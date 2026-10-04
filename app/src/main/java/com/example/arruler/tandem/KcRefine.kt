package com.example.arruler.tandem

import com.example.arruler.objscan.LongIntMap
import com.example.arruler.objscan.cellKey
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Uniform-grid nearest-neighbour index over a packed xyz array. [cell] must be at least the largest query radius. */
internal class NnGrid(private val pts: FloatArray, val cell: Float) {
    private val n = pts.size / 3
    private val minX: Float; private val minY: Float; private val minZ: Float
    private val map = LongIntMap(n / 2 + 8)
    private val start: IntArray
    private val items = IntArray(n)

    init {
        var x0 = Float.MAX_VALUE; var y0 = x0; var z0 = x0
        for (i in 0 until n) { x0 = min(x0, pts[i * 3]); y0 = min(y0, pts[i * 3 + 1]); z0 = min(z0, pts[i * 3 + 2]) }
        minX = x0 - 2 * cell; minY = y0 - 2 * cell; minZ = z0 - 2 * cell
        val cellOf = IntArray(n)
        var cells = 0
        val counts = ArrayList<Int>()
        for (i in 0 until n) {
            val key = cellKey(idx(pts[i * 3], minX), idx(pts[i * 3 + 1], minY), idx(pts[i * 3 + 2], minZ))
            var id = map[key]
            if (id < 0) { id = cells++; map[key] = id; counts.add(0) }
            counts[id] = counts[id] + 1
            cellOf[i] = id
        }
        start = IntArray(cells + 1)
        for (c in 0 until cells) start[c + 1] = start[c] + counts[c]
        val fill = IntArray(cells)
        for (i in 0 until n) { val c = cellOf[i]; items[start[c] + fill[c]++] = i }
    }

    private fun idx(v: Float, m: Float) = floor((v - m) / cell).toInt()

