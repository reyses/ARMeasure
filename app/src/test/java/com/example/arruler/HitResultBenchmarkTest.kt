package com.example.arruler

import org.junit.Test
import kotlin.system.measureNanoTime

class HitResultBenchmarkTest {

    // Simulates com.google.ar.core.Pose
    class FakePose {
        // Simulate some fields
        val x: Float = 1.0f
        val y: Float = 2.0f
        val z: Float = 3.0f
    }

    // Simulates com.google.ar.core.HitResult
    class FakeHitResult {
        // Simulate "getHitPose" creating a new object every time
        val hitPose: FakePose
            get() = FakePose()

        fun checkCondition(pose: FakePose): Boolean {
            // Simulate work done in trackable.isPoseInPolygon(hitResult.hitPose)
            return pose.x > 0
        }
    }

    @Test
    fun benchmarkHitPoseAccess() {
        val iterations = 10_000_000
        val hitResult = FakeHitResult()

        // Warmup
        repeat(100_000) {
            baselineOperation(hitResult)
            optimizedOperation(hitResult)
        }

        // Baseline: Call it twice (mimics current implementation)
        val baselineTime = measureNanoTime {
            repeat(iterations) {
                baselineOperation(hitResult)
            }
        }

        // Optimized: Call it once (mimics optimized implementation)
        val optimizedTime = measureNanoTime {
            repeat(iterations) {
                optimizedOperation(hitResult)
            }
        }

        println("Baseline Time: ${baselineTime / 1_000_000} ms")
        println("Optimized Time: ${optimizedTime / 1_000_000} ms")
        if (baselineTime > 0) {
            println("Improvement: ${(baselineTime - optimizedTime).toDouble() / baselineTime * 100}%")
        }
    }

    private fun baselineOperation(hitResult: FakeHitResult) {
        // First access (inside performHitTest loop)
        val pose1 = hitResult.hitPose
        if (hitResult.checkCondition(pose1)) {
             // Return hitResult only
        }

        // Second access (inside updateLiveMeasurement)
        val pose2 = hitResult.hitPose
        blackhole(pose2)
    }

    private fun optimizedOperation(hitResult: FakeHitResult) {
        // Single access (inside performHitTest loop)
        val pose1 = hitResult.hitPose
        if (hitResult.checkCondition(pose1)) {
             // Return Pair(hitResult, pose1)
        }

        // Reuse (inside updateLiveMeasurement)
        val pose2 = pose1
        blackhole(pose2)
    }

    private var sink: Any? = null
    private fun blackhole(obj: Any?) {
        sink = obj
    }
}
