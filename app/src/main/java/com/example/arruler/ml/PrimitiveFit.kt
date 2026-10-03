package com.example.arruler.ml

import com.example.arruler.depth.ConvexHull
import com.example.arruler.geometry.Vec2
import com.example.arruler.objscan.ObjectMeasures
import com.example.arruler.objscan.SupportPlane
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What an isolated object looks like. [UNKNOWN] = no primitive explains the points. */
enum class ShapeLabel { BOX, CYLINDER, SPHERE, CONE, UNKNOWN }

/** Fitted parameters; every length in meters, [cx]/[cz] world horizontal centre, heights measured from the support plane. */
sealed interface ShapeParams

/** [w] >= [d]; [yaw] in the [com.example.arruler.objscan.ObjectBox] convention (rotation about +Y, rad). */
data class BoxParams(val w: Double, val d: Double, val h: Double, val yaw: Double, val cx: Double, val cz: Double) : ShapeParams

data class CylinderParams(val r: Double, val h: Double, val cx: Double, val cz: Double) : ShapeParams

/** [cy] = height of the sphere centre above the plane (a ball resting on it has cy = r). */
data class SphereParams(val r: Double, val cx: Double, val cy: Double, val cz: Double) : ShapeParams

/** Cone or frustum on its base: radius [rBase] at the plane, [rTop] (0 for a true cone) at height [h]. */
data class ConeParams(val rBase: Double, val rTop: Double, val h: Double, val cx: Double, val cz: Double) : ShapeParams

/** One primitive fitted to the points. [nrms] = rms point-to-surface distance / [PrimitiveFits.size] (dimensionless). */
class PrimitiveFit1(
    val label: ShapeLabel,
    val params: ShapeParams,
    /** RMS distance of the points to the primitive's surface (underside excluded), m. */
    val rms: Double,
    /** [rms] / object size (dimensionless). */
    val nrms: Double,
    /** nrms + parsimony penalty (dimensionless, lower = better). */
    val score: Double,
    /** Formula volume, m^3. */
    val volume: Double,
)

/** All four fits of one object plus shared geometry. */
class PrimitiveFits(
    val fits: Map<ShapeLabel, PrimitiveFit1>,
    /** Object size = max(footprint length, height), m. */
    val size: Double,
    val pointCount: Int,
    /** Footprint rectangle: long side [rectLength], short side [rectWidth] (m). */
    val rectLength: Double,
    val rectWidth: Double,
    /** Robust top height (99.5th percentile of height above the plane), m. */
    val heightMax: Double,
    /** Convex hull perimeter / area of the footprint (m, m^2). */
    val hullPerimeter: Double,
    val hullArea: Double,
    /** Area of the convex hull of the points in the top 20 % of the height, and of the lowest 25 %; m^2. */
    val topArea: Double,
    val bottomArea: Double,
    /** Radius slope of the cone fit, d(radius)/d(height) (dimensionless). */
    val coneSlope: Double,
) {
    val best: PrimitiveFit1 get() = fits.values.minByOrNull { it.score }!!
}

/** Result of [PrimitiveFit.fit]: softmax over the fit scores (no learning). */
class PrimitiveFitResult(
    val fits: PrimitiveFits,
    /** Best label by fit score, or [ShapeLabel.UNKNOWN] when the best fit is worse than the threshold. */
    val label: ShapeLabel,
    /** Softmax(-score / [PrimitiveFit.TEMPERATURE]) of the best fit, 0..1. */
    val confidence: Double,
    /** (rms_best - [PrimitiveFit.NOISE_FLOOR]) / size of the best fit, dimensionless. */
    val excess: Double,
)

