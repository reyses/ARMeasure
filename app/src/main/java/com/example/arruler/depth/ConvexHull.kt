package com.example.arruler.depth

import com.example.arruler.geometry.Vec2

/** Andrew's monotone chain convex hull. */
object ConvexHull {
    /** Counter-clockwise hull without a repeated first point; collinear points are dropped. */
    fun of(points: List<Vec2>): List<Vec2> {
        if (points.size < 3) return points.distinct()
        val p = points.sortedWith(compareBy<Vec2>({ it.x }, { it.y })).distinct()
        if (p.size < 3) return p
        val hull = ArrayList<Vec2>(p.size * 2)
        for (pt in p) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], pt) <= 0f) hull.removeAt(hull.size - 1)
            hull.add(pt)
        }
        val lower = hull.size + 1
        for (i in p.size - 2 downTo 0) {
            val pt = p[i]
            while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], pt) <= 0f) hull.removeAt(hull.size - 1)
            hull.add(pt)
        }
        hull.removeAt(hull.size - 1)
        return hull
    }

    private fun cross(o: Vec2, a: Vec2, b: Vec2): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
}
