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

    // Simulating ARCore classes for HitTest benchmark
    data class MockHitResult(val trackable: Any)
    data class MockPlane(val isValid: Boolean)

    @Test
    fun benchmarkHitTestIteration() {
        val hits = ArrayList<MockHitResult>()
        for (i in 0 until 10) {
            val isPlane = i % 2 == 0
            val isValid = i == 5
            val trackable = if (isPlane) MockPlane(isValid) else Any()
            hits.add(MockHitResult(trackable))
        }

        val iterations = 1_000_000

        repeat(100_000) {
            baselineHitTest(hits)
            optimizedHitTest(hits)
        }

        val baselineTime = measureNanoTime {
            repeat(iterations) {
                baselineHitTest(hits)
            }
        }

        val optimizedTime = measureNanoTime {
            repeat(iterations) {
                optimizedHitTest(hits)
            }
        }

        println("HitTest Baseline Time: ${baselineTime / 1_000_000.0} ms")
        println("HitTest Optimized Time: ${optimizedTime / 1_000_000.0} ms")

        val improvement = (baselineTime - optimizedTime).toDouble() / baselineTime * 100
        println("HitTest Improvement: ${String.format("%.2f", improvement)}%")

        if (optimizedTime >= baselineTime) {
            println("WARNING: HitTest Optimization did not improve performance.")
        }
    }

    private fun baselineHitTest(hits: List<MockHitResult>): MockHitResult? {
        return hits.firstOrNull { hitResult ->
            val trackable = hitResult.trackable
            trackable is MockPlane && trackable.isValid
        }
    }

    private fun optimizedHitTest(hits: List<MockHitResult>): MockHitResult? {
        val size = hits.size
        for (i in 0 until size) {
            val hitResult = hits[i]
            val trackable = hitResult.trackable
            if (trackable is MockPlane && trackable.isValid) {
                return hitResult
            }
        }
        return null
    }
}
