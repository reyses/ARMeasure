package com.example.arruler.measure

import kotlin.math.sqrt

/** A point in AR world coordinates, in meters. Pure Kotlin: no Android/AR types. */
data class MeasurePoint(val x: Float, val y: Float, val z: Float) {
    fun distanceTo(other: MeasurePoint): Float {
        val dx = other.x - x
        val dy = other.y - y
        val dz = other.z - z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
