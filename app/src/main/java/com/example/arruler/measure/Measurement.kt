package com.example.arruler.measure

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
}