    /**
     * Squared distance to the nearest point within [maxD] whose unit normal agrees with (nx, ny, nz) (|cos| >= [cosMin]; a point with
     * no normal agrees with everything), or -1 when there is none. Matching only like-oriented surface keeps the two faces that meet
     * at an edge from "matching" each other across it.
     */
    fun nearestSq(x: Float, y: Float, z: Float, maxD: Float, normals: FloatArray, nx: Float, ny: Float, nz: Float, cosMin: Float): Float {
        val cx = idx(x, minX); val cy = idx(y, minY); val cz = idx(z, minZ)
        var bd = maxD * maxD
        var found = false
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val ix = cx + dx; val iy = cy + dy; val iz = cz + dz
            if (ix < 0 || iy < 0 || iz < 0) continue
            val id = map[cellKey(ix, iy, iz)]
            if (id < 0) continue
            for (s in start[id] until start[id + 1]) {
                val j = items[s]
                val ex = pts[j * 3] - x; val ey = pts[j * 3 + 1] - y; val ez = pts[j * 3 + 2] - z
                val d = ex * ex + ey * ey + ez * ez
                if (d > bd) continue
                val mx = normals[j * 3]; val my = normals[j * 3 + 1]; val mz = normals[j * 3 + 2]
                if ((mx != 0f || my != 0f || mz != 0f) && abs(mx * nx + my * ny + mz * nz) < cosMin) continue
                bd = d; found = true
            }
        }
        return if (found) bd else -1f
    }

    /** Indices of the points within sqrt([r2]) of the query (needs sqrt(r2) <= cell), capped at [out].size. */
    fun within(x: Float, y: Float, z: Float, r2: Float, out: IntArray): Int {
        val cx = idx(x, minX); val cy = idx(y, minY); val cz = idx(z, minZ)
        var c = 0
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val ix = cx + dx; val iy = cy + dy; val iz = cz + dz
            if (ix < 0 || iy < 0 || iz < 0) continue
            val id = map[cellKey(ix, iy, iz)]
            if (id < 0) continue
            for (s in start[id] until start[id + 1]) {
                val j = items[s]
                val ex = pts[j * 3] - x; val ey = pts[j * 3 + 1] - y; val ez = pts[j * 3 + 2] - z
                if (ex * ex + ey * ey + ez * ez <= r2 && c < out.size) out[c++] = j
            }
        }
        return c
    }

    /**
     * Unit surface normals by PCA over the points within [radius] (zero vector where fewer than 6 neighbours exist), and the points
     * themselves with the depth noise taken out: a point whose neighbourhood is flat ([flatRatio] = smallest / middle eigenvalue below
     * it) is projected onto the neighbourhood's mean plane, edges and corners stay where they are.
     */
    fun analyze(radius: Float, denoise: Boolean, flatRatio: Float = 0.35f): Pair<FloatArray, FloatArray> {
        require(radius <= cell) { "normal radius larger than the grid cell" }
        val out = FloatArray(pts.size)
        val smooth = pts.copyOf()
        val buf = IntArray(256)
        val r2 = radius * radius
        for (i in 0 until n) {
            val c = within(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2], r2, buf)
            if (c < 6) continue
            var mx = 0.0; var my = 0.0; var mz = 0.0
            for (k in 0 until c) { mx += pts[buf[k] * 3]; my += pts[buf[k] * 3 + 1]; mz += pts[buf[k] * 3 + 2] }
            mx /= c; my /= c; mz /= c
            val cov = DoubleArray(9)
            for (k in 0 until c) {
                val dx = pts[buf[k] * 3] - mx; val dy = pts[buf[k] * 3 + 1] - my; val dz = pts[buf[k] * 3 + 2] - mz
                cov[0] += dx * dx; cov[1] += dx * dy; cov[2] += dx * dz; cov[4] += dy * dy; cov[5] += dy * dz; cov[8] += dz * dz
            }
            cov[3] = cov[1]; cov[6] = cov[2]; cov[7] = cov[5]
            val (vals, vecs) = Sym.eigen(cov, 3)
            var lo = 0
            for (k in 1..2) if (vals[k] < vals[lo]) lo = k
            out[i * 3] = vecs[3 * lo].toFloat(); out[i * 3 + 1] = vecs[1 + 3 * lo].toFloat(); out[i * 3 + 2] = vecs[2 + 3 * lo].toFloat()
            if (denoise) {
                val mid = vals.sorted()[1]
                if (mid > 1e-12 && vals[lo] < flatRatio * mid) {
                    val nx = vecs[3 * lo]; val ny = vecs[1 + 3 * lo]; val nz = vecs[2 + 3 * lo]
                    val d = (pts[i * 3] - mx) * nx + (pts[i * 3 + 1] - my) * ny + (pts[i * 3 + 2] - mz) * nz
                    smooth[i * 3] = (pts[i * 3] - d * nx).toFloat(); smooth[i * 3 + 1] = (pts[i * 3 + 1] - d * ny).toFloat(); smooth[i * 3 + 2] = (pts[i * 3 + 2] - d * nz).toFloat()
                }
            }
        }
        return out to smooth
    }
}

/** Cyclic Jacobi eigen decomposition of a symmetric matrix. */
internal object Sym {
    /** Returns eigenvalues and eigenvectors; vector k is column k: element i at index i + n * k. */
    fun eigen(a0: DoubleArray, n: Int): Pair<DoubleArray, DoubleArray> {
        val a = a0.copyOf()
        val v = DoubleArray(n * n); for (i in 0 until n) v[i + n * i] = 1.0
        for (sweep in 0 until 60) {
            var off = 0.0
            for (i in 0 until n) for (j in i + 1 until n) off += a[i * n + j] * a[i * n + j]
            if (off < 1e-24) break
            for (p in 0 until n) for (q in p + 1 until n) {
                val apq = a[p * n + q]
                if (abs(apq) < 1e-300) continue
                val theta = (a[q * n + q] - a[p * n + p]) / (2 * apq)
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until n) {
                    val akp = a[k * n + p]; val akq = a[k * n + q]
                    a[k * n + p] = c * akp - s * akq; a[k * n + q] = s * akp + c * akq
                }
                for (k in 0 until n) {
                    val apk = a[p * n + k]; val aqk = a[q * n + k]
                    a[p * n + k] = c * apk - s * aqk; a[q * n + k] = s * apk + c * aqk
                }
                for (k in 0 until n) {
                    val vkp = v[k + n * p]; val vkq = v[k + n * q]
                    v[k + n * p] = c * vkp - s * vkq; v[k + n * q] = s * vkp + c * vkq
                }
            }
        }
        return DoubleArray(n) { a[it * n + it] } to v
    }
}

