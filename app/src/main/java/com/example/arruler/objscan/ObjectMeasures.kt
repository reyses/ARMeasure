package com.example.arruler.objscan

import com.example.arruler.depth.ConvexHull
import com.example.arruler.geometry.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Minimum-area rectangle around the footprint; [length] >= [width], [angle] = direction of the long side in the box frame (rad, atan2(z, x)). */
data class OrientedRect(
    val length: Float,
    val width: Float,
    val angle: Float,
    val centreX: Float,
    val centreZ: Float
) {
    val area: Float get() = length * width
}

/**
 * All measurements of an isolated object. Lengths in m, volumes in m^3.
 *
 *  - [extentW], [extentD]: axis-aligned extent along the box width / depth axes; [extentH] = maxHeight
 *  - [footprint]: min-area oriented rectangle of the footprint hull; [maxHeight]: max height above the support plane
 *  - [hullVolume]: convex hull volume (upper bound; exact for convex objects)
 *  - [occupancyVolume]: height-field volume (lower-ish bound for concave objects, see [ObjectMeasures])
 *  - [boxVolume]: axis-aligned bounding box in the box frame (W x D x H); [orientedBoxVolume]: footprint rect x height
 *  - [volumeLow] / [volumeHigh] / [volumeRecommended]: range and midpoint, as for PILE in ShapeCapture
 */
data class ObjectMeasurements(
    val pointCount: Int,
    val extentW: Float,
    val extentD: Float,
    val extentH: Float,
    val footprint: OrientedRect,
    val maxHeight: Float,
    val hullVolume: Float,
    val occupancyVolume: Float,
    val boxVolume: Float,
    val orientedBoxVolume: Float,
    val volumeLow: Float,
    val volumeHigh: Float,
    val volumeRecommended: Float
)

/**
 * Measurements from isolated points.
 *
 * Volume definitions and when to trust them:
 *  - convex hull (3D quickhull, expected O(n log n), worst case O(n^2); the footprint hull vertices projected onto the
 *    plane are added so the bottom slab removed by the support margin is included): trust for convex objects (boxes,
 *    cans, balls); overestimates anything concave (chairs, mugs with handles, L shapes).
 *  - occupancy (height-field): the footprint is gridded at [voxelSize]; every occupied column is filled from the
 *    support plane up to its highest point, volume = sum(cell area * column height). Exact for objects resting on a surface
 *    without overhangs; overestimates overhangs (table tops, mushrooms: the space under is counted as solid) and, by
 *    up to ~half a voxel per boundary cell, rim columns. It is never larger than the hull by more than that rim bias.
 *  - low = min(occupancy, hull), high = max(..), recommended = midpoint, mirroring PILE.
 *  - bounding boxes only bound.
 */
object ObjectMeasures {

