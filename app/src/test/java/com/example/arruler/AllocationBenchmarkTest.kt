package com.example.arruler

import org.junit.Test
import kotlin.system.measureNanoTime

class AllocationBenchmarkTest {

    // Mock Pose to simulate ARCore's Pose behavior regarding translation access
    class MockPose(val x: Float, val y: Float, val z: Float) {

        // Simulates Pose.getTranslation() which returns a new array
        val translation: FloatArray
            get() = floatArrayOf(x, y, z)

        // Simulates Pose.getTranslation(float[] dest, int offset) which reuses array
        fun getTranslation(dest: FloatArray, offset: Int) {
            dest[offset] = x
            dest[offset + 1] = y
            dest[offset + 2] = z
        }
    }

    @Test
    fun benchmarkTranslationAccess() {
        val iterations = 10_000_000
        val pose = MockPose(1.0f, 2.0f, 3.0f)
        val reusedBuffer = FloatArray(3)

        // Warmup
        repeat(100_000) {
            baselineOperation(pose)
            optimizedOperation(pose, reusedBuffer)
        }

        val baselineTime = measureNanoTime {
            repeat(iterations) {
                baselineOperation(pose)
            }
        }

        val optimizedTime = measureNanoTime {
            repeat(iterations) {
                optimizedOperation(pose, reusedBuffer)
            }
        }

        println("Baseline Time (Allocation): ${baselineTime / 1_000_000} ms")
        println("Optimized Time (Reuse): ${optimizedTime / 1_000_000} ms")
        println("Improvement: ${(baselineTime - optimizedTime).toDouble() / baselineTime * 100}%")

        if (optimizedTime >= baselineTime) {
             println("WARNING: Optimization did not improve performance.")
        }
    }

    private fun baselineOperation(pose: MockPose) {
        val pos = pose.translation
        val x = pos[0]
        val y = pos[1]
        val z = pos[2]
        blackhole(x + y + z)
    }

    private fun optimizedOperation(pose: MockPose, buffer: FloatArray) {
        pose.getTranslation(buffer, 0)
        val x = buffer[0]
        val y = buffer[1]
        val z = buffer[2]
        blackhole(x + y + z)
    }

    // To prevent JIT from optimizing away the calculations
    private var sink: Any? = null
    private fun blackhole(obj: Any?) {
        sink = obj
    }
}
