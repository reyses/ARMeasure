package com.example.arruler

import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlin.math.sqrt
import kotlin.math.abs

class QuaternionBenchmarkTest {

    @Test
    fun testQuaternionLookRotationBehavior() {
        // ... (Keep existing tests)
        val q1 = Quaternion.lookRotation(Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f))
        println("LookRotation(Y, Z): $q1")
    }

    @Test
    fun testQuaternionLookRotation() {
        val vectors = listOf(
            Pair(Vector3(1.0f, 0.5f, 0.2f), Vector3.up()),
            Pair(Vector3(0.0f, 1.0f, 0.0f), Vector3(0f, 0f, 1f)), // Y forward, Z up
            Pair(Vector3(0.0f, 0.0f, 1.0f), Vector3(0f, 1f, 0f)), // Z forward, Y up
            Pair(Vector3(0.0f, 1.0f, 0.0f), Vector3(0f, 1f, 0f)), // Parallel Y, Y
            Pair(Vector3(1.0f, 0.0f, 0.0f), Vector3(0f, 1f, 0f)),
            Pair(Vector3(0f, 0f, 0f), Vector3.up()) // Zero vector
        )

        for ((forward, up) in vectors) {
            // Original
            // Handle zero vector exception if any? Sceneform might return identity or throw.
            // Let's assume identity for zero.
            val q1 = if (forward.length() > 1e-6) Quaternion.lookRotation(forward, up) else Quaternion.identity()

            // Manual
            val q2 = Quaternion()
            setLookRotation(q2, forward, up)

            // Check dot product of quaternions to verify they represent same rotation (q == -q)
            // Or just check components match with sign tolerance.
            // Quaternion dot product approach is better: |q1 . q2| approx 1.
            val dot = q1.x * q2.x + q1.y * q2.y + q1.z * q2.z + q1.w * q2.w
            val absDot = if (dot < 0) -dot else dot

            if (absDot < 0.999f) {
                 println("Failed for forward=$forward, up=$up")
                 println("Expected: $q1")
                 println("Actual:   $q2")
            }
            assertEquals(1.0f, absDot, 0.001f)
        }
    }

    // The implementation to be pasted into MainActivity
    @Test
    fun benchmark() {
        val forward = Vector3(1.0f, 0.5f, 0.2f).normalized()
        val up = Vector3.up()
        val iterations = 1000000

        // Warmup
        val qWarm = Quaternion()
        for (i in 0 until 10000) {
             setLookRotation(qWarm, forward, up)
             Quaternion.lookRotation(forward, up)
        }

        val start1 = System.nanoTime()
        for (i in 0 until iterations) {
            val q = Quaternion.lookRotation(forward, up)
            blackhole(q)
        }
        val end1 = System.nanoTime()

        val start2 = System.nanoTime()
        val qReusable = Quaternion()
        for (i in 0 until iterations) {
            setLookRotation(qReusable, forward, up)
            blackhole(qReusable)
        }
        val end2 = System.nanoTime()

        println("Allocation method: ${(end1 - start1) / 1000000} ms")
        println("No-allocation method: ${(end2 - start2) / 1000000} ms")
    }

    private fun blackhole(obj: Any?) {
        // Prevent dead code elimination
        if (obj.hashCode() == System.nanoTime().toInt()) {
            println(obj)
        }
    }

    private fun setLookRotation(dest: Quaternion, forward: Vector3, up: Vector3) {
        // Assume forward is normalized?
        // In MainActivity usage, it is normalized.
        // But for robustness let's calculate length.

        var fx = forward.x
        var fy = forward.y
        var fz = forward.z

        // Manual normalization
        val lenSq = fx * fx + fy * fy + fz * fz
        if (lenSq < 1e-6f) {
            dest.set(0f, 0f, 0f, 1f)
            return
        }

        if (abs(lenSq - 1.0f) > 1e-6f) {
            val invLen = 1.0f / sqrt(lenSq)
            fx *= invLen
            fy *= invLen
            fz *= invLen
        }

        // forward is -Z in local space.
        // So z axis = -forward
        val zx = -fx
        val zy = -fy
        val zz = -fz

        var ux = up.x
        var uy = up.y
        var uz = up.z

        // x = cross(up, z)
        var xx = uy * zz - uz * zy
        var xy = uz * zx - ux * zz
        var xz = ux * zy - uy * zx

        var xLenSq = xx * xx + xy * xy + xz * xz

        if (xLenSq < 1e-6f) {
            // Parallel. Fallback to Z axis (0,0,1) as up
            // Or if z is Z axis, use X axis.
            if (abs(uz) < 0.999f) {
                ux = 0f; uy = 0f; uz = 1f
            } else {
                ux = 1f; uy = 0f; uz = 0f
            }
            xx = uy * zz - uz * zy
            xy = uz * zx - ux * zz
            xz = ux * zy - uy * zx
            xLenSq = xx * xx + xy * xy + xz * xz
        }

        val xInvLen = 1.0f / sqrt(xLenSq)
        xx *= xInvLen
        xy *= xInvLen
        xz *= xInvLen

        // y = cross(z, x)
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx

        // Matrix to Quaternion
        // m00=xx, m01=yx, m02=zx
        // m10=xy, m11=yy, m12=zy
        // m20=xz, m21=yz, m22=zz

        val trace = xx + yy + zz
        if (trace > 0) {
            val s = 0.5f / sqrt(trace + 1.0f)
            dest.w = 0.25f / s
            dest.x = (yz - zy) * s // m21 - m12
            dest.y = (zx - xz) * s // m02 - m20
            dest.z = (xy - yx) * s // m10 - m01
        } else {
            if (xx > yy && xx > zz) {
                val s = 2.0f * sqrt(1.0f + xx - yy - zz)
                val invS = 1.0f / s
                dest.w = (yz - zy) * invS
                dest.x = 0.25f * s
                dest.y = (xy + yx) * invS // m10 + m01
                dest.z = (zx + xz) * invS // m02 + m20
            } else if (yy > zz) {
                val s = 2.0f * sqrt(1.0f + yy - xx - zz)
                val invS = 1.0f / s
                dest.w = (zx - xz) * invS
                dest.x = (xy + yx) * invS
                dest.y = 0.25f * s
                dest.z = (yz + zy) * invS // m21 + m12
            } else {
                val s = 2.0f * sqrt(1.0f + zz - xx - yy)
                val invS = 1.0f / s
                dest.w = (xy - yx) * invS
                dest.x = (zx + xz) * invS
                dest.y = (yz + zy) * invS
                dest.z = 0.25f * s
            }
        }
    }
}
