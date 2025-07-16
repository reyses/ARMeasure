package com.example.armeasure // Use your app's package name

import com.google.ar.core.Pose
import kotlin.math.sqrt

/**
 * A comprehensive module for handling measurements and related mathematical calculations,
 * specifically tailored for AR contexts.
 */
object MeasurementModule {

    /**
     * Calculates the distance in meters between two ARCore Poses. This is the primary
     * function to be called from the main AR activity.
     *
     * @param startPose The starting AR pose.
     * @param endPose The ending AR pose.
     * @return The distance in meters as a Float.
     */
    fun calculateDistance(startPose: Pose, endPose: Pose): Float {
        val dx = startPose.tx() - endPose.tx()
        val dy = startPose.ty() - endPose.ty()
        val dz = startPose.tz() - endPose.tz()
        return calculateDistanceComponents(dx, dy, dz)
    }

    /**
     * A helper function that calculates the Euclidean distance from coordinate differences.
     * This keeps the core math separate and reusable.
     *
     * @param dx The difference in the x-coordinates.
     * @param dy The difference in the y-coordinates.
     * @param dz The difference in the z-coordinates.
     * @return The Euclidean distance as a Float.
     */
    fun calculateDistanceComponents(dx: Float, dy: Float, dz: Float): Float {
        // kotlin.math.sqrt works with Double, so the result is cast back to Float.
        return sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }

    // --- Kotlin Extension Functions for Unit Conversion ---

    /**
     * Converts a distance from meters to centimeters.
     * @return The distance in centimeters.
     */
    fun Float.toCentimeters(): Float {
        return this * 100.0f
    }

    /**
     * Converts a distance from meters to inches.
     * @return The distance in inches.
     */
    fun Float.toInches(): Float {
        return this * 39.3701f
    }

    // --- Additional Math Utilities ---

    /**
     * Converts degrees to radians.
     * @param degrees The angle in degrees.
     * @return The angle in radians.
     */
    fun degreesToRadians(degrees: Float): Float {
        return (degrees * kotlin.math.PI / 180.0).toFloat()
    }

    /**
     * Converts radians to degrees.
     * @param radians The angle in radians.
     * @return The angle in degrees.
     */
    fun radiansToDegrees(radians: Float): Float {
        return (radians * 180.0 / kotlin.math.PI).toFloat()
    }
}