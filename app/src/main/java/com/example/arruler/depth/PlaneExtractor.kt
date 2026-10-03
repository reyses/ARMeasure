package com.example.arruler.depth

import com.example.arruler.geometry.PlaneBasis
import com.example.arruler.geometry.Vec2
import com.example.arruler.geometry.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

enum class PlaneKind { FLOOR, CEILING, WALL, OTHER }

/**
 * A detected plane: unit [normal] and [d] with `normal . p = d`. Horizontal planes have the normal
 * pointing up (+Y); every other plane's normal points toward the centroid of the whole cloud
 * (inward for a room wall). [outline] is the convex hull of the inliers in [basis] coordinates
 * (counter-clockwise about basis.normal = [normal]), [basis] origin = [centroid] of the inliers.
 */
class ExtractedPlane(
    val normal: Vec3,
    val d: Float,
    val centroid: Vec3,
    val inlierCount: Int,
    val kind: PlaneKind,
    val basis: PlaneBasis,
    val outline: List<Vec2>
) {
    /** Signed distance of [p] from the plane (positive on the side [normal] points to). */
    fun distanceTo(p: Vec3): Float = normal.dot(p) - d

    /** Outline lifted back to world space. */
    fun outline3d(): List<Vec3> = outline.map { basis.unproject(it) }
}

/**
 * Iterative RANSAC plane detection over packed xyz points (meters, world space, +Y up).
 *
 * Per plane: 3-point RANSAC over a random subsample of the remaining points (cheap), then the best
 * candidate is refined on all remaining points: inliers, least-squares (PCA) refit, inliers again,
 * keep only the largest connected patch (so two coplanar but separate surfaces are not merged), refit.
 * The patch's points are removed and the search repeats until [maxPlanes] or no candidate reaches
 * [minInliers]. Pass a seeded [random] for reproducible tests.
 *
 * Classification (world up = +Y): |n.y| >= 0.94 is horizontal, FLOOR if within [heightBand] of the
 * cloud's low end (0.5th percentile of y), CEILING if within it of the high end (99.5th), else OTHER
 * (a table); |n.y| <= 0.17 is WALL; anything in between is OTHER.
 */
