package com.example.arruler

import org.junit.Test
import kotlin.system.measureNanoTime

class HitTestOptimizationBenchmark {

    // Mock classes to simulate ARCore behavior
    class MockPose {
        val tx: Float = 0f
    }

    class MockPlane {
        fun isPoseInPolygon(pose: MockPose): Boolean {
            return true
        }
    }

    class MockHitResult(val trackable: Any, val isPlane: Boolean) {
        val hitPose: MockPose
            get() = MockPose() // Simulates allocation on property access
    }

    // Baseline implementation
    fun performHitTestBaseline(hits: List<MockHitResult>): Pair<MockHitResult, MockPose>? {
        for (hitResult in hits) {
            val trackable = hitResult.trackable
            val pose = hitResult.hitPose // Eager allocation
            if (trackable is MockPlane && trackable.isPoseInPolygon(pose)) {
                return Pair(hitResult, pose)
            }
        }
        return null
    }

    // Optimization 1: Defer Pose allocation
    fun performHitTestDeferPose(hits: List<MockHitResult>): Pair<MockHitResult, MockPose>? {
        for (hitResult in hits) {
            val trackable = hitResult.trackable
            if (trackable is MockPlane) {
                val pose = hitResult.hitPose
                if (trackable.isPoseInPolygon(pose)) {
                    return Pair(hitResult, pose)
                }
            }
        }
        return null
    }

    // Optimization 2: Return HitResult only (and defer pose)
    fun performHitTestReturnHitResult(hits: List<MockHitResult>): MockHitResult? {
        for (hitResult in hits) {
            val trackable = hitResult.trackable
            if (trackable is MockPlane) {
                val pose = hitResult.hitPose
                if (trackable.isPoseInPolygon(pose)) {
                    return hitResult
                }
            }
        }
        return null
    }

    @Test
    fun benchmarkHitTest() {
        // Setup scenarios
        val plane = MockPlane()
        val notPlane = Any()

        // Scenario A: Hit is a Plane (Common case when tracking)
        val hitsPlane = listOf(MockHitResult(plane, true))

        // Scenario B: Hit is NOT a Plane (e.g. Point) followed by Plane
        // Realistically hitTest returns sorted list.
        val hitsMixed = listOf(MockHitResult(notPlane, false), MockHitResult(plane, true))

        val iterations = 1_000_000

        println("--- Benchmarking Scenario A: Hit is a Plane ---")
        measureScenario(hitsPlane, iterations, "Plane")

        println("\n--- Benchmarking Scenario B: Hit is Not Plane then Plane ---")
        measureScenario(hitsMixed, iterations, "Mixed")
    }

    private fun measureScenario(hits: List<MockHitResult>, iterations: Int, label: String) {
        // Warmup
        repeat(100_000) {
            performHitTestBaseline(hits)
            performHitTestDeferPose(hits)
            performHitTestReturnHitResult(hits)
        }

        val timeBaseline = measureNanoTime {
            repeat(iterations) {
                val result = performHitTestBaseline(hits)
                // Simulate usage in updateReticle (ignore pose)
                if (result != null) {
                    val r = result.first
                }
            }
        }

        val timeDefer = measureNanoTime {
            repeat(iterations) {
                val result = performHitTestDeferPose(hits)
                if (result != null) {
                    val r = result.first
                }
            }
        }

        val timeReturnHit = measureNanoTime {
            repeat(iterations) {
                val result = performHitTestReturnHitResult(hits)
                if (result != null) {
                    val r = result
                }
            }
        }

        println("Baseline ($label): ${timeBaseline / 1_000_000} ms")
        println("DeferPose ($label): ${timeDefer / 1_000_000} ms")
        println("ReturnHit ($label): ${timeReturnHit / 1_000_000} ms")
    }
}
