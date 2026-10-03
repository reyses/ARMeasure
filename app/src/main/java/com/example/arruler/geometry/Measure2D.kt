package com.example.arruler.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Measurements on a 2D room plan. */
object Measure2D {

    /** Length of each wall; element i is the edge from vertex i to vertex i+1 (cyclic). */
    fun wallLengths(poly: Polygon2): List<Float> {
        val p = poly.points
        if (p.size < 2) return emptyList()
        return p.indices.map { p[it].distanceTo(p[(it + 1) % p.size]) }
    }

    /**
     * Interior angle at each vertex in degrees (0..360). Reflex corners of concave polygons
     * report > 180. Independent of winding direction.
     */
    fun interiorAngles(poly: Polygon2): List<Float> {
        val p = poly.points
        val n = p.size
        if (n < 3) return emptyList()
        val s = if (poly.signedArea() >= 0f) 1.0 else -1.0
        return (0 until n).map { i ->
            val a = p[(i + n - 1) % n]; val b = p[i]; val c = p[(i + 1) % n]
            val turn = turnDeg(b.x - a.x, b.y - a.y, c.x - b.x, c.y - b.y)
            (180.0 - s * turn).toFloat()
        }
    }

    /**
     * Nudges near-right-angle corners to exactly 90 degrees (or 270 for reflex right angles).
     *
     * Method: the first edge (vertex 0 -> 1) is kept fixed. Edge headings are rebuilt by walking
     * the polygon and accumulating the turn at each vertex 1..n-1; a turn whose interior angle is
     * within [toleranceDeg] of 90 (or 270) is replaced by the exact right-angle turn, every
     * other turn is kept. Then the edge lengths of edges 1..n-1 are adjusted by the smallest
     * least-squares change (minimum-norm correction) so the outline closes again with the new
     * headings. Vertex 0's angle is whatever closure implies (exactly 90 when all other corners
     * of a quadrilateral were snapped). Polygons with < 3 vertices are returned unchanged.
     */
    fun snapRightAngles(poly: Polygon2, toleranceDeg: Float = 5f): Polygon2 {
        val p = poly.points
        val n = p.size
        if (n < 3) return poly
        val s = if (poly.signedArea() >= 0f) 1.0 else -1.0
        val len = DoubleArray(n)
        val heading = DoubleArray(n)
        for (k in 0 until n) {
            val a = p[k]; val b = p[(k + 1) % n]
            val dx = b.x.toDouble() - a.x; val dy = b.y.toDouble() - a.y
            len[k] = Math.hypot(dx, dy)
            heading[k] = atan2(dy, dx)
        }
        val newHeading = DoubleArray(n)
        newHeading[0] = heading[0]
        for (k in 1 until n) {
            val turn = turnDeg(
                cos(heading[k - 1]), sin(heading[k - 1]), cos(heading[k]), sin(heading[k])
            )
            val interior = 180.0 - s * turn
            val newTurn = when {
                abs(interior - 90.0) <= toleranceDeg -> s * 90.0
                abs(interior - 270.0) <= toleranceDeg -> -s * 90.0
                else -> turn
            }
            newHeading[k] = newHeading[k - 1] + newTurn * PI / 180.0
        }
        val dx = DoubleArray(n) { cos(newHeading[it]) }
        val dy = DoubleArray(n) { sin(newHeading[it]) }
        val l = len.copyOf()

        // closure residual of edges 1..n-1 against the fixed first edge
        var rx = -l[0] * dx[0]; var ry = -l[0] * dy[0]
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (k in 1 until n) {
            rx -= l[k] * dx[k]; ry -= l[k] * dy[k]
            sxx += dx[k] * dx[k]; sxy += dx[k] * dy[k]; syy += dy[k] * dy[k]
        }
        val det = sxx * syy - sxy * sxy
        if (abs(det) > 1e-12) {
            // lambda = (D D^T)^-1 r ; delta_k = d_k . lambda
            val lx = (syy * rx - sxy * ry) / det
            val ly = (-sxy * rx + sxx * ry) / det
            for (k in 1 until n) l[k] += dx[k] * lx + dy[k] * ly
        }

        val out = ArrayList<Vec2>(n)
        var cx = p[0].x.toDouble(); var cy = p[0].y.toDouble()
        out.add(p[0])
        for (k in 0 until n - 1) {
            cx += l[k] * dx[k]; cy += l[k] * dy[k]
            out.add(Vec2(cx.toFloat(), cy.toFloat()))
        }
        return Polygon2(out)
    }

    /** Signed turn in degrees (CCW positive, -180..180] from vector (ax,ay) to (bx,by). */
    private fun turnDeg(ax: Double, ay: Double, bx: Double, by: Double): Double =
        atan2(ax * by - ay * bx, ax * bx + ay * by) * 180.0 / PI

    private fun turnDeg(ax: Float, ay: Float, bx: Float, by: Float): Double =
        turnDeg(ax.toDouble(), ay.toDouble(), bx.toDouble(), by.toDouble())
}
