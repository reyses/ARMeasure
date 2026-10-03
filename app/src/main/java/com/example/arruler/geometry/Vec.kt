package com.example.arruler.geometry

import kotlin.math.sqrt

/** 3D vector / point in meters. Pure Kotlin, no Android dependencies. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length(): Float = sqrt(dot(this))

    /** Unit vector; returns the zero vector if the length is (near) zero. */
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-12f) Vec3(0f, 0f, 0f) else Vec3(x / l, y / l, z / l)
    }

    fun distanceTo(o: Vec3): Float = (this - o).length()

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

/** 2D vector / point (plane coordinates, meters). */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun dot(o: Vec2): Float = x * o.x + y * o.y

    /** z component of the 3D cross product. */
    fun cross(o: Vec2): Float = x * o.y - y * o.x
    fun length(): Float = sqrt(dot(this))
    fun distanceTo(o: Vec2): Float = (this - o).length()
}
