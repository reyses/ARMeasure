package com.example.arruler.depth

import com.example.arruler.geometry.PlaneBasis
import com.example.arruler.geometry.Polygon2
import com.example.arruler.geometry.Vec2
import com.example.arruler.geometry.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class PlaneKind { FLOOR, CEILING, WALL, OTHER }

/** Where a plane came from: depth RANSAC, depth corrected by an ARCore plane, ARCore alone, or the floor fallback. */
enum class PlaneSource { DEPTH, FUSED, ARCORE, FALLBACK }

/**
 * A detected plane: unit [normal] and [d] with `normal . p = d`. Horizontal planes have the normal
 * pointing up (+Y); every other plane's normal points toward the centroid of the whole cloud
 * (inward for a room wall). [outline] is the convex hull of the inliers in [basis] coordinates
 * (counter-clockwise about basis.normal = [normal]), [basis] origin = [centroid] of the inliers.
 *
 * Fit diagnostics: [sigma] is the hit-weighted mean one-sigma depth error of the inliers (meters, 0 when
 * ranges were unknown); when [gravityAligned] the plane was constrained to exactly vertical / horizontal and
 * [freeTiltDeg] is how far the unconstrained fit's normal was from that, [freeRms] / [fitRms] the weighted
 * RMS residuals (meters) of the free and the final fit. [mergedFrom] counts the extracted pieces merged into it.
 */
class ExtractedPlane(
    val normal: Vec3,
    val d: Float,
    val centroid: Vec3,
    val inlierCount: Int,
    val kind: PlaneKind,
    val basis: PlaneBasis,
    val outline: List<Vec2>,
    val sigma: Float = 0f,
    val gravityAligned: Boolean = false,
    val freeTiltDeg: Float = 0f,
    val freeRms: Float = 0f,
    val fitRms: Float = 0f,
    val source: PlaneSource = PlaneSource.DEPTH,
    val mergedFrom: Int = 1,
) {
    /** Signed distance of [p] from the plane (positive on the side [normal] points to). */
    fun distanceTo(p: Vec3): Float = normal.dot(p) - d

    /** Outline lifted back to world space. */
    fun outline3d(): List<Vec3> = outline.map { basis.unproject(it) }

    /** Outline area in square meters. */
    fun area(): Float = if (outline.size < 3) 0f else Polygon2(outline).area()

    /** The same plane with the normal reversed (outline re-projected so it stays counter-clockwise about it). */
    internal fun flipped(): ExtractedPlane {
        val n = normal * -1f
        val b = PlaneBasis(n, centroid)
        val o = ConvexHull.of(outline3d().map { b.project(it) })
        return ExtractedPlane(n, -d, centroid, inlierCount, kind, b, o, sigma, gravityAligned, freeTiltDeg, freeRms, fitRms, source, mergedFrom)
    }

    internal fun with(
        kind: PlaneKind = this.kind,
        source: PlaneSource = this.source,
    ) = ExtractedPlane(normal, d, centroid, inlierCount, kind, basis, outline, sigma, gravityAligned, freeTiltDeg, freeRms, fitRms, source, mergedFrom)
}

/**
 * Iterative RANSAC plane detection over a world-space cloud (meters, +Y up = gravity, as ARCore's world).
 *
 * Per plane: RANSAC over a random subsample of the remaining points, then the best candidate is refined
 * on all remaining points (inliers, weighted least-squares refit, three times), only the largest connected
 * patch is kept (two coplanar but separate surfaces are not merged here), and its points are removed.
 *
 * Built for depth-from-motion, whose error grows with distance ([DepthNoiseModel]):
 * - Distance-aware inliers: when the cloud carries per-point observation ranges ([CloudObservations.range])
 *   a point is an inlier within `clamp(sigmaK * sigma(range), inlierThreshold, maxTolerance)` of the plane,
 *   and points with sigma > [maxSigma] (seen only from far) are left out while they are under half the cloud,
 *   so a wall seen from 2.5 m (sigma ~ 13 cm) is one thick slab, not seven 4 cm slices. Without ranges the
 *   fixed [inlierThreshold] applies (the old behaviour; fine for clean clouds).
 * - Weighting: RANSAC scores hypotheses by the truncated quadratic (MSAC) sum of `hits / sigma^2` weights,
 *   and every refit is weighted the same way, so near, often-seen voxels dominate.
 * - Gravity prior ([gravityPrior]): a fit within [gravityToleranceDeg] of vertical is refit as an exactly
 *   vertical plane (a 2D line fit in x, z), one within it of horizontal as an exactly horizontal plane.
 * - Coplanar merging ([mergeCoplanar]): a new plane is merged into an earlier one (and the final list again,
 *   until stable) when their normals differ by < [mergeAngleDeg], their centroids are within
 *   `max(mergeMinOffset, 2 sigma)` of each other's plane, and their extents overlap or come within [mergeGap].
 *   Merged pieces do not count against [maxPlanes].
 * - Floor fallback ([floorFallback]): with no floor plane, the lowest dense horizontal band of points that
 *   covers floor-like area is fitted as the floor. A missing ceiling is never guessed.
 * - ARCore priors: [extract] takes the TRACKING ARCore planes; see [ArPlaneFusion].
 *
 * Classification (world up = +Y): horizontal is FLOOR if within [heightBand] (+ 2 sigma) of the cloud's low
 * end (0.5th percentile of y), CEILING if within it of the high end (99.5th), else OTHER (a table); vertical is
 * WALL; anything in between is OTHER. Pass a seeded [random] for reproducible tests.
 */