/**
 * Least-squares primitive fits to isolated object points (pure Kotlin).
 *
 * Frame: the support plane is horizontal (normal +Y, as for [com.example.arruler.objscan.ObjectBox]); a point is
 * (x, z) world horizontal + height h = plane.signedDistance. The underside is never visible, so no model is
 * charged for it: distances go to the lateral surface and the top face only.
 *
 *  - BOX: min-area rectangle of the footprint hull, sides re-placed at the 0.25 % / 99.75 % quantiles
 *    ([ObjectMeasures.refineRect]), height = [topHeight]. Distance = distance to the 5 faces (box open downwards).
 *  - CYLINDER: vertical axis. Kasa algebraic circle fit on the wall points (top face excluded: points within
 *    [TOP_BAND] of the top), then Gauss-Newton on the geometric (radial) residual. Distance in the meridian plane
 *    (rho, h) to the wall segment and the top disc.
 *  - SPHERE: algebraic (linear) least squares on x^2+y^2+z^2+Dx+Ey+Fz+G=0, then Gauss-Newton on |p-c|-r.
 *  - CONE: vertical axis, radius a + b*h: Gauss-Newton on (cx, cz, a, b) from the Kasa circle of the lowest wall
 *    points. Apex height -a/b when it is within 12 % of the top, else a frustum with a flat top.
 *    A revolved surface's nearest point lies in the point's own meridian half-plane, so the distance is the exact
 *    2D distance to the profile polyline.
 *
 * Score = nrms + [PARSIMONY] * k with k = number of size parameters (box 3, cylinder 2, sphere 1, cone 3): when two
 * models explain the points equally well the simpler one wins.
 */
object PrimitiveFit {
    /** Points within this height of the top are the top face (m). */
    const val TOP_BAND = 0.015

    /** Penalty per size parameter, in normalised-rms units (dimensionless). */
    const val PARSIMONY = 0.002

    /** Softmax temperature on scores (dimensionless): one noise floor of a 20 cm object (4 mm / 200 mm = 0.02), halved. */
    const val TEMPERATURE = 0.01

    /** Typical residual rms of denoised ARCore points (m); subtracted before the unknown test so small objects are not rejected for noise. */
    const val NOISE_FLOOR = 0.005

    /**
     * UNKNOWN when (rms_best - NOISE_FLOOR) / size exceeds this (dimensionless). Tuned on the tests: correct
     * primitives at 2-6 mm noise stay below ~0.02, three-sphere blobs sit well above 0.05.
     */
    const val UNKNOWN_EXCESS = 0.04

    private const val MIN_POINTS = 30

    /** Fits all four primitives; null when there are fewer than 30 points above the plane. */
    fun fitAll(points: FloatArray, plane: SupportPlane): PrimitiveFits? {
        val nAll = points.size / 3
        val xs = DoubleArray(nAll); val zs = DoubleArray(nAll); val hs = DoubleArray(nAll)
        var n = 0
        for (i in 0 until nAll) {
            val h = plane.signedDistance(points[i * 3], points[i * 3 + 1], points[i * 3 + 2])
            if (h > 0f) { xs[n] = points[i * 3].toDouble(); zs[n] = points[i * 3 + 2].toDouble(); hs[n] = h.toDouble(); n++ }
        }
        if (n < MIN_POINTS) return null
        return fitLocal(xs.copyOf(n), zs.copyOf(n), hs.copyOf(n))
    }