    /**
     * [trim]: fraction of footprint points ignored at each end when the oriented rectangle's sides are placed (default 0.25 %).
     * The min-area rectangle gives the ORIENTATION; its sides are then re-placed at the [trim] / (1 - [trim]) quantiles of the
     * points projected on its axes. A flat face holds far more than 0.25 % of the points, so a face's quantile IS the face,
     * while the thin Gaussian tails at corners (which the local-plane denoise cannot flatten, ~4 mm at 4 mm noise) are cut.
     */
    fun measure(points: FloatArray, box: ObjectBox, plane: SupportPlane, voxelSize: Float, trim: Float = 0.0025f): ObjectMeasurements? {
        val n = points.size / 3
        if (n < 4) return null
        val lx = FloatArray(n); val lz = FloatArray(n); val hy = FloatArray(n)
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
        var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        var hMax = 0f
        for (i in 0 until n) {
            val l = box.toLocal(points[i * 3], points[i * 3 + 1], points[i * 3 + 2])
            lx[i] = l.x; lz[i] = l.z
            hy[i] = maxOf(0f, plane.signedDistance(points[i * 3], points[i * 3 + 1], points[i * 3 + 2]))
            x0 = minOf(x0, l.x); x1 = maxOf(x1, l.x); z0 = minOf(z0, l.z); z1 = maxOf(z1, l.z)
            hMax = maxOf(hMax, hy[i])
        }
        val extentW = x1 - x0; val extentD = z1 - z0

        val fp = ConvexHull.of(List(n) { Vec2(lx[it], lz[it]) })
        val rect = refineRect(minAreaRect(fp), lx, lz, n, trim)

        // convex hull in (x, height, z)
        val hp = ArrayList<Double>(n * 3 + fp.size * 3)
        val pts = DoubleArray((n + fp.size) * 3)
        for (i in 0 until n) { pts[i * 3] = lx[i].toDouble(); pts[i * 3 + 1] = hy[i].toDouble(); pts[i * 3 + 2] = lz[i].toDouble() }
        for ((j, v) in fp.withIndex()) {
            val o = (n + j) * 3
            pts[o] = v.x.toDouble(); pts[o + 1] = 0.0; pts[o + 2] = v.y.toDouble()
        }
        val hull = ConvexHull3D.volume(pts, n + fp.size).toFloat()

        // height-field occupancy
        val nx = ceil(extentW / voxelSize).toInt() + 1
        val nz = ceil(extentD / voxelSize).toInt() + 1
        val cols = FloatArray(nx * nz) { -1f }
        for (i in 0 until n) {
            val ix = ((lx[i] - x0) / voxelSize).toInt().coerceIn(0, nx - 1)
            val iz = ((lz[i] - z0) / voxelSize).toInt().coerceIn(0, nz - 1)
            val c = iz * nx + ix
            if (hy[i] > cols[c]) cols[c] = hy[i]
        }
        var occ = 0.0
        for (c in cols) if (c >= 0f) occ += c.toDouble() * voxelSize * voxelSize
        val occupancy = occ.toFloat()

        val lo = minOf(occupancy, hull); val hi = maxOf(occupancy, hull)
        return ObjectMeasurements(
            pointCount = n,
            extentW = extentW, extentD = extentD, extentH = hMax,
            footprint = rect, maxHeight = hMax,
            hullVolume = hull, occupancyVolume = occupancy,
            boxVolume = extentW * extentD * hMax,
            orientedBoxVolume = rect.area * hMax,
            volumeLow = lo, volumeHigh = hi, volumeRecommended = (lo + hi) / 2f
        )
    }

    internal fun refineRect(r: OrientedRect, lx: FloatArray, lz: FloatArray, n: Int, trim: Float): OrientedRect {
        if (trim <= 0f || n < 50 || r.width <= 0f) return r
        val ux = cos(r.angle); val uz = sin(r.angle)
        val pu = FloatArray(n) { lx[it] * ux + lz[it] * uz }
        val pv = FloatArray(n) { -lx[it] * uz + lz[it] * ux }
        pu.sort(); pv.sort()
        val k = (n * trim).toInt().coerceAtMost(n / 4)
        val u0 = pu[k]; val u1 = pu[n - 1 - k]; val v0 = pv[k]; val v1 = pv[n - 1 - k]
        val cu = (u0 + u1) / 2; val cv = (v0 + v1) / 2
        var l = u1 - u0; var w = v1 - v0
        var ang = r.angle
        if (w > l) { val t = l; l = w; w = t; ang += (PI / 2).toFloat() }
        return OrientedRect(l, w, ang, cu * ux - cv * uz, cu * uz + cv * ux)
    }

    /**
     * Minimum-area enclosing rectangle of a ccw convex polygon. The optimal rectangle has a side collinear with a hull
     * edge (rotating calipers theorem), so every edge direction is evaluated: O(h^2) for h hull vertices (h is tens
     * to a few hundred for a footprint, so this is microseconds; pointer-advancing calipers would make it O(h)).
     */
    fun minAreaRect(hull: List<Vec2>): OrientedRect {
        if (hull.isEmpty()) return OrientedRect(0f, 0f, 0f, 0f, 0f)
        if (hull.size < 3) {
            val a = hull.first(); val b = hull.last()
            return OrientedRect(a.distanceTo(b), 0f, atan2(b.y - a.y, b.x - a.x), (a.x + b.x) / 2, (a.y + b.y) / 2)
        }
        var bestArea = Double.MAX_VALUE
        var best = OrientedRect(0f, 0f, 0f, 0f, 0f)
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]
            val ex = (b.x - a.x).toDouble(); val ey = (b.y - a.y).toDouble()
            val len = Math.sqrt(ex * ex + ey * ey)
            if (len < 1e-9) continue
            val ux = ex / len; val uy = ey / len
            var u0 = Double.MAX_VALUE; var u1 = -Double.MAX_VALUE
            var v0 = Double.MAX_VALUE; var v1 = -Double.MAX_VALUE
            for (p in hull) {
                val u = p.x * ux + p.y * uy
                val v = -p.x * uy + p.y * ux
                if (u < u0) u0 = u; if (u > u1) u1 = u
                if (v < v0) v0 = v; if (v > v1) v1 = v
            }
            val area = (u1 - u0) * (v1 - v0)
            if (area < bestArea) {
                bestArea = area
                val cu = (u0 + u1) / 2; val cv = (v0 + v1) / 2
                val cx = cu * ux - cv * uy; val cy = cu * uy + cv * ux
                var l = u1 - u0; var w = v1 - v0
                var ang = atan2(uy, ux)
                if (w > l) { val t = l; l = w; w = t; ang += PI / 2 }
                while (ang > PI) ang -= 2 * PI
                while (ang <= -PI) ang += 2 * PI
                best = OrientedRect(l.toFloat(), w.toFloat(), ang.toFloat(), cx.toFloat(), cy.toFloat())
            }
        }
        return best
    }
}

