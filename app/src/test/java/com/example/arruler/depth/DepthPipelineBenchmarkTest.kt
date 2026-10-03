package com.example.arruler.depth

import org.junit.Ignore
import org.junit.Test
import kotlin.random.Random

/** Manual JVM micro-benchmark: remove @Ignore (or run with the class filter) to print ms/frame. */
class DepthPipelineBenchmarkTest {

    @Ignore("manual benchmark")
    @Test fun unprojectAndVoxelInsertThirtyFrames160x120() {
        val k = DepthMath.scaleIntrinsics(Intrinsics(400f, 400f, 320f, 240f, 640, 480), 160, 120)
        val rnd = Random(1)
        val w = 160; val h = 120
        val tmp = FloatArray(3)
        val world = FloatArray(3)
        fun runFrames(n: Int, cloud: VoxelCloud): Double {
            val t0 = System.nanoTime()
            for (f in 0 until n) {
                val pose = DepthMath.yawMatrix(f * 0.01f, f * 0.005f, 1.4f, 0f)
                for (v in 0 until h) for (u in 0 until w) {
                    val mm = 1500 + (u + v) * 4 + rnd.nextInt(10)
                    DepthMath.unproject(u.toFloat(), v.toFloat(), mm, k, tmp)
                    DepthMath.transformPoint(pose, tmp[0], tmp[1], tmp[2], world)
                    cloud.add(world[0], world[1], world[2])
                }
            }
            return (System.nanoTime() - t0) / 1e6 / n
        }
        runFrames(10, VoxelCloud())            // warm-up the JIT
        val cloud = VoxelCloud()
        val ms = runFrames(30, cloud)
        println("BENCH depth pipeline: %.2f ms/frame (30 frames of 160x120 = %d px, %d voxels)".format(ms, w * h, cloud.count))
    }
}