    /** Same as [fitAll] on horizontal coordinates x, z and heights h (all m). */
    fun fitLocal(x: DoubleArray, z: DoubleArray, h: DoubleArray): PrimitiveFits? {
        val n = x.size
        if (n < MIN_POINTS) return null
        val hull = ConvexHull.of(List(n) { Vec2(x[it].toFloat(), z[it].toFloat()) })
        if (hull.size < 3) return null
        val lx = FloatArray(n) { x[it].toFloat() }; val lz = FloatArray(n) { z[it].toFloat() }
        val rect = ObjectMeasures.refineRect(ObjectMeasures.minAreaRect(hull), lx, lz, n, 0.0025f)
        val hMax = percentile(h, 0.995)
        val size = max(rect.length.toDouble(), hMax)
        val topH = topHeight(h)

        // wall points: not on the top face
        val wall = IntArray(n); var nw = 0
        for (i in 0 until n) if (h[i] < hMax - TOP_BAND) wall[nw++] = i
        val useAll = nw < 20
        val widx = if (useAll) IntArray(n) { it } else wall.copyOf(nw)

        val fits = LinkedHashMap<ShapeLabel, PrimitiveFit1>()

        // BOX
        run {
            val a0 = rect.angle.toDouble()
            val ca = cos(a0); val sa = sin(a0)
            val pu = DoubleArray(n) { x[it] * ca + z[it] * sa }
            val pv = DoubleArray(n) { -x[it] * sa + z[it] * ca }
            val u0 = faceEstimate(pu, false); val u1 = faceEstimate(pu, true)
            val v0 = faceEstimate(pv, false); val v1 = faceEstimate(pv, true)
            var l = u1 - u0; var wd = v1 - v0
            var a = a0
            if (wd > l) { val t = l; l = wd; wd = t; a += PI / 2 }
            val cu = (u0 + u1) / 2; val cv = (v0 + v1) / 2
            val cx = cu * ca - cv * sa; val cz = cu * sa + cv * ca
            val ca2 = cos(a); val sa2 = sin(a)
            val hu = l / 2.0; val hv = wd / 2.0
            var ss = 0.0
            for (i in 0 until n) {
                val dx = x[i] - cx; val dz = z[i] - cz
                val u = dx * ca2 + dz * sa2; val v = -dx * sa2 + dz * ca2
                val d = boxDistance(abs(u) - hu, abs(v) - hv, h[i] - topH)
                ss += d * d
            }
            val p = BoxParams(l, wd, topH, -a, cx, cz)
            fits[ShapeLabel.BOX] = make(ShapeLabel.BOX, p, sqrt(ss / n), size, 3, l * wd * topH)
        }

        // CYLINDER
        run {
            val c = kasaCircle(x, z, widx, widx.size)
            val g = refineRadial(x, z, h, widx, doubleArrayOf(c[0], c[1], c[2], 0.0), fixB = true)
            val r = g[2]
            var ss = 0.0
            for (i in 0 until n) {
                val rho = hypot(x[i] - g[0], z[i] - g[1])
                val d = profileDistance(rho, h[i], r, topH, r)
                ss += d * d
            }
            val p = CylinderParams(r, topH, g[0], g[1])
            fits[ShapeLabel.CYLINDER] = make(ShapeLabel.CYLINDER, p, sqrt(ss / n), size, 2, PI * r * r * topH)
        }

        // SPHERE
        run {
            val s = fitSphere(x, z, h, n)
            var ss = 0.0
            for (i in 0 until n) {
                val d = sqrt(sq(x[i] - s[0]) + sq(h[i] - s[1]) + sq(z[i] - s[2])) - s[3]
                ss += d * d
            }
            val p = SphereParams(s[3], s[0], s[1], s[2])
            fits[ShapeLabel.SPHERE] = make(ShapeLabel.SPHERE, p, sqrt(ss / n), size, 1, 4.0 / 3.0 * PI * s[3] * s[3] * s[3])
        }

        // CONE (frustum)
        var slope = 0.0
        run {
            // init: Kasa circle of the lowest 35 % of the wall points
            val sorted = widx.sortedBy { h[it] }
            val low = sorted.take(max(10, (sorted.size * 0.35).toInt())).toIntArray()
            val c = kasaCircle(x, z, low, low.size)
            val a0 = regress(x, z, h, widx, c[0], c[1])
            val g = refineRadial(x, z, h, widx, doubleArrayOf(c[0], c[1], a0[0], a0[1]), fixB = false)
            val a = g[2]; val b = g[3]
            slope = b
            var hh: Double
            var rTop: Double
            val apex = if (b < -1e-3) -a / b else Double.POSITIVE_INFINITY
            if (apex <= 1.12 * hMax) {
                hh = apex.coerceIn(0.95 * hMax, 1.12 * hMax); rTop = 0.0
            } else {
                hh = topH; rTop = max(0.0, a + b * hh)
            }
            val rBase = max(a, 1e-4)
            var ss = 0.0
            for (i in 0 until n) {
                val rho = hypot(x[i] - g[0], z[i] - g[1])
                val d = profileDistance(rho, h[i], rBase, hh, rTop)
                ss += d * d
            }
            val p = ConeParams(rBase, rTop, hh, g[0], g[1])
            val vol = PI * hh / 3.0 * (rBase * rBase + rBase * rTop + rTop * rTop)
            fits[ShapeLabel.CONE] = make(ShapeLabel.CONE, p, sqrt(ss / n), size, 3, vol)
        }

        // footprint / slab areas for the classifier
        val hullPts = hull
        var per = 0.0
        for (i in hullPts.indices) per += hullPts[i].distanceTo(hullPts[(i + 1) % hullPts.size]).toDouble()
        val topArea = slabHullArea(x, z, h, hMax * 0.8, Double.MAX_VALUE)
        val botArea = slabHullArea(x, z, h, 0.0, hMax * 0.25)
        return PrimitiveFits(
            fits, size, n, rect.length.toDouble(), rect.width.toDouble(), hMax, per, polygonArea(hullPts),
            topArea, botArea, slope,
        )
    }

