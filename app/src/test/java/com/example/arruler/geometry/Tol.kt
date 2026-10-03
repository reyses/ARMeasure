package com.example.arruler.geometry

import kotlin.math.abs
import org.junit.Assert.assertTrue

/** Float tolerance helpers shared by the geometry tests. */
object Tol {
    const val DEFAULT = 1e-4f

    fun near(expected: Float, actual: Float, tol: Float = DEFAULT) {
        assertTrue("expected $expected but was $actual (tol $tol)", abs(expected - actual) <= tol)
    }

    fun near(expected: Vec3, actual: Vec3, tol: Float = DEFAULT) {
        assertTrue("expected $expected but was $actual (tol $tol)", expected.distanceTo(actual) <= tol)
    }

    fun near(expected: Vec2, actual: Vec2, tol: Float = DEFAULT) {
        assertTrue("expected $expected but was $actual (tol $tol)", expected.distanceTo(actual) <= tol)
    }
}
