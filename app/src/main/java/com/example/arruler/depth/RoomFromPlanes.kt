package com.example.arruler.depth

import com.example.arruler.geometry.Polygon2
import com.example.arruler.geometry.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A room from extracted planes. [outline] is the floor polygon in (world x, world z) coordinates,
 * counter-clockwise in that frame (so [Polygon2.signedArea] > 0); [floorY] / [ceilingY] are world
 * heights at the floor centroid; [height] = ceiling - floor.
 */
class RoomModel(
    val outline: Polygon2,
    val floorY: Float,
    val ceilingY: Float,
    val wallCount: Int
) {
    val height: Float get() = ceilingY - floorY
}

object RoomFromPlanes {

    /**
     * Intersects each wall's trace on the floor with its neighbour's. Walls are ordered by the angle
     * (atan2 in x,z) of their inlier centroid around the floor centroid, so adjacent walls in the
     * list share a corner. Assumes the floor is star-shaped about its centroid (true for boxes and
     * L-shapes) and that every wall was detected. Returns null without a floor and ceiling, with
     * fewer than 3 usable walls, or when fewer than 3 corners can be formed (parallel neighbours).
     * Walls that are parallel and adjacent in the order are skipped as a corner.
     */
    fun build(planes: List<ExtractedPlane>): RoomModel? {
        val floor = planes.filter { it.kind == PlaneKind.FLOOR }.maxByOrNull { it.inlierCount } ?: return null
        val ceiling = planes.filter { it.kind == PlaneKind.CEILING }.maxByOrNull { it.inlierCount } ?: return null
        val walls = planes.filter { it.kind == PlaneKind.WALL }
        if (walls.size < 3) return null

        val fc = floor.centroid
        val floorY = yAt(floor, fc.x, fc.z)
        val ceilingY = yAt(ceiling, fc.x, fc.z)

        // Each wall as a line a*x + b*z = c in the horizontal plane y = floorY.
        class Line(val a: Double, val b: Double, val c: Double, val angle: Double)
        val lines = walls.mapNotNull { w ->
            val a = w.normal.x.toDouble(); val b = w.normal.z.toDouble()
            val len = sqrt(a * a + b * b)
            if (len < 1e-6) return@mapNotNull null
            val c = w.d.toDouble() - w.normal.y.toDouble() * floorY
            Line(a / len, b / len, c / len, atan2((w.centroid.z - fc.z).toDouble(), (w.centroid.x - fc.x).toDouble()))
        }.sortedBy { it.angle }
        if (lines.size < 3) return null

        val corners = ArrayList<Vec2>()
        for (i in lines.indices) {
            val l1 = lines[i]; val l2 = lines[(i + 1) % lines.size]
            val det = l1.a * l2.b - l2.a * l1.b
            if (abs(det) < 1e-3) continue
            val x = (l1.c * l2.b - l2.c * l1.b) / det
            val z = (l1.a * l2.c - l2.a * l1.c) / det
            corners += Vec2(x.toFloat(), z.toFloat())
        }
        if (corners.size < 3) return null
        // the corner between lines[i] and lines[i+1] was appended in angular order -> CCW
        return RoomModel(Polygon2(corners), floorY, ceilingY, lines.size)
    }

    /** World y of a (non-vertical) plane above (x, z). */
    private fun yAt(p: ExtractedPlane, x: Float, z: Float): Float =
        (p.d - p.normal.x * x - p.normal.z * z) / p.normal.y
}
