package com.example.arruler.depth

import com.example.arruler.geometry.Vec3
import kotlin.math.abs

/** ARCore's Plane.Type, mirrored so the fusion stays free of ARCore classes. */
enum class ArPlaneType { HORIZONTAL_UP, HORIZONTAL_DOWN, VERTICAL }

/**
 * One TRACKING, non-subsumed ARCore plane at the moment of Analyze, in world space (meters, +Y up).
 * [centre] is the centre pose's position, [normal] its +Y axis (unit; up for HORIZONTAL_UP, down for
 * HORIZONTAL_DOWN, horizontal for VERTICAL), [polygon] the boundary lifted to world space,
 * [extentX] / [extentZ] the centre pose's extents. Built by ArSessionController.trackedPlanes() on device,
 * directly by tests.
 */
class ArPlaneObservation(
    val type: ArPlaneType,
    val centre: Vec3,
    val normal: Vec3,
    val polygon: List<Vec3>,
    val extentX: Float = 0f,
    val extentZ: Float = 0f,
) {
    /** Polygon area in square meters (Newell), falling back to extentX * extentZ without a polygon. */
    val area: Float
        get() {
            if (polygon.size < 3) return extentX * extentZ
            var nx = 0.0; var ny = 0.0; var nz = 0.0
            for (i in polygon.indices) {
                val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
                nx += (a.y - b.y).toDouble() * (a.z + b.z)
                ny += (a.z - b.z).toDouble() * (a.x + b.x)
                nz += (a.x - b.x).toDouble() * (a.y + b.y)
            }
            return (kotlin.math.sqrt(nx * nx + ny * ny + nz * nz) / 2).toFloat()
        }

    val isHorizontal: Boolean get() = type != ArPlaneType.VERTICAL

    companion object {
        /** Classifies a centre-pose normal the way ARCore does (for callers that only have a pose). */
        fun typeOf(normal: Vec3): ArPlaneType = when {
            abs(normal.y) < 0.5f -> ArPlaneType.VERTICAL
            normal.y > 0f -> ArPlaneType.HORIZONTAL_UP
            else -> ArPlaneType.HORIZONTAL_DOWN
        }
    }
}
