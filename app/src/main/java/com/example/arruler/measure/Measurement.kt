package com.example.arruler.measure

import com.example.arruler.geometry.PlaneBasis
import com.example.arruler.geometry.Polygon3
import com.example.arruler.geometry.Vec3
import com.example.arruler.geometry.Volumes
import kotlin.math.abs

internal fun MeasurePoint.toVec3() = Vec3(x, y, z)

/** A finished or in-progress measurement over world points (meters). */
sealed class Measurement {
    /** Ordered world points the measurement is made of. */
    abstract val points: List<MeasurePoint>

    /** Total path length in meters. */
    abstract val lengthMeters: Float

    data class Distance(val a: MeasurePoint, val b: MeasurePoint) : Measurement() {
        override val points: List<MeasurePoint> get() = listOf(a, b)
        override val lengthMeters: Float get() = a.distanceTo(b)
    }

    /** Open chain of segments; length is the sum of segment lengths. */
    data class Polyline(override val points: List<MeasurePoint>) : Measurement() {
        override val lengthMeters: Float
            get() {
                var sum = 0f
                for (i in 1 until points.size) sum += points[i - 1].distanceTo(points[i])
                return sum
            }
    }

    /** Closed polygon over [points] (outline order, >= 3 points, meters). */
    data class Area(override val points: List<MeasurePoint>) : Measurement() {
        private val polygon: Polygon3 get() = Polygon3(points.map { it.toVec3() })

        /** Perimeter in meters; this is the measurement's "length". */
        override val lengthMeters: Float get() = perimeter()

        /** Area in m2 of the outline projected onto its best-fit plane (0 below 3 points). */
        fun area(): Float = if (points.size < 3) 0f else polygon.area()

        fun perimeter(): Float = if (points.size < 3) 0f else polygon.perimeter()

        /** Edge lengths in meters, point i to point i+1 (last edge closes to the first). */
        fun wallLengths(): List<Float> =
            points.indices.map { points[it].distanceTo(points[(it + 1) % points.size]) }

        /** Signed distance in meters of [p] from the fitted floor plane (sign follows the winding). */
        fun signedHeightOf(p: MeasurePoint): Float =
            PlaneBasis.fitFromPoints(points.map { it.toVec3() }).signedDistance(p.toVec3())

        /** Height in meters of [p] above or below the floor plane (absolute value). */
        fun heightOf(p: MeasurePoint): Float = abs(signedHeightOf(p))

        /** Fitted plane's centroid (its origin), where the height segment starts. */
        fun centroid(): MeasurePoint {
            val o = PlaneBasis.fitFromPoints(points.map { it.toVec3() }).origin
            return MeasurePoint(o.x, o.y, o.z)
        }
    }

    /** Prism: [area] extruded by [height] meters. [lengthMeters] is the height. */
    data class Volume(val area: Area, val height: Float) : Measurement() {
        override val points: List<MeasurePoint> get() = area.points
        override val lengthMeters: Float get() = height

        /** Volume in m3. */
        fun volume(): Float =
            if (area.points.size < 3) 0f
            else Volumes.extrudedPolygon(Polygon3(area.points.map { it.toVec3() }), height)
    }
}
