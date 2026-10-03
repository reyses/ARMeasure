package com.example.arruler.store

import com.example.arruler.geometry.Measure2D
import com.example.arruler.geometry.PlaneBasis
import com.example.arruler.geometry.Polygon2
import com.example.arruler.geometry.Vec2
import com.example.arruler.geometry.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Maps world points to plan coordinates: projection onto the floor plane [basis], then a rotation
 * by -[rotationRad] so the first room's longest wall lies along plan x. Rooms captured in one AR
 * session share one frame, which keeps their relative positions.
 */
class PlanFrame(val basis: PlaneBasis, val rotationRad: Float) {

    fun toPlan(p: Vec3): Vec2 {
        val q = basis.project(p)
        val c = cos(rotationRad); val s = sin(rotationRad)
        return Vec2(q.x * c + q.y * s, -q.x * s + q.y * c)
    }

    companion object {
        /**
         * Frame fitted to [points] (outline order). The plane normal is flipped to point up
         * (world +Y) so the plan is not mirrored, and the longest wall becomes plan-horizontal.
         */
        fun from(points: List<Vec3>): PlanFrame {
            var basis = PlaneBasis.fitFromPoints(points)
            if (basis.normal.y < 0f) basis = PlaneBasis(-basis.normal, basis.origin)
            val flat = points.map { basis.project(it) }
            var best = 0f
            var angle = 0f
            for (i in flat.indices) {
                val a = flat[i]; val b = flat[(i + 1) % flat.size]
                val len = a.distanceTo(b)
                if (len > best) { best = len; angle = atan2(b.y - a.y, b.x - a.x) }
            }
            return PlanFrame(basis, if (best > 1e-6f) angle else 0f)
        }
    }
}

/** An outline ready to be saved, before ([raw]) and after ([snapped]) right-angle snapping. */
class CapturedOutline(val frame: PlanFrame, val raw: Polygon2, val snapped: Polygon2) {
    fun outline(snap: Boolean): Polygon2 = if (snap) snapped else raw
    fun areaM2(snap: Boolean): Float = outline(snap).area()

    fun toSavedRoom(id: String, name: String, snap: Boolean, heightM: Float?, capturedAt: Long): SavedRoom {
        val poly = outline(snap)
        val area = poly.area()
        return SavedRoom(
            id = id,
            name = name,
            outline = poly.points.map { PlanPoint(it.x, it.y) },
            heightM = heightM,
            areaM2 = area,
            perimeterM = poly.perimeter(),
            volumeM3 = heightM?.let { area * it },
            capturedAt = capturedAt,
        )
    }
}

object RoomCapture {
    /**
     * Projects the world [points] through [frame] (or a new frame fitted to them when null). The
     * caller keeps the returned [CapturedOutline.frame] as the session frame once the room is saved.
     */
    fun capture(points: List<Vec3>, frame: PlanFrame?): CapturedOutline {
        val f = frame ?: PlanFrame.from(points)
        val raw = Polygon2(points.map { f.toPlan(it) })
        return CapturedOutline(f, raw, Measure2D.snapRightAngles(raw))
    }
}
