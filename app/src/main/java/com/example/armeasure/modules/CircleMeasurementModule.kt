package com.example.armeasure.modules

import com.google.ar.sceneform.math.Vector3

// Data class to hold the results of our calculation
data class CircleResult(val center: Vector3, val radius: Float)

/**
 * A self-contained module for handling 3-point circle calculations.
 */
object CircleMeasurementModule {

    /**
     * Calculates the center and radius of a circle defined by three 3D points.
     * @return A CircleResult containing the center and radius, or null if the points are collinear.
     */
    fun calculateCircleFromThreePoints(p1: Vector3, p2: Vector3, p3: Vector3): CircleResult? {
        // Corrected to use the static Vector3.subtract method
        val p12 = Vector3.subtract(p2, p1)
        val p13 = Vector3.subtract(p3, p1)

        val p12x13 = Vector3.cross(p12, p13)
        val p12x13sq = p12x13.lengthSquared()

        // Check if the points are in a line (collinear)
        if (p12x13sq < 0.00001f) {
            return null
        }

        val p13sq = p13.lengthSquared()
        val p12sq = p12.lengthSquared()

        val a = p13sq * (Vector3.dot(p12, p12) - Vector3.dot(p12, p13))
        val b = p12sq * (Vector3.dot(p13, p13) - Vector3.dot(p12, p13))

        val cx = p1.x + (a * p13.x - b * p12.x) / (2 * p12x13sq)
        val cy = p1.y + (a * p13.y - b * p12.y) / (2 * p12x13sq)
        val cz = p1.z + (a * p13.z - b * p12.z) / (2 * p12x13sq)

        val center = Vector3(cx, cy, cz)
        // Corrected to use the static Vector3.subtract method
        val radius = Vector3.subtract(center, p1).length()

        return CircleResult(center, radius)
    }
}