class PlaneExtractor(
    private val maxPlanes: Int = 8,
    private val inlierThreshold: Float = 0.02f,
    private val iterations: Int = 300,
    private val minInliers: Int = 300,
    private val ransacSample: Int = 4000,
    private val clusterCell: Float = 0.15f,
    private val heightBand: Float = 0.3f,
    private val random: Random = Random(1),
    /** GL context for the RANSAC hypothesis scoring (null = CPU); obtain it from GpuGate.ransacContext() so the gate decides. Used only for clouds without ranges. */
    private val gpu: com.example.arruler.gpu.GpuContext? = null,
    private val gpuPolicy: com.example.arruler.gpu.GpuProfile? = null,
    private val noise: DepthNoiseModel = DepthNoiseModel.DEFAULT,
    private val sigmaK: Float = 2.5f,
    private val maxTolerance: Float = 0.60f,
    /** Points whose depth sigma exceeds this (observed only from far away) are left out of the fit when enough nearer points exist. */
    private val maxSigma: Float = 0.50f,
    private val gravityPrior: Boolean = true,
    private val gravityToleranceDeg: Float = 15f,
    private val mergeCoplanar: Boolean = true,
    private val mergeAngleDeg: Float = 12f,
    private val mergeMinOffset: Float = 0.10f,
    private val mergeGap: Float = 0.30f,
    private val floorFallback: Boolean = true,
    private val boundingCheck: Boolean = true,
    /** Rescale [noise] from the data (see [noiseScale]) before extracting. */
    private val selfCalibrate: Boolean = true,
) {
    private val sinTol = sin(Math.toRadians(gravityToleranceDeg.toDouble()))
    private val cosTol = cos(Math.toRadians(gravityToleranceDeg.toDouble()))
    private val cosMerge = cos(Math.toRadians(mergeAngleDeg.toDouble()))

    // per-extraction state
    private lateinit var p: FloatArray
    private lateinit var tol: FloatArray
    private lateinit var w: FloatArray
    private lateinit var sig: FloatArray
    private lateinit var hit: IntArray
    /** Robust (Tukey) weight per point from the latest IRLS pass, multiplies [w] in every fit. */
    private lateinit var rw: FloatArray
    private var ranged = false

    /** True-to-model depth sigma ratio measured by the last [extract] of a ranged cloud (1 = the model was right or not measured). */
    var noiseScale = 1f
        private set

    /** Points only (no hits, no ranges): fixed inlier threshold. */
    fun extract(points: FloatArray): List<ExtractedPlane> = extract(CloudObservations(points))

    fun extract(cloud: CloudObservations, arPlanes: List<ArPlaneObservation> = emptyList()): List<ExtractedPlane> {
        val total = cloud.size
        if (total < max(3, minInliers)) return ArPlaneFusion.fuse(emptyList(), arPlanes, Float.NaN, Float.NaN, null, heightBand)
        prepare(cloud)
        if (ranged && selfCalibrate) {
            noiseScale = measureNoiseScale(total)
            if (noiseScale > 1.15f) prepare(cloud, noiseScale)
        }

        // prefer near observations: leave out points seen only from far (sigma > maxSigma) unless that is most of the cloud
        val near = (0 until total).filter { sig[it] <= maxSigma }
        val remaining = if (near.size * 2 >= total) near.toIntArray().copyOf(total) else IntArray(total) { it }
        var remainingCount = if (near.size * 2 >= total) near.size else total
        val ys = FloatArray(total) { p[it * 3 + 1] }.also { it.sort() }
        val lo = ys[(total * 0.005f).toInt().coerceIn(0, total - 1)]
        val hi = ys[(total * 0.995f).toInt().coerceIn(0, total - 1)]
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (i in 0 until total) { cx += p[i * 3]; cy += p[i * 3 + 1]; cz += p[i * 3 + 2] }
        val cloudCentre = Vec3((cx / total).toFloat(), (cy / total).toFloat(), (cz / total).toFloat())

        val drafts = ArrayList<Draft>()
        var attempts = 0
        while (drafts.count { !gravityPrior || it.fit.snapped } < maxPlanes && remainingCount >= minInliers && attempts < maxPlanes * 3) {
            attempts++
            val best = ransac(remaining, remainingCount) ?: break
            val patch = refine(remaining, remainingCount, best) ?: break
            if (patch.size < minInliers) break
            val draft = draftOf(patch)
            val taken = BooleanArray(total)
            for (i in patch) taken[i] = true
            if (ranged) claimTails(draft, remaining, remainingCount, taken)
            var k = 0
            for (j in 0 until remainingCount) { val i = remaining[j]; if (!taken[i]) remaining[k++] = i }
            remainingCount = k
            val into = if (mergeCoplanar) drafts.indexOfFirst { canMerge(it, draft) } else -1
            if (into >= 0) drafts[into] = merged(drafts[into], draft) else drafts += draft
        }
        if (mergeCoplanar) mergeAll(drafts)

        val out = drafts.map { build(it, cloudCentre, lo, hi) }.toMutableList()
        if (boundingCheck) for (k in out.indices) out[k] = bounding(drafts[k], out[k])
        if (floorFallback && out.none { it.kind == PlaneKind.FLOOR }) {
            fallbackFloor(remaining, remainingCount, lo, hi, cloudCentre)?.let { out += it }
        }
        return ArPlaneFusion.fuse(out, arPlanes, lo, hi, cloudCentre, heightBand)
    }

    private fun prepare(cloud: CloudObservations, scale: Float = 1f) {
        val n = cloud.size
        p = cloud.xyz
        ranged = cloud.range != null
        tol = FloatArray(n); w = FloatArray(n); sig = FloatArray(n); hit = IntArray(n); rw = FloatArray(n) { 1f }
        for (i in 0 until n) {
            val h = cloud.hits?.get(i)?.coerceAtLeast(1) ?: 1
            val r = cloud.range?.get(i) ?: Float.NaN
            val s = if (r.isNaN() || r <= 0f) inlierThreshold / sigmaK else noise.sigma(r) * scale
            val t = if (r.isNaN() || r <= 0f) inlierThreshold else (sigmaK * s).coerceIn(inlierThreshold, maxTolerance)
            tol[i] = t
            sig[i] = if (r.isNaN() || r <= 0f) 0f else s
            hit[i] = h
            val q = inlierThreshold / t
            w[i] = h * q * q
        }
    }

    /**
     * How much noisier the cloud is than [noise] predicts: the largest plane is found on a sample, the points
     * within 4 model-sigma of it (inside its extent) give u = residual / sigma, and 1.4826 x MAD(u) estimates
     * the true-to-model sigma ratio (robust to the corner points of adjacent surfaces). Clamped to 1..[MAX_NOISE_SCALE];
     * 1 when no plane is found. Depth-from-motion quality changes with how the phone moved (a user turning on
     * the spot gets far worse depth than one walking), so a fixed model is only a starting point.
     */
    private fun measureNoiseScale(total: Int): Float {
        val idx = IntArray(total) { it }
        val seed = ransac(idx, total) ?: return 1f
        val patch = refine(idx, total, seed) ?: return 1f
        if (patch.size < minInliers) return 1f
        val pl = (robustFit(patch, null) ?: return 1f).plane
        val basis = PlaneBasis(Vec3(pl[0].toFloat(), pl[1].toFloat(), pl[2].toFloat()), Vec3.ZERO)
        val r = rect(basis, patch)
        val us = ArrayList<Float>()
        for (i in 0 until total) {
            val s = sig[i]
            if (s <= 0f) continue
            val o = i * 3
            val d = abs(pl[0] * p[o] + pl[1] * p[o + 1] + pl[2] * p[o + 2] - pl[3]).toFloat()
            if (d > 4f * s) continue
            val q = basis.project(Vec3(p[o], p[o + 1], p[o + 2]))
            if (q.x in r[0]..r[2] && q.y in r[1]..r[3]) us += d / s
        }
        if (us.size < minInliers) return 1f
        us.sort()
        // |u| is half-normal: its median is 0.6745 sigma
        val scale = us[us.size / 2] / 0.6745f
        for (i in 0 until total) rw[i] = 1f
        return scale.coerceIn(1f, MAX_NOISE_SCALE)
    }

    // ---- RANSAC ----

    private fun ransac(idx: IntArray, n: Int): DoubleArray? {
        if (gpu != null && !ranged) return com.example.arruler.gpu.GpuRansac.bestPlane(gpu, p, idx, n, iterations, ransacSample, inlierThreshold, minInliers, random, gpuPolicy).value
        val sub: IntArray = if (n <= ransacSample) IntArray(n) { idx[it] }
        else IntArray(ransacSample) { idx[random.nextInt(n)] }
        var bestScore = 0.0
        var bestCount = 0
        var best: DoubleArray? = null
        val gravity = gravityPrior && ranged
        repeat(iterations) { iter ->
            val a = sub[random.nextInt(sub.size)] * 3
            val b = sub[random.nextInt(sub.size)] * 3
            val c = sub[random.nextInt(sub.size)] * 3
            val mx = (p[a] + p[b] + p[c]) / 3.0; val my = (p[a + 1] + p[b + 1] + p[c + 1]) / 3.0; val mz = (p[a + 2] + p[b + 2] + p[c + 2]) / 3.0
            var nx: Double; var ny: Double; var nz: Double
            var oblique = false
            when (if (gravity) iter % 3 else 0) {
                1 -> {
                    // vertical through a and b (two points fix a wall; far less sensitive to noise than three)
                    val dx = (p[b] - p[a]).toDouble(); val dz = (p[b + 2] - p[a + 2]).toDouble()
                    val l = sqrt(dx * dx + dz * dz)
                    if (l < 0.2) return@repeat
                    nx = -dz / l; ny = 0.0; nz = dx / l
                }
                2 -> { nx = 0.0; ny = 1.0; nz = 0.0 }   // horizontal at the three points' mean height
                else -> {
                    val ux = (p[b] - p[a]).toDouble(); val uy = (p[b + 1] - p[a + 1]).toDouble(); val uz = (p[b + 2] - p[a + 2]).toDouble()
                    val vx = (p[c] - p[a]).toDouble(); val vy = (p[c + 1] - p[a + 1]).toDouble(); val vz = (p[c + 2] - p[a + 2]).toDouble()
                    nx = uy * vz - uz * vy; ny = uz * vx - ux * vz; nz = ux * vy - uy * vx
                    val len = sqrt(nx * nx + ny * ny + nz * nz)
                    if (len < 1e-9) return@repeat
                    nx /= len; ny /= len; nz /= len
                    if (gravity) {
                        // snap the hypothesis too: noisy 3-point normals scatter by several degrees
                        if (abs(ny) <= sinTol) { val l = sqrt(nx * nx + nz * nz); nx /= l; nz /= l; ny = 0.0 }
                        else if (abs(ny) >= cosTol) { nx = 0.0; nz = 0.0; ny = if (ny > 0) 1.0 else -1.0 }
                        else oblique = true
                    }
                }
            }
            val dd = nx * mx + ny * my + nz * mz
            var cnt = 0
            var score = 0.0
            for (i in sub) {
                val o = i * 3
                val r = abs(nx * p[o] + ny * p[o + 1] + nz * p[o + 2] - dd)
                val t = tol[i]
                if (r <= t) {
                    cnt++
                    if (ranged) { val q = r / t; score += w[i] * (1.0 - q * q) }
                }
            }
            if (!ranged) score = cnt.toDouble()
            // rooms are built from vertical and horizontal planes: with a wide (noisy) inlier band an oblique plane
            // through a corner can collect two surfaces at once, so it must clearly beat the aligned ones
            if (oblique) score *= OBLIQUE_PENALTY
            if (score > bestScore) { bestScore = score; bestCount = cnt; best = doubleArrayOf(nx, ny, nz, dd) }
        }
        val scaled = bestCount.toLong() * n / sub.size
        return if (scaled < minInliers) null else best
    }

    /** Refits on all remaining points and returns the point indices of the final planar patch. */
    private fun refine(idx: IntArray, n: Int, seed: DoubleArray): IntArray? {
        var plane = seed
        repeat(3) {
            val inl = collect(idx, n, plane)
            if (inl.size < 3) return null
            plane = robustFit(inl, plane)?.plane ?: return null
        }
        val inl = collect(idx, n, plane)
        if (inl.size < 3) return null
        return largestPatch(inl, plane)
    }

    /**
     * Iteratively reweighted fit: Tukey biweight on the residual over [TUKEY_K] sigma of each point, so the
     * points of an adjacent perpendicular surface that fall inside the (wide) inlier band near a corner, which
     * all lie on the room side, pull the plane much less than a plain least-squares fit would. Ranged clouds only.
     */
    private fun robustFit(inl: IntArray, start: DoubleArray?): Fit? {
        for (i in inl) rw[i] = 1f
        if (!ranged) return fitConstrained(inl)
        var plane = start ?: (fitConstrained(inl)?.plane ?: return null)
        var fit: Fit? = null
        repeat(IRLS_PASSES) {
            for (i in inl) {
                val r = abs(plane[0] * p[i * 3] + plane[1] * p[i * 3 + 1] + plane[2] * p[i * 3 + 2] - plane[3])
                val u = (r / (TUKEY_K * tol[i] / sigmaK)).toFloat()
                rw[i] = if (u >= 1f) 0f else (1f - u * u).let { it * it }
            }
            val next = fitConstrained(inl) ?: return fit
            fit = next
            plane = next.plane
        }
        return fit
    }

    /**
     * Marks as [taken] the remaining points within [CLAIM_K] sigma of the accepted plane and inside its
     * in-plane extent (plus 20 cm). With a decimetre-scale sigma the 2.5-sigma band leaves a few hundred tail
     * points on each side of a big wall, enough for a phantom parallel plane; they are removed with the
     * plane but do not enter its fit.
     */
    private fun claimTails(dr: Draft, idx: IntArray, n: Int, taken: BooleanArray) {
        val pl = dr.plane
        val basis = PlaneBasis(Vec3(pl[0].toFloat(), pl[1].toFloat(), pl[2].toFloat()), Vec3.ZERO)
        val r = rect(basis, dr.idx)
        val m = 0.2f
        for (j in 0 until n) {
            val i = idx[j]
            if (taken[i]) continue
            val o = i * 3
            val dist = abs(pl[0] * p[o] + pl[1] * p[o + 1] + pl[2] * p[o + 2] - pl[3])
            if (dist > (CLAIM_K / sigmaK) * tol[i]) continue
            val q = basis.project(Vec3(p[o], p[o + 1], p[o + 2]))
            if (q.x >= r[0] - m && q.x <= r[2] + m && q.y >= r[1] - m && q.y <= r[3] + m) taken[i] = true
        }
    }

    private fun collect(idx: IntArray, n: Int, pl: DoubleArray): IntArray {
        var cnt = 0
        val tmp = IntArray(n)
        for (j in 0 until n) {
            val i = idx[j]
            val o = i * 3
            if (abs(pl[0] * p[o] + pl[1] * p[o + 1] + pl[2] * p[o + 2] - pl[3]) <= tol[i]) tmp[cnt++] = i
        }
        return tmp.copyOf(cnt)
    }

    private class Fit(val plane: DoubleArray, val free: DoubleArray, val snapped: Boolean)

    /** Weighted least-squares plane, constrained to vertical / horizontal when the free fit is near one. */
    private fun fitConstrained(inl: IntArray): Fit? {
        val free = fitFree(inl) ?: return null
        if (!gravityPrior) return Fit(free, free, false)
        val ny = abs(free[1])
        return when {
            ny <= sinTol -> fitVertical(inl)?.let { Fit(it, free, true) } ?: Fit(free, free, false)
            ny >= cosTol -> Fit(fitHorizontal(inl), free, true)
            else -> Fit(free, free, false)
        }
    }

    /** Weighted PCA plane (nx, ny, nz, d) through the indexed points, or null if degenerate. */
    private fun fitFree(inl: IntArray): DoubleArray? {
        var sw = 0.0; var mx = 0.0; var my = 0.0; var mz = 0.0
        for (i in inl) { val wi = (w[i] * rw[i]).toDouble(); sw += wi; mx += wi * p[i * 3]; my += wi * p[i * 3 + 1]; mz += wi * p[i * 3 + 2] }
        if (sw <= 0.0) return null
        mx /= sw; my /= sw; mz /= sw
        var xx = 0.0; var xy = 0.0; var xz = 0.0; var yy = 0.0; var yz = 0.0; var zz = 0.0
        for (i in inl) {
            val wi = (w[i] * rw[i]).toDouble()
            val dx = p[i * 3] - mx; val dy = p[i * 3 + 1] - my; val dz = p[i * 3 + 2] - mz
            xx += wi * dx * dx; xy += wi * dx * dy; xz += wi * dx * dz; yy += wi * dy * dy; yz += wi * dy * dz; zz += wi * dz * dz
        }
        val nrm = smallestEigenvector(arrayOf(doubleArrayOf(xx, xy, xz), doubleArrayOf(xy, yy, yz), doubleArrayOf(xz, yz, zz)))
        return doubleArrayOf(nrm[0], nrm[1], nrm[2], nrm[0] * mx + nrm[1] * my + nrm[2] * mz)
    }

    /** Exactly vertical plane: weighted 2D line fit of the (x, z) footprint. */
    private fun fitVertical(inl: IntArray): DoubleArray? {
        var sw = 0.0; var mx = 0.0; var mz = 0.0
        for (i in inl) { val wi = (w[i] * rw[i]).toDouble(); sw += wi; mx += wi * p[i * 3]; mz += wi * p[i * 3 + 2] }
        if (sw <= 0.0) return null
        mx /= sw; mz /= sw
        var xx = 0.0; var xz = 0.0; var zz = 0.0
        for (i in inl) {
            val wi = (w[i] * rw[i]).toDouble()
            val dx = p[i * 3] - mx; val dz = p[i * 3 + 2] - mz
            xx += wi * dx * dx; xz += wi * dx * dz; zz += wi * dz * dz
        }
        if (xx + zz < 1e-12) return null
        val theta = 0.5 * atan2(2 * xz, xx - zz)   // direction of the line (major axis)
        val nx = -sin(theta); val nz = cos(theta)
        return doubleArrayOf(nx, 0.0, nz, nx * mx + nz * mz)
    }

    /** Exactly horizontal plane at the weighted mean height. */
    private fun fitHorizontal(inl: IntArray): DoubleArray {
        var sw = 0.0; var my = 0.0
        for (i in inl) { val wi = (w[i] * rw[i]).toDouble(); sw += wi; my += wi * p[i * 3 + 1] }
        return doubleArrayOf(0.0, 1.0, 0.0, if (sw > 0) my / sw else 0.0)
    }

    private fun largestPatch(inl: IntArray, pl: DoubleArray): IntArray {
        val basis = PlaneBasis(Vec3(pl[0].toFloat(), pl[1].toFloat(), pl[2].toFloat()), Vec3(0f, 0f, 0f))
        val cells = HashMap<Long, MutableList<Int>>()
        for (i in inl) {
            val q = basis.project(Vec3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]))
            val cu = floor((q.x / clusterCell).toDouble()).toLong()
            val cv = floor((q.y / clusterCell).toDouble()).toLong()
            cells.getOrPut((cu shl 32) xor (cv and 0xFFFFFFFFL)) { ArrayList() }.add(i)
        }
        val seen = HashSet<Long>()
        var bestList: List<Int> = emptyList()
        for (start in cells.keys) {
            if (!seen.add(start)) continue
            val members = ArrayList<Int>()
            val stack = ArrayDeque<Long>()
            stack.add(start)
            while (stack.isNotEmpty()) {
                val k = stack.removeLast()
                members += cells.getValue(k)
                val cu = k shr 32; val cv = (k and 0xFFFFFFFFL).toInt().toLong()
                for (du in -1..1) for (dv in -1..1) {
                    if (du == 0 && dv == 0) continue
                    val nk = ((cu + du) shl 32) xor ((cv + dv) and 0xFFFFFFFFL)
                    if (nk in cells && seen.add(nk)) stack.add(nk)
                }
            }
            if (members.size > bestList.size) bestList = members
        }
        return bestList.toIntArray()
    }

    // ---- drafts and merging ----

    private inner class Draft(val idx: IntArray, val fit: Fit, val merged: Int) {
        val plane get() = fit.plane
        val centroid: DoubleArray
        val sigma: Double

        init {
            var mx = 0.0; var my = 0.0; var mz = 0.0; var sh = 0.0; var ss = 0.0
            for (i in idx) { mx += p[i * 3]; my += p[i * 3 + 1]; mz += p[i * 3 + 2]; sh += hit[i]; ss += hit[i] * sig[i].toDouble() }
            centroid = doubleArrayOf(mx / idx.size, my / idx.size, mz / idx.size)
            sigma = if (sh > 0) ss / sh else 0.0
        }

        fun offsetOf(c: DoubleArray) = abs(plane[0] * c[0] + plane[1] * c[1] + plane[2] * c[2] - plane[3])
    }

    private fun draftOf(idx: IntArray, merged: Int = 1): Draft {
        val fit = robustFit(idx, null) ?: Fit(fitFree(idx) ?: doubleArrayOf(0.0, 1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0, 0.0), false)
        return Draft(idx, fit, merged)
    }

    private fun canMerge(a: Draft, b: Draft): Boolean {
        val dot = abs(a.plane[0] * b.plane[0] + a.plane[1] * b.plane[1] + a.plane[2] * b.plane[2])
        if (dot < cosMerge) return false
        val offTol = max(mergeMinOffset.toDouble(), 2.0 * max(a.sigma, b.sigma))
        if (max(a.offsetOf(b.centroid), b.offsetOf(a.centroid)) > offTol) return false
        return extentGap(a, b) <= mergeGap
    }

    /** Gap (meters) between the in-plane bounding rectangles of the two point sets, in a's plane frame. */
    private fun extentGap(a: Draft, b: Draft): Double {
        val basis = PlaneBasis(Vec3(a.plane[0].toFloat(), a.plane[1].toFloat(), a.plane[2].toFloat()), Vec3.ZERO)
        val ra = rect(basis, a.idx); val rb = rect(basis, b.idx)
        val gu = max(0f, max(ra[0] - rb[2], rb[0] - ra[2]))
        val gv = max(0f, max(ra[1] - rb[3], rb[1] - ra[3]))
        return sqrt((gu * gu + gv * gv).toDouble())
    }

    /** 2nd-98th percentile rectangle (u0, v0, u1, v1) of the points in [basis] (robust to stray points). */
    private fun rect(basis: PlaneBasis, idx: IntArray): FloatArray {
        val us = FloatArray(idx.size); val vs = FloatArray(idx.size)
        for ((k, i) in idx.withIndex()) {
            val q = basis.project(Vec3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]))
            us[k] = q.x; vs[k] = q.y
        }
        us.sort(); vs.sort()
        val lo = (idx.size * 0.02).toInt().coerceIn(0, idx.size - 1)
        val hi = (idx.size * 0.98).toInt().coerceIn(0, idx.size - 1)
        return floatArrayOf(us[lo], vs[lo], us[hi], vs[hi])
    }

    private fun merged(a: Draft, b: Draft): Draft = draftOf(a.idx + b.idx, a.merged + b.merged)

    private fun mergeAll(drafts: MutableList<Draft>) {
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in drafts.indices) for (j in i + 1 until drafts.size) {
                if (canMerge(drafts[i], drafts[j])) {
                    drafts[i] = merged(drafts[i], drafts[j])
                    drafts.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
    }

    // ---- output ----

    private fun build(dr: Draft, cloud: Vec3, lo: Float, hi: Float): ExtractedPlane {
        val f = dr.plane
        var nx = f[0]; var ny = f[1]; var nz = f[2]; var d = f[3]
        val mx = dr.centroid[0]; val my = dr.centroid[1]; val mz = dr.centroid[2]
        val horizontal = abs(ny) >= (if (gravityPrior) cosTol else HORIZONTAL_COS)
        val flip = if (horizontal) ny < 0
        else nx * (cloud.x - mx) + ny * (cloud.y - my) + nz * (cloud.z - mz) < 0
        if (flip) { nx = -nx; ny = -ny; nz = -nz; d = -d }
        val normal = Vec3(nx.toFloat(), ny.toFloat(), nz.toFloat())
        val centroid = Vec3(mx.toFloat(), my.toFloat(), mz.toFloat())
        val basis = PlaneBasis(normal, centroid)
        val hull = ConvexHull.of(dr.idx.map { basis.project(Vec3(p[it * 3], p[it * 3 + 1], p[it * 3 + 2])) })
        val band = heightBand + 2f * dr.sigma.toFloat()
        val vertical = abs(ny) <= (if (gravityPrior) sinTol else VERTICAL_SIN)
        val kind = when {
            horizontal -> when {
                centroid.y - lo < band -> PlaneKind.FLOOR
                hi - centroid.y < band -> PlaneKind.CEILING
                else -> PlaneKind.OTHER
            }
            vertical -> PlaneKind.WALL
            else -> PlaneKind.OTHER
        }
        val free = dr.fit.free
        val tilt = if (dr.fit.snapped) {
            val c = abs(free[0] * f[0] + free[1] * f[1] + free[2] * f[2]).coerceAtMost(1.0)
            Math.toDegrees(acos(c)).toFloat()
        } else 0f
        return ExtractedPlane(
            normal, d.toFloat(), centroid, dr.idx.size, kind, basis, hull,
            sigma = dr.sigma.toFloat(),
            gravityAligned = dr.fit.snapped,
            freeTiltDeg = tilt,
            freeRms = rms(dr.idx, free),
            fitRms = rms(dr.idx, f),
            mergedFrom = dr.merged,
        )
    }

    /**
     * A room boundary (WALL, FLOOR, CEILING) has one empty side: depth cannot see through a wall, so points
     * more than max(30 cm, 3 sigma) beyond it, inside its in-plane extent, are noise only. Points are counted on
     * both sides (extent = the inliers' 2-98 % rectangle shrunk by max(30 cm, 3 sigma) per side, so surfaces
     * meeting this one at its edges do not count). When BOTH sides hold more than [BEHIND_RATIO] of the plane's
     * inliers it is an interior surface (furniture, a table, or a plane through speckle) and becomes OTHER. A
     * wall whose populated side is opposite its normal is flipped, so wall normals point into the room even
     * where the cloud centroid lies outside the wall's room part (L-shapes). A floor needs its empty side below,
     * a ceiling above.
     */
    private fun bounding(dr: Draft, pl: ExtractedPlane): ExtractedPlane {
        if (pl.kind == PlaneKind.OTHER) return pl
        val r = rect(pl.basis, dr.idx)
        val m = max(BEHIND_MIN, 3f * pl.sigma)
        r[0] += m; r[1] += m; r[2] -= m; r[3] -= m
        if (r[0] >= r[2] || r[1] >= r[3]) return pl
        val nx = pl.normal.x; val ny = pl.normal.y; val nz = pl.normal.z; val d = pl.d
        var front = 0; var back = 0
        for (j in 0 until p.size / 3) {
            val o = j * 3
            val dist = nx * p[o] + ny * p[o + 1] + nz * p[o + 2] - d
            val lim = max(BEHIND_MIN, 3f * sig[j])
            if (dist > -lim && dist < lim) continue
            val q = pl.basis.project(Vec3(p[o], p[o + 1], p[o + 2]))
            if (q.x in r[0]..r[2] && q.y in r[1]..r[3]) { if (dist > 0) front++ else back++ }
        }
        val limit = BEHIND_RATIO * dr.idx.size
        return when {
            front > limit && back > limit -> pl.with(kind = PlaneKind.OTHER)
            pl.kind == PlaneKind.FLOOR && back > limit -> pl.with(kind = PlaneKind.OTHER)
            pl.kind == PlaneKind.CEILING && front > limit -> pl.with(kind = PlaneKind.OTHER)
            pl.kind == PlaneKind.WALL && back > front -> pl.flipped()
            else -> pl
        }
    }

    private fun rms(idx: IntArray, pl: DoubleArray): Float {
        var sw = 0.0; var s = 0.0
        for (i in idx) {
            val r = pl[0] * p[i * 3] + pl[1] * p[i * 3 + 1] + pl[2] * p[i * 3 + 2] - pl[3]
            sw += w[i]; s += w[i] * r * r
        }
        return if (sw > 0) sqrt(s / sw).toFloat() else 0f
    }

    /**
     * The lowest dense horizontal band of the points no plane took, as the floor, when RANSAC found none (a
     * floor swept thinly breaks into patches below minInliers). Sliding 15 cm windows from the cloud's low end
     * up to its middle height: the first window holding >= minInliers / 3 unassigned points that cover at least
     * [FALLBACK_MIN_AREA] of (x, z) in 20 cm cells (a wall's foot is a strip, not an area) is fitted as a
     * horizontal plane at the weighted mean height of the points within max(5 cm, their tolerance) of it.
     */
    private fun fallbackFloor(rest: IntArray, restCount: Int, lo: Float, hi: Float, cloud: Vec3): ExtractedPlane? {
        if (restCount == 0) return null
        val bin = 0.05f
        var base = lo
        for (j in 0 until restCount) base = minOf(base, p[rest[j] * 3 + 1])
        val top = (lo + hi) / 2                     // the floor is in the lower half of the room
        val bins = ((top - base) / bin).toInt() + 3
        if (bins < 4) return null
        val byBin = Array(bins) { ArrayList<Int>() }
        for (j in 0 until restCount) {
            val i = rest[j]
            val b = ((p[i * 3 + 1] - base) / bin).toInt()
            if (b in 0 until bins) byBin[b] += i
        }
        for (b in 0 until bins - 2) {
            val window = byBin[b] + byBin[b + 1] + byBin[b + 2]
            if (window.size < minInliers / 3) continue
            val cells = HashSet<Long>()
            for (i in window) cells += (floor(p[i * 3] / 0.2f).toLong() shl 32) xor (floor(p[i * 3 + 2] / 0.2f).toLong() and 0xFFFFFFFFL)
            if (cells.size * 0.04f < FALLBACK_MIN_AREA) continue
            var sw = 0.0; var sy = 0.0
            for (i in window) { sw += w[i]; sy += w[i] * p[i * 3 + 1] }
            val y0 = (sy / sw).toFloat()
            val band = (0 until restCount).map { rest[it] }.filter { abs(p[it * 3 + 1] - y0) <= max(bin, tol[it]) }.toIntArray()
            for (i in band) rw[i] = 1f
            val fit = Fit(fitHorizontal(band), fitFree(band) ?: doubleArrayOf(0.0, 1.0, 0.0, y0.toDouble()), true)
            return build(Draft(band, fit, 1), cloud, lo, hi).with(kind = PlaneKind.FLOOR, source = PlaneSource.FALLBACK)
        }
        return null
    }

    companion object {
        private const val HORIZONTAL_COS = 0.94
        private const val VERTICAL_SIN = 0.17
        private const val FALLBACK_MIN_AREA = 1.5f
        private const val BEHIND_MIN = 0.30f
        private const val BEHIND_RATIO = 0.2f
        private const val TUKEY_K = 2.0f
        private const val IRLS_PASSES = 3
        private const val OBLIQUE_PENALTY = 0.5
        private const val CLAIM_K = 3.5f
        private const val MAX_NOISE_SCALE = 2.5f

        /** The pre-2026-10-03 extractor: fixed 2 cm threshold, no merging, no gravity prior, no fallback (for comparisons). */
        fun legacy(maxPlanes: Int = 8, random: Random = Random(1)) = PlaneExtractor(
            maxPlanes = maxPlanes, random = random,
            gravityPrior = false, mergeCoplanar = false, floorFallback = false, boundingCheck = false,
        )

        /** Unit eigenvector of the smallest eigenvalue of a symmetric 3x3 (cyclic Jacobi). */
        internal fun smallestEigenvector(m: Array<DoubleArray>): DoubleArray {
            val a = Array(3) { m[it].copyOf() }
            val v = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
            repeat(30) {
                val off = abs(a[0][1]) + abs(a[0][2]) + abs(a[1][2])
                if (off < 1e-14 * (abs(a[0][0]) + abs(a[1][1]) + abs(a[2][2]) + 1e-30)) return@repeat
                for ((pp, qq) in listOf(0 to 1, 0 to 2, 1 to 2)) {
                    if (abs(a[pp][qq]) < 1e-300) continue
                    val theta = (a[qq][qq] - a[pp][pp]) / (2.0 * a[pp][qq])
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
                    val c = 1.0 / sqrt(t * t + 1.0); val s = t * c
                    for (k in 0 until 3) {
                        val akp = a[k][pp]; val akq = a[k][qq]
                        a[k][pp] = c * akp - s * akq; a[k][qq] = s * akp + c * akq
                    }
                    for (k in 0 until 3) {
                        val apk = a[pp][k]; val aqk = a[qq][k]
                        a[pp][k] = c * apk - s * aqk; a[qq][k] = s * apk + c * aqk
                    }
                    for (k in 0 until 3) {
                        val vkp = v[k][pp]; val vkq = v[k][qq]
                        v[k][pp] = c * vkp - s * vkq; v[k][qq] = s * vkp + c * vkq
                    }
                }
            }
            var best = 0
            for (i in 1 until 3) if (a[i][i] < a[best][best]) best = i
            return doubleArrayOf(v[0][best], v[1][best], v[2][best])
        }
    }
}
