package com.example.arruler.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned bounding box in plane coordinates. */
data class Box2(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
}

/** Simple 2D polygon given by its vertices (implicitly closed; do not repeat the first point). */
class Polygon2(val points: List<Vec2>) {

    /** Shoelace area, positive for counter-clockwise winding, negative for clockwise. */
    fun signedArea(): Float {
        var s = 0.0
        val n = points.size
        for (i in 0 until n) {
            val a = points[i]; val b = points[(i + 1) % n]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return (s / 2.0).toFloat()
    }

    fun area(): Float = abs(signedArea())

    fun perimeter(): Float {
        var s = 0f
        val n = points.size
        if (n < 2) return 0f
        for (i in 0 until n) s += points[i].distanceTo(points[(i + 1) % n])
        return s
    }

    fun isClockwise(): Boolean = signedArea() < 0f

    /** Area-weighted centroid; falls back to the vertex average for zero-area input. */
    fun centroid(): Vec2 {
        require(points.isNotEmpty()) { "empty polygon" }
        val n = points.size
        var a2 = 0.0; var cx = 0.0; var cy = 0.0
        for (i in 0 until n) {
            val p = points[i]; val q = points[(i + 1) % n]
            val c = p.x.toDouble() * q.y - q.x.toDouble() * p.y
            a2 += c
            cx += (p.x + q.x) * c
            cy += (p.y + q.y) * c
        }
        if (abs(a2) < 1e-12) {
            return Vec2(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())
        }
        return Vec2((cx / (3.0 * a2)).toFloat(), (cy / (3.0 * a2)).toFloat())
    }

    fun boundingBox(): Box2 {
        require(points.isNotEmpty()) { "empty polygon" }
        var x0 = points[0].x; var x1 = x0; var y0 = points[0].y; var y1 = y0
        for (p in points) {
            x0 = min(x0, p.x); x1 = max(x1, p.x); y0 = min(y0, p.y); y1 = max(y1, p.y)
        }
        return Box2(x0, y0, x1, y1)
    }

    /**
     * True if no two non-adjacent edges intersect or touch (O(n^2)). Needs >= 3 vertices.
     */
    fun isSimple(): Boolean {
        val n = points.size
        if (n < 3) return false
        for (i in 0 until n) {
            val a = points[i]; val b = points[(i + 1) % n]
            for (j in i + 1 until n) {
                // skip edges sharing a vertex with edge i
                if (j == i + 1 || (i == 0 && j == n - 1)) continue
                val c = points[j]; val d = points[(j + 1) % n]
                if (segmentsIntersect(a, b, c, d)) return false
            }
        }
        return true
    }

    private fun orient(a: Vec2, b: Vec2, c: Vec2): Int {
        val v = (b.x.toDouble() - a.x) * (c.y - a.y) - (b.y.toDouble() - a.y) * (c.x - a.x)
        return if (v > 1e-12) 1 else if (v < -1e-12) -1 else 0
    }

    private fun onSegment(a: Vec2, b: Vec2, p: Vec2): Boolean =
        p.x >= min(a.x, b.x) && p.x <= max(a.x, b.x) && p.y >= min(a.y, b.y) && p.y <= max(a.y, b.y)

    private fun segmentsIntersect(a: Vec2, b: Vec2, c: Vec2, d: Vec2): Boolean {
        val o1 = orient(a, b, c); val o2 = orient(a, b, d)
        val o3 = orient(c, d, a); val o4 = orient(c, d, b)
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(a, b, c)) return true
        if (o2 == 0 && onSegment(a, b, d)) return true
        if (o3 == 0 && onSegment(c, d, a)) return true
        if (o4 == 0 && onSegment(c, d, b)) return true
        return false
    }
}

/** Polygon of 3D points that are (approximately) coplanar, in outline order. */
class Polygon3(val points: List<Vec3>) {

    /** Fits a plane and projects the outline into it. */
    fun toPlanar(): Pair<PlaneBasis, Polygon2> {
        val basis = PlaneBasis.fitFromPoints(points)
        return basis to Polygon2(points.map { basis.project(it) })
    }

    /** Area of the outline projected onto its best-fit plane. */
    fun area(): Float = toPlanar().second.area()

    /** Perimeter of the projected outline (equals the 3D perimeter for exactly planar input). */
    fun perimeter(): Float = toPlanar().second.perimeter()

    /** Signed distance of [p] from [basis]'s plane (positive toward the normal). */
    fun heightAbove(basis: PlaneBasis, p: Vec3): Float = basis.signedDistance(p)
}
