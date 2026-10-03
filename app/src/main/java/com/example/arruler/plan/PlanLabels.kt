package com.example.arruler.plan

import com.example.arruler.geometry.Measure2D
import com.example.arruler.geometry.Polygon2
import com.example.arruler.geometry.Vec2
import com.example.arruler.measure.Units
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2

enum class LabelKind { WALL, ROOM_NAME, ROOM_AREA, ANGLE }

/**
 * A text label in plan coordinates (meters). [angleDeg] is the counter-clockwise rotation in the
 * y-up plan frame, already normalised to (-90, 90] so text is never upside down. On screen (y down)
 * rotate clockwise by -angleDeg.
 */
data class PlanLabel(
    val text: String,
    val pos: Vec2,
    val angleDeg: Float,
    val kind: LabelKind,
    val roomIndex: Int
)

/** Ray-casting point-in-polygon (boundary points are unspecified). */
fun Polygon2.containsPoint(p: Vec2): Boolean {
    val pts = points
    var inside = false
    var j = pts.size - 1
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[j]
        if ((a.y > p.y) != (b.y > p.y) &&
            p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
        ) inside = !inside
        j = i
    }
    return inside
}

object PlanLabels {

    fun formatLength(m: Float, units: Units): String {
        val v = units.fromMeters(m)
        return when (units) {
            Units.CM -> String.format(Locale.US, "%.0f %s", v, units.symbol)
            Units.INCH -> String.format(Locale.US, "%.1f %s", v, units.symbol)
            else -> String.format(Locale.US, "%.2f %s", v, units.symbol)
        }
    }

    fun formatArea(m2: Float, units: Units): String {
        val u = units.fromMeters(1f)
        val v = m2 * u * u
        val fmt = if (units == Units.CM) "%.0f" else "%.2f"
        return String.format(Locale.US, "$fmt %s²", v, units.symbol)
    }

    /** Rotation in (-90, 90] for a direction (dx, dy) in the y-up frame. */
    fun readableAngleDeg(dx: Float, dy: Float): Float {
        var a = atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI
        if (a > 90.0) a -= 180.0 else if (a <= -90.0) a += 180.0
        return a.toFloat()
    }

    /** A point inside [poly]: the centroid, else the middle of the widest scanline span. */
    fun interiorPoint(poly: Polygon2): Vec2 {
        val c = poly.centroid()
        if (poly.containsPoint(c)) return c
        val pts = poly.points
        val n = pts.size
        val xs = ArrayList<Float>()
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i + 1) % n]
            if ((a.y > c.y) != (b.y > c.y)) xs.add(a.x + (c.y - a.y) / (b.y - a.y) * (b.x - a.x))
        }
        xs.sort()
        var best = c; var bestW = -1f
        for (k in 0 until xs.size - 1 step 2) {
            val w = xs[k + 1] - xs[k]
            if (w > bestW) { bestW = w; best = Vec2((xs[k] + xs[k + 1]) / 2f, c.y) }
        }
        return best
    }

    /**
     * All labels for [plan]: wall lengths outside each wall midpoint ([wallOffsetM] away), room
     * name and area around an interior point ([lineGapM] between lines), and optionally interior
     * angles inside each corner.
     */
    fun compute(
        plan: FloorPlan,
        units: Units,
        wallOffsetM: Float = 0.25f,
        lineGapM: Float = 0.3f,
        showAngles: Boolean = false
    ): List<PlanLabel> {
        val out = ArrayList<PlanLabel>()
        plan.rooms.forEachIndexed { ri, room ->
            val poly = room.outline
            val pts = poly.points
            val n = pts.size
            if (n < 3) return@forEachIndexed
            val ccw = poly.signedArea() >= 0f
            val lengths = Measure2D.wallLengths(poly)
            for (i in 0 until n) {
                val a = pts[i]; val b = pts[(i + 1) % n]
                val d = b - a
                val len = lengths[i]
                if (len < 1e-6f) continue
                val u = Vec2(d.x / len, d.y / len)
                // outward normal: right of the edge for CCW, left for CW
                val nrm = if (ccw) Vec2(u.y, -u.x) else Vec2(-u.y, u.x)
                val mid = Vec2((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                var pos = mid + nrm * wallOffsetM
                if (poly.containsPoint(pos)) {
                    val alt = mid - nrm * wallOffsetM
                    if (!poly.containsPoint(alt)) pos = alt
                }
                out.add(PlanLabel(formatLength(len, units), pos, readableAngleDeg(d.x, d.y), LabelKind.WALL, ri))
            }
            val c = interiorPoint(poly)
            out.add(PlanLabel(room.name, Vec2(c.x, c.y + lineGapM / 2f), 0f, LabelKind.ROOM_NAME, ri))
            out.add(PlanLabel(formatArea(poly.area(), units), Vec2(c.x, c.y - lineGapM / 2f), 0f, LabelKind.ROOM_AREA, ri))
            if (showAngles) {
                val angles = Measure2D.interiorAngles(poly)
                for (i in 0 until n) {
                    val p = pts[(i + n - 1) % n]; val b = pts[i]; val q = pts[(i + 1) % n]
                    val e1 = unit(p - b); val e2 = unit(q - b)
                    var bis = unit(e1 + e2)
                    if (bis.length() < 1e-6f) bis = Vec2(-e2.y, e2.x) // straight corner
                    var pos = b + bis * (wallOffsetM * 1.6f)
                    if (!poly.containsPoint(pos)) pos = b - bis * (wallOffsetM * 1.6f)
                    out.add(PlanLabel(String.format(Locale.US, "%.0f°", angles[i]), pos, 0f, LabelKind.ANGLE, ri))
                }
            }
        }
        return out
    }

    private fun unit(v: Vec2): Vec2 {
        val l = v.length()
        return if (l < 1e-9f) Vec2(0f, 0f) else Vec2(v.x / l, v.y / l)
    }
}