    /** Softmax label of the fit scores with the unknown gate; no learning involved. */
    fun fit(points: FloatArray, plane: SupportPlane): PrimitiveFitResult? =
        fitAll(points, plane)?.let { decide(it) }

    fun decide(f: PrimitiveFits): PrimitiveFitResult {
        val best = f.best
        val minS = best.score
        var z = 0.0
        for (fit in f.fits.values) z += exp(-(fit.score - minS) / TEMPERATURE)
        val conf = 1.0 / z
        val excess = (best.rms - NOISE_FLOOR) / f.size
        return PrimitiveFitResult(f, if (excess > UNKNOWN_EXCESS) ShapeLabel.UNKNOWN else best.label, conf, excess)
    }

    // ------------------------------------------------------------------ helpers

    private fun make(l: ShapeLabel, p: ShapeParams, rms: Double, size: Double, k: Int, vol: Double): PrimitiveFit1 {
        val nrms = rms / size
        return PrimitiveFit1(l, p, rms, nrms, nrms + PARSIMONY * k, vol)
    }

    private fun sq(v: Double) = v * v
    private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)

    internal fun percentile(v: DoubleArray, q: Double): Double {
        val s = v.copyOf(); s.sort()
        return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)]
    }

    internal fun topHeight(h: DoubleArray): Double = faceEstimate(h, true)

    /**
     * Position of a flat face from the extreme of the values [v] (heights, or points projected on a box axis).
     * The 0.5 % / 99.5 % quantile of a noisy flat face is biased by ~2 sigma outwards, so take the 2 mm histogram
     * mode of the values within 1 cm outside / 3 cm inside that quantile (the true face holds far more points per
     * bin than the uniformly spread adjacent faces) and average the values within 5 mm of the mode.
     */
    internal fun faceEstimate(v: DoubleArray, high: Boolean): Double {
        val q = percentile(v, if (high) 0.995 else 0.005)
        val lo = if (high) q - 0.03 else q - 0.01
        val hi = lo + 0.04
        val bins = IntArray(20)
        for (x in v) if (x >= lo && x < hi) bins[((x - lo) / 0.002).toInt().coerceIn(0, 19)]++
        var bi = 0; var bs = -1
        for (i in 0 until 20) {
            val s = bins[i] + (if (i > 0) bins[i - 1] else 0) + (if (i < 19) bins[i + 1] else 0)
            if (s > bs) { bs = s; bi = i }
        }
        val mode = lo + (bi + 0.5) * 0.002
        var s = 0.0; var c = 0
        for (x in v) if (abs(x - mode) <= 0.005) { s += x; c++ }
        return if (c > 0) s / c else q
    }

    /** Distance to a box (x/y/z excesses over the half extents; open downwards, so q.z is only the top excess). */
    private fun boxDistance(qx: Double, qy: Double, qz: Double): Double {
        val ox = max(qx, 0.0); val oy = max(qy, 0.0); val oz = max(qz, 0.0)
        return if (ox > 0.0 || oy > 0.0 || oz > 0.0) sqrt(ox * ox + oy * oy + oz * oz) else -max(qx, max(qy, qz))
    }

    /**
     * Distance in the meridian half-plane from (rho, h) to the profile (rBase, 0) -> (rTop, hh) -> (0, hh)
     * (lateral surface, then top disc; no bottom).
     */
    private fun profileDistance(rho: Double, h: Double, rBase: Double, hh: Double, rTop: Double): Double {
        val d1 = segDist(rho, h, rBase, 0.0, rTop, hh)
        val d2 = if (rTop > 1e-9) segDist(rho, h, rTop, hh, 0.0, hh) else Double.MAX_VALUE
        return min(d1, d2)
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-18) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Kasa algebraic circle fit on points idx[0 until m]: returns (cx, cz, r). */
    internal fun kasaCircle(x: DoubleArray, z: DoubleArray, idx: IntArray, m: Int): DoubleArray {
        var mx = 0.0; var mz = 0.0
        for (q in 0 until m) { mx += x[idx[q]]; mz += z[idx[q]] }
        mx /= m; mz /= m
        var sxx = 0.0; var sxz = 0.0; var szz = 0.0; var sx = 0.0; var sz = 0.0
        var bx = 0.0; var bz = 0.0; var b1 = 0.0
        for (q in 0 until m) {
            val u = x[idx[q]] - mx; val v = z[idx[q]] - mz; val w = u * u + v * v
            sxx += u * u; sxz += u * v; szz += v * v; sx += u; sz += v
            bx -= u * w; bz -= v * w; b1 -= w
        }
        val sol = solve(3, doubleArrayOf(sxx, sxz, sx, sxz, szz, sz, sx, sz, m.toDouble()), doubleArrayOf(bx, bz, b1))
            ?: return doubleArrayOf(mx, mz, 0.05)
        val cu = -sol[0] / 2; val cv = -sol[1] / 2
        val r2 = cu * cu + cv * cv - sol[2]
        return doubleArrayOf(mx + cu, mz + cv, sqrt(max(r2, 1e-8)))
    }

    /** Least squares rho = a + b*h about a fixed axis; returns (a, b). */
    private fun regress(x: DoubleArray, z: DoubleArray, h: DoubleArray, idx: IntArray, cx: Double, cz: Double): DoubleArray {
        var sh = 0.0; var sr = 0.0; var shh = 0.0; var shr = 0.0
        val m = idx.size
        for (i in idx) {
            val rho = hypot(x[i] - cx, z[i] - cz)
            sh += h[i]; sr += rho; shh += h[i] * h[i]; shr += h[i] * rho
        }
        val den = m * shh - sh * sh
        val b = if (abs(den) < 1e-12) 0.0 else (m * shr - sh * sr) / den
        return doubleArrayOf((sr - b * sh) / m, b)
    }

    /**
     * Gauss-Newton on the radial residual rho_i - (a + b h_i), parameters (cx, cz, a, b); [fixB] pins b = 0 (cylinder).
     * Levenberg damping of 1e-9 * trace keeps a degenerate (partial-arc) system solvable. [p0] = (cx, cz, a, b).
     */
    private fun refineRadial(x: DoubleArray, z: DoubleArray, h: DoubleArray, idx: IntArray, p0: DoubleArray, fixB: Boolean): DoubleArray {
        val p = p0.copyOf()
        if (fixB) p[3] = 0.0
        repeat(25) {
            val jtj = DoubleArray(16); val jtr = DoubleArray(4)
            for (i in idx) {
                val dx = x[i] - p[0]; val dz = z[i] - p[1]
                val rho = max(hypot(dx, dz), 1e-9)
                val res = rho - (p[2] + p[3] * h[i])
                val j = doubleArrayOf(-dx / rho, -dz / rho, -1.0, -h[i])
                for (a in 0 until 4) { jtr[a] += j[a] * res; for (b in 0 until 4) jtj[a * 4 + b] += j[a] * j[b] }
            }
            if (fixB) { jtj[15] = 1e12; jtr[3] = 0.0 }
            val tr = jtj[0] + jtj[5] + jtj[10]
            for (a in 0 until 3) jtj[a * 5] += 1e-9 * tr + 1e-12
            val d = solve(4, jtj, DoubleArray(4) { -jtr[it] }) ?: return p
            var mag = 0.0
            for (a in 0 until 4) { p[a] += d[a]; mag += d[a] * d[a] }
            if (mag < 1e-14) return p
        }
        return p
    }

    /** Sphere (cx, cy, cz, r) in (x, h, z) coordinates. */
    internal fun fitSphere(x: DoubleArray, z: DoubleArray, h: DoubleArray, n: Int): DoubleArray {
        var mx = 0.0; var my = 0.0; var mz = 0.0
        for (i in 0 until n) { mx += x[i]; my += h[i]; mz += z[i] }
        mx /= n; my /= n; mz /= n
        val a = DoubleArray(16); val b = DoubleArray(4)
        for (i in 0 until n) {
            val u = x[i] - mx; val v = h[i] - my; val w = z[i] - mz
            val row = doubleArrayOf(u, v, w, 1.0)
            val rhs = -(u * u + v * v + w * w)
            for (r in 0 until 4) { b[r] += row[r] * rhs; for (c in 0 until 4) a[r * 4 + c] += row[r] * row[c] }
        }
        val s = solve(4, a, b) ?: return doubleArrayOf(mx, my, mz, 0.05)
        val c = doubleArrayOf(mx - s[0] / 2, my - s[1] / 2, mz - s[2] / 2)
        val r2 = sq(s[0] / 2) + sq(s[1] / 2) + sq(s[2] / 2) - s[3]
        val p = doubleArrayOf(c[0], c[1], c[2], sqrt(max(r2, 1e-8)))
        repeat(15) {
            val jtj = DoubleArray(16); val jtr = DoubleArray(4)
            for (i in 0 until n) {
                val dx = x[i] - p[0]; val dy = h[i] - p[1]; val dz = z[i] - p[2]
                val d = max(sqrt(dx * dx + dy * dy + dz * dz), 1e-9)
                val res = d - p[3]
                val j = doubleArrayOf(-dx / d, -dy / d, -dz / d, -1.0)
                for (r in 0 until 4) { jtr[r] += j[r] * res; for (cc in 0 until 4) jtj[r * 4 + cc] += j[r] * j[cc] }
            }
            for (k in 0 until 4) jtj[k * 5] += 1e-12
            val d = solve(4, jtj, DoubleArray(4) { -jtr[it] }) ?: return p
            var mag = 0.0
            for (k in 0 until 4) { p[k] += d[k]; mag += d[k] * d[k] }
            if (mag < 1e-14) return p
        }
        return p
    }

    private fun slabHullArea(x: DoubleArray, z: DoubleArray, h: DoubleArray, lo: Double, hi: Double): Double {
        val pts = ArrayList<Vec2>()
        for (i in x.indices) if (h[i] >= lo && h[i] <= hi) pts.add(Vec2(x[i].toFloat(), z[i].toFloat()))
        if (pts.size < 3) return 0.0
        return polygonArea(ConvexHull.of(pts))
    }

    private fun polygonArea(p: List<Vec2>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x.toDouble() * b.y - b.x.toDouble() * a.y }
        return abs(s) / 2.0
    }

    /** Gaussian elimination with partial pivoting on the row-major n x n system; null when singular. */
    internal fun solve(n: Int, a0: DoubleArray, b0: DoubleArray): DoubleArray? {
        val a = a0.copyOf(); val b = b0.copyOf()
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(a[r * n + c]) > abs(a[piv * n + c])) piv = r
            if (abs(a[piv * n + c]) < 1e-18) return null
            if (piv != c) {
                for (k in 0 until n) { val t = a[c * n + k]; a[c * n + k] = a[piv * n + k]; a[piv * n + k] = t }
                val t = b[c]; b[c] = b[piv]; b[piv] = t
            }
            for (r in c + 1 until n) {
                val f = a[r * n + c] / a[c * n + c]
                for (k in c until n) a[r * n + k] -= f * a[c * n + k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r * n + k] * x[k]
            x[r] = s / a[r * n + r]
        }
        return x
    }
}