class PlaneExtractor(
    private val maxPlanes: Int = 8,
    private val inlierThreshold: Float = 0.02f,
    private val iterations: Int = 300,
    private val minInliers: Int = 300,
    private val ransacSample: Int = 4000,
    private val clusterCell: Float = 0.15f,
    private val heightBand: Float = 0.3f,
    private val random: Random = Random(1)
) {

    fun extract(points: FloatArray): List<ExtractedPlane> {
        val total = points.size / 3
        if (total < max(3, minInliers)) return emptyList()

        var remaining = IntArray(total) { it }
        var remainingCount = total
        val ys = FloatArray(total) { points[it * 3 + 1] }.also { it.sort() }
        val lo = ys[(total * 0.005f).toInt().coerceIn(0, total - 1)]
        val hi = ys[(total * 0.995f).toInt().coerceIn(0, total - 1)]
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (i in 0 until total) { cx += points[i * 3]; cy += points[i * 3 + 1]; cz += points[i * 3 + 2] }
        val cloudCentre = Vec3((cx / total).toFloat(), (cy / total).toFloat(), (cz / total).toFloat())

        val out = ArrayList<ExtractedPlane>()
        while (out.size < maxPlanes && remainingCount >= minInliers) {
            val best = ransac(points, remaining, remainingCount) ?: break
            val patch = refine(points, remaining, remainingCount, best) ?: break
            if (patch.size < minInliers) break
            out += build(points, patch, cloudCentre, lo, hi)
            val taken = BooleanArray(total)
            for (i in patch) taken[i] = true
            var k = 0
            for (j in 0 until remainingCount) { val i = remaining[j]; if (!taken[i]) remaining[k++] = i }
            remainingCount = k
        }
        return out
    }

    // ---- RANSAC ----

    private fun ransac(p: FloatArray, idx: IntArray, n: Int): DoubleArray? {
        val sub: IntArray = if (n <= ransacSample) IntArray(n) { idx[it] }
        else IntArray(ransacSample) { idx[random.nextInt(n)] }
        var bestCount = 0
        var best: DoubleArray? = null
        repeat(iterations) {
            val a = sub[random.nextInt(sub.size)] * 3
            val b = sub[random.nextInt(sub.size)] * 3
            val c = sub[random.nextInt(sub.size)] * 3
            val ux = (p[b] - p[a]).toDouble(); val uy = (p[b + 1] - p[a + 1]).toDouble(); val uz = (p[b + 2] - p[a + 2]).toDouble()
            val vx = (p[c] - p[a]).toDouble(); val vy = (p[c + 1] - p[a + 1]).toDouble(); val vz = (p[c + 2] - p[a + 2]).toDouble()
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-9) return@repeat
            nx /= len; ny /= len; nz /= len
            val dd = nx * p[a] + ny * p[a + 1] + nz * p[a + 2]
            var cnt = 0
            for (i in sub) {
                val o = i * 3
                if (abs(nx * p[o] + ny * p[o + 1] + nz * p[o + 2] - dd) <= inlierThreshold) cnt++
            }
            if (cnt > bestCount) { bestCount = cnt; best = doubleArrayOf(nx, ny, nz, dd) }
        }
        val scaled = bestCount.toLong() * n / sub.size
        return if (scaled < minInliers) null else best
    }

    /** Refits on all remaining points and returns the point indices of the final planar patch. */
    private fun refine(p: FloatArray, idx: IntArray, n: Int, seed: DoubleArray): IntArray? {
        var plane = seed
        var inl = collect(p, idx, n, plane)
        if (inl.size < 3) return null
        plane = fit(p, inl) ?: return null
        inl = collect(p, idx, n, plane)
        if (inl.size < 3) return null
        plane = fit(p, inl) ?: return null
        inl = collect(p, idx, n, plane)
        if (inl.size < 3) return null
        val patch = largestPatch(p, inl, plane)
        // final plane equation is recomputed in build() from the patch
        return patch
    }

    private fun collect(p: FloatArray, idx: IntArray, n: Int, pl: DoubleArray): IntArray {
        var cnt = 0
        val tmp = IntArray(n)
        for (j in 0 until n) {
            val o = idx[j] * 3
            if (abs(pl[0] * p[o] + pl[1] * p[o + 1] + pl[2] * p[o + 2] - pl[3]) <= inlierThreshold) tmp[cnt++] = idx[j]
        }
        return tmp.copyOf(cnt)
    }

    /** Least-squares plane (nx, ny, nz, d) through the indexed points, or null if degenerate. */
    private fun fit(p: FloatArray, inl: IntArray): DoubleArray? {
        val n = inl.size
        var mx = 0.0; var my = 0.0; var mz = 0.0
        for (i in inl) { mx += p[i * 3]; my += p[i * 3 + 1]; mz += p[i * 3 + 2] }
        mx /= n; my /= n; mz /= n
        var xx = 0.0; var xy = 0.0; var xz = 0.0; var yy = 0.0; var yz = 0.0; var zz = 0.0
        for (i in inl) {
            val dx = p[i * 3] - mx; val dy = p[i * 3 + 1] - my; val dz = p[i * 3 + 2] - mz
            xx += dx * dx; xy += dx * dy; xz += dx * dz; yy += dy * dy; yz += dy * dz; zz += dz * dz
        }
        val nrm = smallestEigenvector(arrayOf(doubleArrayOf(xx, xy, xz), doubleArrayOf(xy, yy, yz), doubleArrayOf(xz, yz, zz)))
        return doubleArrayOf(nrm[0], nrm[1], nrm[2], nrm[0] * mx + nrm[1] * my + nrm[2] * mz)
    }

    private fun largestPatch(p: FloatArray, inl: IntArray, pl: DoubleArray): IntArray {
        val basis = PlaneBasis(Vec3(pl[0].toFloat(), pl[1].toFloat(), pl[2].toFloat()), Vec3(0f, 0f, 0f))
        val cells = HashMap<Long, MutableList<Int>>()
        for (i in inl) {
            val q = basis.project(Vec3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]))
            val cu = Math.floor((q.x / clusterCell).toDouble()).toLong()
            val cv = Math.floor((q.y / clusterCell).toDouble()).toLong()
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

    private fun build(p: FloatArray, patch: IntArray, cloud: Vec3, lo: Float, hi: Float): ExtractedPlane {
        val f = fit(p, patch)!!
        var nx = f[0]; var ny = f[1]; var nz = f[2]; var d = f[3]
        var mx = 0.0; var my = 0.0; var mz = 0.0
        for (i in patch) { mx += p[i * 3]; my += p[i * 3 + 1]; mz += p[i * 3 + 2] }
        mx /= patch.size; my /= patch.size; mz /= patch.size
        val horizontal = abs(ny) >= HORIZONTAL_COS
        val flip = if (horizontal) ny < 0
        else nx * (cloud.x - mx) + ny * (cloud.y - my) + nz * (cloud.z - mz) < 0
        if (flip) { nx = -nx; ny = -ny; nz = -nz; d = -d }
        val normal = Vec3(nx.toFloat(), ny.toFloat(), nz.toFloat())
        val centroid = Vec3(mx.toFloat(), my.toFloat(), mz.toFloat())
        val basis = PlaneBasis(normal, centroid)
        val hull = ConvexHull.of(patch.map { basis.project(Vec3(p[it * 3], p[it * 3 + 1], p[it * 3 + 2])) })
        val kind = when {
            horizontal -> when {
                centroid.y - lo < heightBand -> PlaneKind.FLOOR
                hi - centroid.y < heightBand -> PlaneKind.CEILING
                else -> PlaneKind.OTHER
            }
            abs(ny) <= VERTICAL_SIN -> PlaneKind.WALL
            else -> PlaneKind.OTHER
        }
        return ExtractedPlane(normal, d.toFloat(), centroid, patch.size, kind, basis, hull)
    }

    companion object {
        private const val HORIZONTAL_COS = 0.94
        private const val VERTICAL_SIN = 0.17

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
