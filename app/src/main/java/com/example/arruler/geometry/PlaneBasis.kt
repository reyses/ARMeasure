package com.example.arruler.geometry

import kotlin.math.abs

/**
 * Orthonormal frame in a plane: [u] and [v] span the plane, [normal] = u x v.
 * [project] maps a 3D point to plane coordinates relative to [origin]; [unproject] is the inverse
 * for points on the plane. Build with `PlaneBasis(normal, origin)` or [fitFromPoints].
 */
class PlaneBasis private constructor(
    val origin: Vec3,
    val normal: Vec3,
    val u: Vec3,
    val v: Vec3
) {
    fun project(p: Vec3): Vec2 {
        val d = p - origin
        return Vec2(d.dot(u), d.dot(v))
    }

    fun unproject(q: Vec2): Vec3 = origin + u * q.x + v * q.y

    /** Signed distance of [p] from the plane, positive on the side [normal] points to. */
    fun signedDistance(p: Vec3): Float = (p - origin).dot(normal)

    companion object {
        /** Basis for the plane with the given (not necessarily unit) [normal] through [origin]. */
        operator fun invoke(normal: Vec3, origin: Vec3 = Vec3.ZERO): PlaneBasis {
            val n = normal.normalized()
            require(n.length() > 0.5f) { "plane normal must be non-zero" }
            // pick the world axis least aligned with n to avoid a degenerate cross product
            val axis = when {
                abs(n.x) <= abs(n.y) && abs(n.x) <= abs(n.z) -> Vec3(1f, 0f, 0f)
                abs(n.y) <= abs(n.z) -> Vec3(0f, 1f, 0f)
                else -> Vec3(0f, 0f, 1f)
            }
            val u = n.cross(axis).normalized()
            val v = n.cross(u).normalized()
            return PlaneBasis(origin, n, u, v)
        }

        /**
         * Best-fit plane through >= 3 points. Origin = centroid; normal = Newell's method on the
         * points in the given order (so points should be ordered around the outline; a polygon
         * wound counter-clockwise about the returned normal has positive projected area).
         * Collinear input falls back to a normal perpendicular to the line (Y-up preferred).
         */
        fun fitFromPoints(points: List<Vec3>): PlaneBasis {
            require(points.size >= 3) { "need at least 3 points" }
            val n = points.size
            var cx = 0.0; var cy = 0.0; var cz = 0.0
            for (p in points) { cx += p.x; cy += p.y; cz += p.z }
            cx /= n; cy /= n; cz /= n
            var nx = 0.0; var ny = 0.0; var nz = 0.0
            for (i in 0 until n) {
                val a = points[i]; val b = points[(i + 1) % n]
                val ax = a.x - cx; val ay = a.y - cy; val az = a.z - cz
                val bx = b.x - cx; val by = b.y - cy; val bz = b.z - cz
                nx += (ay - by) * (az + bz)
                ny += (az - bz) * (ax + bx)
                nz += (ax - bx) * (ay + by)
            }
            val origin = Vec3(cx.toFloat(), cy.toFloat(), cz.toFloat())
            var normal = Vec3(nx.toFloat(), ny.toFloat(), nz.toFloat())
            if (normal.length() < 1e-9f) {
                val far = points.maxByOrNull { it.distanceTo(origin) }!!
                val dir = (far - origin).normalized()
                var c = dir.cross(Vec3(0f, 1f, 0f))
                if (c.length() < 1e-6f) c = dir.cross(Vec3(1f, 0f, 0f))
                require(c.length() > 1e-9f) { "degenerate points" }
                normal = dir.cross(c)
            }
            return invoke(normal, origin)
        }
    }
}
