package com.example.arruler.depth

import com.example.arruler.geometry.Polygon2
import com.example.arruler.geometry.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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

/**
 * A side of the room without a wall, named from where the scan started: ARCore's world origin is the
 * camera's first pose, which faced -Z with +X to its right.
 */
enum class RoomSide(val words: String) {
    AHEAD("the wall ahead of where you started"),
    BEHIND("the wall behind where you started"),
    LEFT("the wall to the left of where you started"),
    RIGHT("the wall to the right of where you started"),
}

/**
 * Everything [RoomFromPlanes.assemble] could put together, complete or not. [room] is set only when the
 * floor, the ceiling and walls closing a footprint are all there. [outline] is the closed footprint (it can
 * exist without a ceiling: the area is then known, the height is not). [openSides] are the sides of the
 * room's rectangle frame (axes from the walls' dominant direction) that no wall faces; all four when there
 * are no walls.
 */
class RoomAssembly(
    val room: RoomModel?,
    val outline: Polygon2?,
    val floorY: Float?,
    val ceilingY: Float?,
    val wallCount: Int,
    val openSides: List<RoomSide>,
) {
    val complete: Boolean get() = room != null
}

object RoomFromPlanes {

    /** Walls smaller than this (m^2 of outline) are furniture faces or fragments, not room walls. */
    const val MIN_WALL_AREA = 0.5f
    /** Parallel walls closer than this (m) are one wall; the larger one is kept. */
    const val DUPLICATE_WALL_OFFSET = 0.30f
    /** A corner further than this (m) from both of its walls' observed extents is a bogus intersection. */
    const val MAX_CORNER_GAP = 2.0

    /**
     * Complete room or null (no floor, no ceiling, fewer than 3 usable walls, or they do not close).
     * See [assemble] for the partial result.
     */
    fun build(planes: List<ExtractedPlane>): RoomModel? = assemble(planes).room

    /**
     * Intersects each wall's trace on the floor with its neighbour's. Walls are ordered by the angle
     * (atan2 in x,z) of their inlier centroid around the floor centroid (or the walls' mean centroid
     * without a floor), so adjacent walls in the list share a corner. Assumes the floor is star-shaped
     * about that centre (true for boxes and L-shapes). Walls that are parallel and adjacent in the order
     * are skipped as a corner. A footprint is only formed when no side of the room frame is open.
     */
    fun assemble(planes: List<ExtractedPlane>): RoomAssembly {
        val floor = planes.filter { it.kind == PlaneKind.FLOOR }.maxByOrNull { weight(it) }
        val ceiling = planes.filter { it.kind == PlaneKind.CEILING }.maxByOrNull { weight(it) }
        val walls = usableWalls(planes)
        val open = openSides(walls)

        val cx: Float; val cz: Float
        if (floor != null) { cx = floor.centroid.x; cz = floor.centroid.z }
        else if (walls.isNotEmpty()) { cx = walls.map { it.centroid.x }.average().toFloat(); cz = walls.map { it.centroid.z }.average().toFloat() }
        else return RoomAssembly(null, null, null, ceiling?.let { yAt(it, it.centroid.x, it.centroid.z) }, 0, open)
        val floorY = floor?.let { yAt(it, cx, cz) }
        val ceilingY = ceiling?.let { yAt(it, cx, cz) }

        val outline = if (open.isEmpty() && walls.size >= 3) footprint(walls, cx, cz, floorY ?: walls.map { it.centroid.y }.average().toFloat()) else null
        val room = if (outline != null && floorY != null && ceilingY != null) RoomModel(outline, floorY, ceilingY, walls.size) else null
        return RoomAssembly(room, outline, floorY, ceilingY, walls.size, open)
    }

    private fun weight(p: ExtractedPlane): Float = if (p.inlierCount > 0) p.inlierCount.toFloat() else p.area() * 100f

    /** WALL planes big enough to be room walls, with near-duplicates (parallel, within 30 cm) dropped. */
    private fun usableWalls(planes: List<ExtractedPlane>): List<ExtractedPlane> {
        val out = ArrayList<ExtractedPlane>()
        val cos12 = cos(12.0 * PI / 180).toFloat()
        for (w in planes.filter { it.kind == PlaneKind.WALL && it.area() >= MIN_WALL_AREA }.sortedByDescending { weight(it) }) {
            val dup = out.any { abs(it.normal.dot(w.normal)) >= cos12 && abs(it.distanceTo(w.centroid)) < DUPLICATE_WALL_OFFSET }
            if (!dup) out += w
        }
        return out
    }