internal class KcResult(
    /** Centred-frame transform helper -> leader. */
    val t: Rigid,
    val fitness: Double,
    val rmse: Double,
    val inliers: Int,
    /** Smallest / largest curvature of the cost over (yaw at the object's lever arm, shift u, shift v): ~0 = a direction the overlap does not pin. */
    val curvatureRatio: Double,
)

/**
 * Kernel-correlation refinement of a candidate pose, in coordinates centred on the pivot (the object axis).
 *
 * The up vectors and support planes already fix tilt and height, so the free parameters are the yaw about the pivot and the two
 * horizontal shifts (plus the height when no planes were given). The cost is the sum over the helper points of 1 - exp(-d^2 / 2 s^2),
 * d = distance to the nearest leader point (1 when there is none within 3 s): robust, bounded, smooth, and it uses edges and corners
 * (a box outline fixes its yaw) where point-to-plane methods only see flat faces. A Gaussian prior on the shifts (the stated axis
 * placement accuracy) keeps directions the overlap does not pin at their constraint-based value. Pattern search with halving steps.
 */
internal class KcRefiner(
    leader: FloatArray, helper: FloatArray, private val o: RegistrationOptions, private val up: DoubleArray,
    private val freeHeight: Boolean, private val shiftSigma: Double,
) {
    private val s = o.kernelSigma.toDouble()
    private val reach = (3 * s).toFloat()
    private val cell = max(max(reach, o.inlierDist), o.normalRadius)
    private val leaderA = NnGrid(leader, cell).analyze(o.normalRadius, o.denoise)
    private val leaderN = leaderA.first
    private val leaderS = leaderA.second
    private val grid = NnGrid(leaderS, cell)

    // the cost is symmetric (helper -> leader and leader -> helper): counting only the helper's points would reward sliding the helper
    // inwards, onto the part of the leader it happens to overlap, since nothing is charged for the leader surface it leaves uncovered
    private val helperA = NnGrid(helper, cell).analyze(o.normalRadius, o.denoise)
    private val helperN = helperA.first
    private val helperS = helperA.second
    private val helperGrid = NnGrid(helperS, cell)
    private val cosMin = o.normalCosMin

    private class Sample(val p: FloatArray, val n: FloatArray)

    private fun sampleOf(p: FloatArray, nrm: FloatArray, maxN: Int): Sample {
        val n = p.size / 3
        if (n <= maxN) return Sample(p, nrm)
        val sp = FloatArray(maxN * 3); val sn = FloatArray(maxN * 3)
        for (i in 0 until maxN) {
            val k = (i.toLong() * n / maxN).toInt()
            for (a in 0..2) { sp[i * 3 + a] = p[k * 3 + a]; sn[i * 3 + a] = nrm[k * 3 + a] }
        }
        return Sample(sp, sn)
    }

    private val leaderFit = sampleOf(leaderS, leaderN, o.maxSourceSamples)
    private val fit = sampleOf(helperS, helperN, o.maxSourceSamples)
    private val leaderFine = sampleOf(leaderS, leaderN, o.polishSamples)
    private val fineFit = sampleOf(helperS, helperN, o.polishSamples)
    private val eval = sampleOf(helperS, helperN, 8000)
    private val e1: DoubleArray
    private val e2: DoubleArray
    private val extent: Double

    init {
        val ref = if (abs(up[1]) < 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val c = doubleArrayOf(up[1] * ref[2] - up[2] * ref[1], up[2] * ref[0] - up[0] * ref[2], up[0] * ref[1] - up[1] * ref[0])
        val l = sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2])
        e1 = doubleArrayOf(c[0] / l, c[1] / l, c[2] / l)
        e2 = doubleArrayOf(up[1] * e1[2] - up[2] * e1[1], up[2] * e1[0] - up[0] * e1[2], up[0] * e1[1] - up[1] * e1[0])
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = x0; var y1 = x1; var z0 = x0; var z1 = x1
        for (i in 0 until leader.size / 3) {
            x0 = min(x0, leader[i * 3]); x1 = max(x1, leader[i * 3]); y0 = min(y0, leader[i * 3 + 1]); y1 = max(y1, leader[i * 3 + 1]); z0 = min(z0, leader[i * 3 + 2]); z1 = max(z1, leader[i * 3 + 2])
        }
        extent = sqrt(((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0)).toDouble()).coerceAtLeast(0.05)
    }

    /** Squared distance from sample point [i] moved by [t] to the nearest compatible point of [g] (normals [gn]), -1 when none within [maxD]. */
    private fun probe(t: Rigid, smp: Sample, i: Int, g: NnGrid, gn: FloatArray, maxD: Float): Float {
        val px = smp.p[i * 3].toDouble(); val py = smp.p[i * 3 + 1].toDouble(); val pz = smp.p[i * 3 + 2].toDouble()
        val sx = smp.n[i * 3].toDouble(); val sy = smp.n[i * 3 + 1].toDouble(); val sz = smp.n[i * 3 + 2].toDouble()
        return g.nearestSq(
            t.applyX(px, py, pz).toFloat(), t.applyY(px, py, pz).toFloat(), t.applyZ(px, py, pz).toFloat(), maxD, gn,
            (t.r[0] * sx + t.r[1] * sy + t.r[2] * sz).toFloat(), (t.r[3] * sx + t.r[4] * sy + t.r[5] * sz).toFloat(), (t.r[6] * sx + t.r[7] * sy + t.r[8] * sz).toFloat(),
            cosMin,
        )
    }

    /** Correction in the leader frame, about the pivot (the origin of the centred frame): yaw [dth] about up, then shifts. */
    private fun delta(x: DoubleArray): Rigid {
        val dh = if (freeHeight) x[3] else 0.0
        return Rigid.rotation(up, x[0], doubleArrayOf(x[1] * e1[0] + x[2] * e2[0] + dh * up[0], x[1] * e1[1] + x[2] * e2[1] + dh * up[1], x[1] * e1[2] + x[2] * e2[2] + dh * up[2]))
    }

    private fun cost(init: Rigid, x: DoubleArray, fine: Boolean = false, withPrior: Boolean = true): Double {
        val t = delta(x).mul(init)
        val two = 2 * s * s
        var c = 0.0
        val hs = if (fine) fineFit else fit
        val ls = if (fine) leaderFine else leaderFit
        for (i in 0 until hs.p.size / 3) {
            val d2 = probe(t, hs, i, grid, leaderN, reach)
            c += if (d2 < 0f) 1.0 else 1.0 - exp(-d2 / two)
        }
        val inv = t.inverse()
        for (i in 0 until ls.p.size / 3) {
            val d2 = probe(inv, ls, i, helperGrid, helperN, reach)
            c += if (d2 < 0f) 1.0 else 1.0 - exp(-d2 / two)
        }
        // Gaussian prior on the shifts around the constraint-based pose, weighted by the number of points so it stays comparable with the
        // data term: where the overlap only holds parallel faces (a cube's halves meeting along x) every slide matches equally well, the
        // one that stacks the halves on top of each other matches best, and only this prior says that the axis is where it was placed
        if (!withPrior) return c
        val w = 0.1 * (hs.p.size / 3 + ls.p.size / 3)
        var p = w * 0.5 * (x[1] * x[1] + x[2] * x[2]) / (shiftSigma * shiftSigma) + (if (freeHeight) w * 0.5 * x[3] * x[3] / (shiftSigma * shiftSigma) else 0.0)
        // a direction the overlap cannot pin (see [pinning]) is held at the constraint-based value, to a millimetre
        weak?.let { d -> val s1 = x[1] * d[0] + x[2] * d[1]; p += w * 0.5 * s1 * s1 / (0.001 * 0.001) }
        return c + p
    }

    @Volatile private var weak: DoubleArray? = null

    /**
     * Data-only curvature of the cost at the start pose along the two horizontal shifts (2 x 2 Hessian, principal directions) and along
     * the yaw at the object's lever arm. Returns the horizontal direction the overlap does NOT pin (null when both are pinned) and the
     * ratio of the weakest curvature to the strongest one.
     */
    private fun pinning(init: Rigid, fine: Boolean): Pair<DoubleArray?, Double> {
        val h = 0.003
        fun f(u: Double, v: Double, th: Double = 0.0) = cost(init, doubleArrayOf(th, u, v, 0.0), fine, withPrior = false)
        val f0 = f(0.0, 0.0)
        val huu = (f(h, 0.0) + f(-h, 0.0) - 2 * f0) / (h * h)
        val hvv = (f(0.0, h) + f(0.0, -h) - 2 * f0) / (h * h)
        val huv = (f(h, h) + f(-h, -h) - f(h, 0.0) - f(-h, 0.0) - f(0.0, h) - f(0.0, -h) + 2 * f0) / (2 * h * h)
        val tr = huu + hvv; val det = huu * hvv - huv * huv
        val disc = sqrt(max(0.0, tr * tr / 4 - det))
        val l1 = tr / 2 + disc; val l2 = tr / 2 - disc            // l1 >= l2
        val thetaStep = Math.toRadians(2.0); val lever = extent / 2
        val hth = (f(0.0, 0.0, thetaStep) + f(0.0, 0.0, -thetaStep) - 2 * f0) / ((thetaStep * lever) * (thetaStep * lever))
        val ref = max(max(l1, hth), 1e-9)
        val ratio = (min(l2, hth).coerceAtLeast(0.0)) / ref
        if (l2 >= o.underconstrainedRatio * ref) return null to ratio
        // eigenvector of l2
        val ex: Double; val ey: Double
        if (abs(huv) > 1e-12) { ex = l2 - hvv; ey = huv } else if (huu < hvv) { ex = 1.0; ey = 0.0 } else { ex = 0.0; ey = 1.0 }
        val n = sqrt(ex * ex + ey * ey)
        return doubleArrayOf(ex / n, ey / n) to ratio
    }

    /**
     * Pattern search over (yaw, shift u, shift v[, height]) starting from [init]; [fine] switches to the large point sample (the polish
     * of the winning candidate), whose smaller start steps keep it from wandering off.
     */
    fun refine(init: Rigid, fine: Boolean = false): KcResult {
        val dims = if (freeHeight) 4 else 3
        val x = DoubleArray(4)
        val (weakDir, pinRatio) = pinning(init, fine)
        weak = weakDir
        val limitTheta = Math.toRadians(o.icpMaxDriftDeg.toDouble())
        val limitShift = o.maxShiftM.toDouble()
        var stepTheta = Math.toRadians(if (fine) 0.4 else 1.0); var stepShift = if (fine) 0.001 else 0.002
        val minTheta = Math.toRadians(0.01); val minShift = 0.00005
        var best = cost(init, x, fine)
        var evals = 1
        while ((stepTheta > minTheta || stepShift > minShift) && evals < 1500) {
            var improved = false
            for (k in 0 until dims) for (sign in intArrayOf(1, -1)) {
                val h = if (k == 0) stepTheta else stepShift
                val old = x[k]
                x[k] = old + sign * h
                val within = if (k == 0) abs(x[0]) <= limitTheta else abs(x[k]) <= limitShift
                val c = if (within) cost(init, x, fine) else Double.MAX_VALUE
                evals++
                if (c < best - 1e-9) { best = c; improved = true } else x[k] = old
            }
            if (!improved) { stepTheta *= 0.5; stepShift *= 0.5 }
        }
        val t = delta(x).mul(init)

        // fitness over a larger sample: share of helper points that have a leader point within the inlier distance
        var inl = 0; var sse = 0.0
        val m = eval.p.size / 3
        for (i in 0 until m) {
            val d2 = probe(t, eval, i, grid, leaderN, o.inlierDist)
            if (d2 >= 0f) { inl++; sse += d2 }
        }
        val fitness = inl.toDouble() / m
        val rmse = if (inl > 0) sqrt(sse / inl) else Double.NaN

        weak = null
        CloudRegistration.trace?.invoke("refine fine=$fine weak=${weakDir?.toList()} pinRatio=%.4f x=%s evals=$evals".format(pinRatio, x.map { "%.5f".format(it) }))
        return KcResult(t, fitness, rmse, inl, pinRatio)
    }
}
