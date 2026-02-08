package com.example.arruler

import com.google.ar.sceneform.math.Vector3
import org.junit.Test
import kotlin.math.sqrt
import kotlin.system.measureNanoTime

class PerformanceBenchmarkTest {

    @Test
    fun benchmarkDistanceCalculation() {
        val iterations = 10_000_000
        val start = Vector3(1.5f, 2.5f, 3.5f)
        val end = Vector3(4.5f, 6.5f, 8.5f)

        // Warmup
        repeat(100_000) {
            baselineOperation(start, end)
            optimizedOperation(start, end)
        }

        val baselineTime = measureNanoTime {
            repeat(iterations) {
                baselineOperation(start, end)
            }
        }

        val optimizedTime = measureNanoTime {
            repeat(iterations) {
                optimizedOperation(start, end)
            }
        }

        println("Baseline Time: ${baselineTime / 1_000_000} ms")
        println("Optimized Time: ${optimizedTime / 1_000_000} ms")
        println("Improvement: ${(baselineTime - optimizedTime).toDouble() / baselineTime * 100}%")

        if (optimizedTime >= baselineTime) {
             println("WARNING: Optimization did not improve performance.")
        }
    }

    private fun baselineOperation(start: Vector3, end: Vector3) {
        // Current implementation in updateLiveMeasurement
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)

        // Current implementation in drawTemporaryLine
        val difference = Vector3.subtract(end, start)
        val direction = difference.normalized() // Internal sqrt
        val length = difference.length() // Internal sqrt

        // Avoid optimizing away
        blackhole(distance)
        blackhole(direction)
        blackhole(length)
    }

    private fun optimizedOperation(start: Vector3, end: Vector3) {
        // Current implementation in updateLiveMeasurement
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)

        // Optimized implementation in drawTemporaryLine
        val difference = Vector3.subtract(end, start)
        // If distance is > 0
        val direction = if (distance > 0) difference.scaled(1.0f / distance) else Vector3.zero()
        val length = distance

        // Avoid optimizing away
        blackhole(distance)
        blackhole(direction)
        blackhole(length)
    }

    // To prevent JIT from optimizing away the calculations
    private var sink: Any? = null
    private fun blackhole(obj: Any?) {
        sink = obj
    }

    @Test
    fun benchmarkFormatter() {
        val formatter = DistanceFormatter()
        val iterations = 10_000_000
        val value1 = 12.3456f
        val value2 = 12.3457f // Should result in same formatted string "12.3"
        val unit = "cm"

        // Warmup
        repeat(100_000) {
            formatter.format(value1, unit)
            formatter.format(value2, unit)
        }

        // Scenario 1: Same value repeatedly (Ideal for caching)
        val timeSameValue = measureNanoTime {
            repeat(iterations) {
                formatter.format(value1, unit)
            }
        }

        // Scenario 2: Varying value but same output (Ideal for caching)
        val timeVaryingValueSameOutput = measureNanoTime {
            repeat(iterations) {
                // Alternating between value1 and value2
                formatter.format(if (it % 2 == 0) value1 else value2, unit)
            }
        }

        // Scenario 3: Varying value and different output (Cache miss)
        val timeVaryingOutput = measureNanoTime {
            repeat(iterations) {
                formatter.format(it.toFloat(), unit)
            }
        }

        println("Formatter - Time Same Value: ${timeSameValue / 1_000_000} ms")
        println("Formatter - Time Varying Value (Same Output): ${timeVaryingValueSameOutput / 1_000_000} ms")
        println("Formatter - Time Varying Output: ${timeVaryingOutput / 1_000_000} ms")
    }
}