    /**
     * Sides of the room frame that no wall faces. Frame: the weighted circular mean of 4x the wall normals'
     * angles (so perpendicular walls agree); a wall on side k has its inward normal within 30 degrees of the
     * side's inward direction.
     */
    internal fun openSides(walls: List<ExtractedPlane>): List<RoomSide> {
        if (walls.isEmpty()) return RoomSide.entries.toList()
        var s = 0.0; var c = 0.0
        for (w in walls) {
            val a = atan2(w.normal.z.toDouble(), w.normal.x.toDouble())
            val wt = weight(w).toDouble()
            s += wt * sin(4 * a); c += wt * cos(4 * a)
        }
        val base = atan2(s, c) / 4
        val out = ArrayList<RoomSide>()
        for (k in 0 until 4) {
            val a = base + k * PI / 2
            val ox = cos(a); val oz = sin(a)      // outward direction of side k
            val covered = walls.any { w ->
                val len = sqrt((w.normal.x * w.normal.x + w.normal.z * w.normal.z).toDouble())
                len > 1e-6 && -(w.normal.x * ox + w.normal.z * oz) / len >= cos(PI / 6)
            }
            if (!covered) out += sideOf(ox, oz)
        }
        return out.distinct()
    }

    /** The start-relative name of an outward (x, z) direction: start camera looked down -Z, +X to its right. */
    private fun sideOf(ox: Double, oz: Double): RoomSide = when {
        abs(ox) >= abs(oz) -> if (ox > 0) RoomSide.RIGHT else RoomSide.LEFT
        else -> if (oz < 0) RoomSide.AHEAD else RoomSide.BEHIND
    }

    private fun footprint(walls: List<ExtractedPlane>, cx: Float, cz: Float, atY: Float): Polygon2? {
        // Each wall as a line a*x + b*z = c in the horizontal plane y = atY.
        class Line(val a: Double, val b: Double, val c: Double, val angle: Double, val wall: ExtractedPlane)
        val lines = walls.mapNotNull { w ->
            val a = w.normal.x.toDouble(); val b = w.normal.z.toDouble()
            val len = sqrt(a * a + b * b)
            if (len < 1e-6) return@mapNotNull null
            val c = w.d.toDouble() - w.normal.y.toDouble() * atY
            Line(a / len, b / len, c / len, atan2((w.centroid.z - cz).toDouble(), (w.centroid.x - cx).toDouble()), w)
        }.sortedBy { it.angle }
        if (lines.size < 3) return null

        val corners = ArrayList<Vec2>()
        for (i in lines.indices) {
            val l1 = lines[i]; val l2 = lines[(i + 1) % lines.size]
            val det = l1.a * l2.b - l2.a * l1.b
            if (abs(det) < 1e-3) continue
            val x = (l1.c * l2.b - l2.c * l1.b) / det
            val z = (l1.a * l2.c - l2.a * l1.c) / det
            if (minOf(extentGap(l1.wall, x, z), extentGap(l2.wall, x, z)) > MAX_CORNER_GAP) return null
            corners += Vec2(x.toFloat(), z.toFloat())
        }
        if (corners.size < 3) return null
        // the corner between lines[i] and lines[i+1] was appended in angular order -> CCW
        return Polygon2(corners).takeIf { it.isSimple() }
    }

    /** Horizontal distance (m) from (x, z) to the wall's observed extent along its own direction. */
    private fun extentGap(w: ExtractedPlane, x: Double, z: Double): Double {
        val tx = -w.normal.z.toDouble(); val tz = w.normal.x.toDouble()
        val len = sqrt(tx * tx + tz * tz)
        if (len < 1e-6) return Double.MAX_VALUE
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (q in w.outline3d()) {
            val t = (q.x * tx + q.z * tz) / len
            if (t < lo) lo = t
            if (t > hi) hi = t
        }
        val t = (x * tx + z * tz) / len
        return maxOf(0.0, t - hi, lo - t)
    }

    /** World y of a (non-vertical) plane above (x, z). */
    private fun yAt(p: ExtractedPlane, x: Float, z: Float): Float =
        (p.d - p.normal.x * x - p.normal.z * z) / p.normal.y
}
