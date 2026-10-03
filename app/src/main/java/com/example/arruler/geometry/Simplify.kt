package com.example.arruler.geometry

/** Polyline simplification. */
object Simplify {

    /**
     * Ramer-Douglas-Peucker. [toleranceMeters] is the maximum perpendicular distance a dropped
     * point may have from the simplified polyline. First and last points are always kept.
     */
    fun rdp(points: List<Vec2>, toleranceMeters: Float): List<Vec2> {
        if (points.size < 3) return points.toList()
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = -1f
            var idx = -1
            for (i in s + 1 until e) {
                val d = perpendicularDistance(points[i], points[s], points[e])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > toleranceMeters) {
                keep[idx] = true
                stack.addLast(s to idx)
                stack.addLast(idx to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Distance from [p] to the segment a-b (point distance if a == b). */
    internal fun perpendicularDistance(p: Vec2, a: Vec2, b: Vec2): Float {
        val ab = b - a
        val len2 = ab.dot(ab)
        if (len2 < 1e-18f) return p.distanceTo(a)
        val t = ((p - a).dot(ab) / len2).coerceIn(0f, 1f)
        return p.distanceTo(a + ab * t)
    }
}
