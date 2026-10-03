package com.example.arruler.objscan

import com.example.arruler.geometry.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Plane n.p = [d] with unit [normal] pointing towards the object (up for a table / floor).
 * [signedDistance] > 0 on the object side.
 */
data class SupportPlane(val normal: Vec3, val d: Float) {
    fun signedDistance(p: Vec3): Float = normal.dot(p) - d
    fun signedDistance(x: Float, y: Float, z: Float): Float = normal.x * x + normal.y * y + normal.z * z - d

    companion object {
        /** Plane through [point] with the given (not necessarily unit) [normal]. */
        fun through(point: Vec3, normal: Vec3 = Vec3(0f, 1f, 0f)): SupportPlane {
            val n = normal.normalized()
            require(n.length() > 0.5f) { "plane normal must be non-zero" }
            return SupportPlane(n, n.dot(point))
        }

        /** Horizontal plane at world height [y]. */
        fun horizontal(y: Float) = SupportPlane(Vec3(0f, 1f, 0f), y)
    }
}

/**
 * Oriented bounding box the user places on a support plane (assumed horizontal, normal = world +Y).
 *
 * [centre] is the centre of the box BASE (it lies on the plane). Local frame: x along the width
 * [w] (world (cos yaw, 0, -sin yaw)), y up (world +Y), z along the depth [d]
 * (world (sin yaw, 0, cos yaw)); [yaw] is a right-handed rotation about +Y in radians. The box occupies
 * local x in [-w/2, w/2], y in [0, h], z in [-d/2, d/2]. All lengths are meters.
 */
data class ObjectBox(
    val centre: Vec3,
    val yaw: Float,
    val w: Float,
    val d: Float,
    val h: Float
) {
    init {
        require(w > 0f && d > 0f && h > 0f) { "box size must be positive" }
    }

    private val c = cos(yaw)
    private val s = sin(yaw)

    /** World -> local (x along width, y up from the base, z along depth). */
    fun toLocal(p: Vec3): Vec3 = toLocal(p.x, p.y, p.z)

    fun toLocal(x: Float, y: Float, z: Float): Vec3 {
        val dx = x - centre.x; val dz = z - centre.z
        return Vec3(dx * c - dz * s, y - centre.y, dx * s + dz * c)
    }

    /** Local -> world; exact inverse of [toLocal]. */
    fun toWorld(l: Vec3): Vec3 =
        Vec3(centre.x + l.x * c + l.z * s, centre.y + l.y, centre.z - l.x * s + l.z * c)

    /** True when [p] lies inside the box grown by [margin] meters on every side. */
    fun contains(p: Vec3, margin: Float = 0f): Boolean = contains(p.x, p.y, p.z, margin)

    fun contains(x: Float, y: Float, z: Float, margin: Float = 0f): Boolean {
        val l = toLocal(x, y, z)
        return l.x >= -w / 2 - margin && l.x <= w / 2 + margin &&
            l.y >= -margin && l.y <= h + margin &&
            l.z >= -d / 2 - margin && l.z <= d / 2 + margin
    }

    /** The 8 world corners: indices 0-3 are the base, 4-7 the top above them. */
    fun corners(): List<Vec3> {
        val hw = w / 2; val hd = d / 2
        val base = listOf(Vec3(-hw, 0f, -hd), Vec3(hw, 0f, -hd), Vec3(hw, 0f, hd), Vec3(-hw, 0f, hd))
        return (base + base.map { Vec3(it.x, h, it.z) }).map { toWorld(it) }
    }

    /** World centre of the volume (half way up). */
    fun volumeCentre(): Vec3 = toWorld(Vec3(0f, h / 2, 0f))

    fun volume(): Float = w * d * h

    /** Largest side in meters; drives the coverage distance window. */
    fun maxDimension(): Float = maxOf(w, d, h)

    // ---- edit helpers (immutable: each returns a new box) ----
    fun moveTo(newCentre: Vec3) = copy(centre = newCentre)
    fun translated(dx: Float, dz: Float) = copy(centre = Vec3(centre.x + dx, centre.y, centre.z + dz))
    fun rotated(dYaw: Float) = copy(yaw = yaw + dYaw)
    fun withYaw(newYaw: Float) = copy(yaw = newYaw)
    fun resized(newW: Float = w, newD: Float = d, newH: Float = h) = copy(w = newW, d = newD, h = newH)
    fun scaled(factor: Float) = copy(w = w * factor, d = d * factor, h = h * factor)

    /** Same box dropped onto another support plane (keeps x/z). */
    fun onPlane(plane: SupportPlane): ObjectBox {
        val n = plane.normal
        val y = if (abs(n.y) > 1e-6f) (plane.d - n.x * centre.x - n.z * centre.z) / n.y else centre.y
        return copy(centre = Vec3(centre.x, y, centre.z))
    }
}
