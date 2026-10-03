package com.example.arruler.ar

import com.example.arruler.measure.MeasurePoint
import dev.romainguy.kotlin.math.Quaternion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SceneView 4's node composables take Euler degrees, so the segment orientation (a quaternion that
 * takes +Y onto the segment) goes through toEulerAngles()/fromEuler(). It must round-trip for every
 * direction, including the vertical and horizontal ones where Euler angles are singular.
 */
class ArRendererGeometryTest {

    private fun dot(a: Quaternion, b: Quaternion) = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w

    /** Rotates +Y by [q]; returns (x, y, z) in unit length. */
    private fun upRotated(q: Quaternion): Triple<Float, Float, Float> {
        val x = 2f * (q.x * q.y - q.w * q.z)
        val y = 1f - 2f * (q.x * q.x + q.z * q.z)
        val z = 2f * (q.y * q.z + q.w * q.x)
        return Triple(x, y, z)
    }

    @Test
    fun rotationTakesUpOntoTheSegmentDirection() {
        val dirs = directions()
        for ((dx, dy, dz) in dirs) {
            val (x, y, z) = upRotated(ArRenderer.rotationFromUpTo(dx, dy, dz))
            assertEquals("dx for ($dx,$dy,$dz)", dx, x, 1e-4f)
            assertEquals("dy for ($dx,$dy,$dz)", dy, y, 1e-4f)
            assertEquals("dz for ($dx,$dy,$dz)", dz, z, 1e-4f)
        }
    }

    @Test
    fun eulerRoundTripKeepsTheOrientation() {
        for ((dx, dy, dz) in directions()) {
            val q = ArRenderer.rotationFromUpTo(dx, dy, dz)
            val back = Quaternion.fromEuler(q.toEulerAngles())
            assertTrue("orientation lost for ($dx,$dy,$dz): dot=${dot(q, back)}", abs(dot(q, back)) > 0.9999f)
        }
    }

    @Test
    fun segmentPlacesCentreAndLength() {
        val s = ArRenderer.segment(MeasurePoint(0f, 0f, 0f), MeasurePoint(2f, 0f, 0f), 0.003f)
        assertEquals(1f, s.center.x, 1e-6f)
        assertEquals(2f, s.length, 1e-6f)
        assertEquals(0.003f, s.radius, 1e-9f)
        val (x, y, z) = upRotated(s.rotation)
        assertEquals(1f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
        assertEquals(0f, z, 1e-4f)
    }

    private fun directions(): List<Triple<Float, Float, Float>> {
        val out = mutableListOf(
            Triple(0f, 1f, 0f), Triple(0f, -1f, 0f), Triple(1f, 0f, 0f), Triple(-1f, 0f, 0f),
            Triple(0f, 0f, 1f), Triple(0f, 0f, -1f),
        )
        for (pitch in -90..90 step 15) for (yaw in 0 until 360 step 15) {
            val p = Math.toRadians(pitch.toDouble())
            val w = Math.toRadians(yaw.toDouble())
            val x = (cos(p) * sin(w)).toFloat()
            val y = sin(p).toFloat()
            val z = (cos(p) * cos(w)).toFloat()
            val n = sqrt(x * x + y * y + z * z)
            out += Triple(x / n, y / n, z / n)
        }
        return out
    }
}
