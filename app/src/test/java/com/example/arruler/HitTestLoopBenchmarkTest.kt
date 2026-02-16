package com.example.arruler

import org.junit.Test
import kotlin.system.measureNanoTime

class HitTestLoopBenchmarkTest {

    class FakePose {
        val tx: Float = 0f
        val ty: Float = 0f
        val tz: Float = 0f
    }

    class FakeHitResult {
        val hitPose: FakePose
            get() = FakePose() // Simulates allocation
    }

    class FakeFrame {
        fun hitTest(x: Float, y: Float): List<FakeHitResult> {
             // Simulate work: e.g. iterate through some points and check distance
             val results = ArrayList<FakeHitResult>()
             repeat(5) { // Simulate finding 5 hits
                 results.add(FakeHitResult())
             }
             return results
        }
    }

    @Test
    fun benchmarkHitTestLoop() {
        val frame = FakeFrame()
        val iterations = 1_000_000

        // Warmup
        repeat(100_000) {
            baselineLoop(frame)
            optimizedLoop(frame)
        }

        val baselineTime = measureNanoTime {
            repeat(iterations) {
                baselineLoop(frame)
            }
        }

        val optimizedTime = measureNanoTime {
            repeat(iterations) {
                optimizedLoop(frame)
            }
        }

        println("Baseline Time: ${baselineTime / 1_000_000} ms")
        println("Optimized Time: ${optimizedTime / 1_000_000} ms")
        if (baselineTime > 0) {
            val improvement = (baselineTime - optimizedTime).toDouble() / baselineTime * 100
            println("Improvement: %.2f%%".format(improvement))
        }
    }

    private fun baselineLoop(frame: FakeFrame) {
        // Update Reticle
        val hits1 = frame.hitTest(0.5f, 0.5f)
        val hit1 = hits1.firstOrNull()
        blackhole(hit1?.hitPose)

        // Update Measurement
        val hits2 = frame.hitTest(0.5f, 0.5f)
        val hit2 = hits2.firstOrNull()
        blackhole(hit2?.hitPose)
    }

    private fun optimizedLoop(frame: FakeFrame) {
        // Shared Hit Test
        val hits = frame.hitTest(0.5f, 0.5f)
        val hit = hits.firstOrNull()
        val pose = hit?.hitPose

        // Update Reticle
        blackhole(pose)

        // Update Measurement
        blackhole(pose)
    }

    private var sink: Any? = null
    private fun blackhole(obj: Any?) {
        sink = obj
    }
}