/**
 * 3D convex hull by quickhull with per-face outside sets (double precision).
 * Complexity: expected O(n log n) for typical clouds, worst case O(n^2); each iteration additionally scans the alive
 * faces (O(F), F = faces of the final hull, a few hundred to a few thousand) to find the visible set.
 */
internal object ConvexHull3D {
    private class Face(val a: Int, val b: Int, val c: Int, val nx: Double, val ny: Double, val nz: Double, val off: Double) {
        var outside = IntArray(0)
        var count = 0
        var far = -1
        var farDist = 0.0
        var alive = true
        fun add(i: Int, d: Double) {
            if (count == outside.size) outside = outside.copyOf(maxOf(4, count * 2))
            outside[count++] = i
            if (d > farDist) { farDist = d; far = i }
        }
    }

    fun volume(p: DoubleArray, n: Int): Double {
        val faces = build(p, n) ?: return 0.0
        val (fs, ix, iy, iz) = faces
        var vol = 0.0
        for (f in fs) {
            val ax = p[f.a * 3] - ix; val ay = p[f.a * 3 + 1] - iy; val az = p[f.a * 3 + 2] - iz
            val bx = p[f.b * 3] - ix; val by = p[f.b * 3 + 1] - iy; val bz = p[f.b * 3 + 2] - iz
            val cx = p[f.c * 3] - ix; val cy = p[f.c * 3 + 1] - iy; val cz = p[f.c * 3 + 2] - iz
            vol += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)
        }
        return abs(vol) / 6.0
    }

    private data class Result(val faces: List<Face>, val ix: Double, val iy: Double, val iz: Double)

    private fun build(p: DoubleArray, n: Int): Result? {
        if (n < 4) return null
        // extremes
        val ext = IntArray(6)
        for (i in 1 until n) {
            for (a in 0..2) {
                if (p[i * 3 + a] < p[ext[a * 2] * 3 + a]) ext[a * 2] = i
                if (p[i * 3 + a] > p[ext[a * 2 + 1] * 3 + a]) ext[a * 2 + 1] = i
            }
        }
        val scale = maxOf(
            p[ext[1] * 3] - p[ext[0] * 3], p[ext[3] * 3 + 1] - p[ext[2] * 3 + 1], p[ext[5] * 3 + 2] - p[ext[4] * 3 + 2]
        )
        if (scale <= 0.0) return null
        val eps = 1e-7 * scale
        fun d2(i: Int, j: Int): Double {
            val x = p[i * 3] - p[j * 3]; val y = p[i * 3 + 1] - p[j * 3 + 1]; val z = p[i * 3 + 2] - p[j * 3 + 2]
            return x * x + y * y + z * z
        }
        var s0 = ext[0]; var s1 = ext[1]; var bd = -1.0
        for (i in ext) for (j in ext) { val d = d2(i, j); if (d > bd) { bd = d; s0 = i; s1 = j } }
        if (bd < eps * eps) return null
        // third: farthest from line s0-s1
        val lx = p[s1 * 3] - p[s0 * 3]; val ly = p[s1 * 3 + 1] - p[s0 * 3 + 1]; val lz = p[s1 * 3 + 2] - p[s0 * 3 + 2]
        var s2 = -1; var bl = -1.0
        for (i in 0 until n) {
            val x = p[i * 3] - p[s0 * 3]; val y = p[i * 3 + 1] - p[s0 * 3 + 1]; val z = p[i * 3 + 2] - p[s0 * 3 + 2]
            val cx = ly * z - lz * y; val cy = lz * x - lx * z; val cz = lx * y - ly * x
            val d = cx * cx + cy * cy + cz * cz
            if (d > bl) { bl = d; s2 = i }
        }
        if (Math.sqrt(bl) / Math.sqrt(bd) < eps) return null
        // fourth: farthest from plane s0 s1 s2
        val ux = p[s2 * 3] - p[s0 * 3]; val uy = p[s2 * 3 + 1] - p[s0 * 3 + 1]; val uz = p[s2 * 3 + 2] - p[s0 * 3 + 2]
        var nx = ly * uz - lz * uy; var ny = lz * ux - lx * uz; var nz = lx * uy - ly * ux
        val nl = Math.sqrt(nx * nx + ny * ny + nz * nz)
        nx /= nl; ny /= nl; nz /= nl
        var s3 = -1; var bp = -1.0
        for (i in 0 until n) {
            val d = abs((p[i * 3] - p[s0 * 3]) * nx + (p[i * 3 + 1] - p[s0 * 3 + 1]) * ny + (p[i * 3 + 2] - p[s0 * 3 + 2]) * nz)
            if (d > bp) { bp = d; s3 = i }
        }
        if (bp < eps) return null

        val ix = (p[s0 * 3] + p[s1 * 3] + p[s2 * 3] + p[s3 * 3]) / 4
        val iy = (p[s0 * 3 + 1] + p[s1 * 3 + 1] + p[s2 * 3 + 1] + p[s3 * 3 + 1]) / 4
        val iz = (p[s0 * 3 + 2] + p[s1 * 3 + 2] + p[s2 * 3 + 2] + p[s3 * 3 + 2]) / 4

        fun makeFace(a: Int, b: Int, c: Int, orientToOutsideOf: Boolean): Face {
            var aa = a; var bb = b; var cc = c
            fun mk(): Face {
                val e1x = p[bb * 3] - p[aa * 3]; val e1y = p[bb * 3 + 1] - p[aa * 3 + 1]; val e1z = p[bb * 3 + 2] - p[aa * 3 + 2]
                val e2x = p[cc * 3] - p[aa * 3]; val e2y = p[cc * 3 + 1] - p[aa * 3 + 1]; val e2z = p[cc * 3 + 2] - p[aa * 3 + 2]
                var fx = e1y * e2z - e1z * e2y; var fy = e1z * e2x - e1x * e2z; var fz = e1x * e2y - e1y * e2x
                val l = Math.sqrt(fx * fx + fy * fy + fz * fz)
                if (l > 0) { fx /= l; fy /= l; fz /= l }
                return Face(aa, bb, cc, fx, fy, fz, fx * p[aa * 3] + fy * p[aa * 3 + 1] + fz * p[aa * 3 + 2])
            }
            var f = mk()
            if (orientToOutsideOf && f.nx * ix + f.ny * iy + f.nz * iz - f.off > 0) { val t = bb; bb = cc; cc = t; f = mk() }
            return f
        }

        val alive = ArrayList<Face>()
        alive.add(makeFace(s0, s1, s2, true)); alive.add(makeFace(s0, s1, s3, true))
        alive.add(makeFace(s0, s2, s3, true)); alive.add(makeFace(s1, s2, s3, true))
        val simplex = setOf(s0, s1, s2, s3)
        fun assign(i: Int, fs: List<Face>) {
            for (f in fs) {
                val d = f.nx * p[i * 3] + f.ny * p[i * 3 + 1] + f.nz * p[i * 3 + 2] - f.off
                if (d > eps) { f.add(i, d); return }
            }
        }
        for (i in 0 until n) if (i !in simplex) assign(i, alive)

        var guard = 0
        while (guard++ < 200_000) {
            val f = alive.firstOrNull { it.count > 0 } ?: break
            val eye = f.far
            val ex = p[eye * 3]; val ey = p[eye * 3 + 1]; val ez = p[eye * 3 + 2]
            val visible = alive.filter { it.nx * ex + it.ny * ey + it.nz * ez - it.off > eps }
            val edges = HashSet<Long>(visible.size * 6)
            for (v in visible) {
                edges.add(v.a.toLong() * n + v.b); edges.add(v.b.toLong() * n + v.c); edges.add(v.c.toLong() * n + v.a)
            }
            val fresh = ArrayList<Face>()
            for (v in visible) {
                val es = arrayOf(v.a to v.b, v.b to v.c, v.c to v.a)
                for ((a, b) in es) if (!edges.contains(b.toLong() * n + a)) fresh.add(makeFace(a, b, eye, false))
            }
            for (v in visible) {
                v.alive = false
                for (q in 0 until v.count) { val i = v.outside[q]; if (i != eye) assign(i, fresh) }
            }
            alive.removeAll { !it.alive }
            alive.addAll(fresh)
        }
        return Result(alive, ix, iy, iz)
    }
}
