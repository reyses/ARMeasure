package com.example.arruler.objscan

import com.example.arruler.geometry.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** A world ray: [origin] and unit [dir]. */
class Ray(val origin: Vec3, val dir: Vec3)

/** Touch pixel to world ray and ray-plane intersection (column-major 4x4 matrices as OpenGL / ARCore give them). */
object PlaneRaycast {

    /** a * b, both column-major. */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            o[c * 4 + r] = s
        }
        return o
    }

    /** Inverse of a column-major 4x4, or null when singular. */
    fun invert(m: FloatArray): FloatArray? {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (abs(det) < 1e-12f) return null
        val id = 1f / det
        for (i in 0 until 16) inv[i] *= id
        return inv
    }

    private fun unproject(inv: FloatArray, x: Float, y: Float, z: Float): Vec3? {
        val vx = inv[0] * x + inv[4] * y + inv[8] * z + inv[12]
        val vy = inv[1] * x + inv[5] * y + inv[9] * z + inv[13]
        val vz = inv[2] * x + inv[6] * y + inv[10] * z + inv[14]
        val vw = inv[3] * x + inv[7] * y + inv[11] * z + inv[15]
        if (abs(vw) < 1e-12f) return null
        return Vec3(vx / vw, vy / vw, vz / vw)
    }

    /** The world ray through view pixel ([xPx], [yPx], y down) of a [width] x [height] view; null on a degenerate camera. */
    fun rayFromScreen(xPx: Float, yPx: Float, width: Int, height: Int, view: FloatArray, proj: FloatArray): Ray? {
        if (width <= 0 || height <= 0) return null
        val inv = invert(mul(proj, view)) ?: return null
        val nx = 2f * xPx / width - 1f
        val ny = 1f - 2f * yPx / height
        val near = unproject(inv, nx, ny, -1f) ?: return null
        val far = unproject(inv, nx, ny, 1f) ?: return null
        val dx = far.x - near.x; val dy = far.y - near.y; val dz = far.z - near.z
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 1e-9f) return null
        return Ray(near, Vec3(dx / l, dy / l, dz / l))
    }

    /** Where [ray] meets the horizontal plane y = [planeY] in front of its origin, or null (parallel or behind). */
    fun intersectHorizontal(ray: Ray, planeY: Float): Vec3? {
        if (abs(ray.dir.y) < 1e-6f) return null
        val t = (planeY - ray.origin.y) / ray.dir.y
        if (t <= 0f) return null
        return Vec3(ray.origin.x + ray.dir.x * t, planeY, ray.origin.z + ray.dir.z * t)
    }
}

/** A horizontal plane candidate near a tap: its height and the horizontal distance from the tap to its polygon (0 inside). */
data class PlaneCandidate(val y: Float, val distXz: Float)

/** Which horizontal plane the tapped object stands on. */
object SupportPlanePick {
    const val MAX_BELOW = 2.0f
    const val MAX_DIST = 0.35f
    const val TOLERANCE = 0.03f

    /** The highest candidate not above the tap (plus [TOLERANCE]) and within [MAX_BELOW] below it and [MAX_DIST] sideways; null when none. */
    fun pick(tap: Vec3, candidates: List<PlaneCandidate>): Float? =
        candidates
            .filter { it.distXz <= MAX_DIST && it.y <= tap.y + TOLERANCE && tap.y - it.y <= MAX_BELOW }
            .maxOfOrNull { it.y }
}
