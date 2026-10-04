package com.example.arruler.gpu

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.arruler.objscan.ObjectDenoise
import com.example.arruler.texture.Sharpness
import com.example.arruler.texture.SpinRoi
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * GPU vs CPU on synthetic fixtures, one test per kernel, with timing printouts (logcat tag GPUTEST and stdout).
 * Skipped (assumption) when the device has no ES 3.1 compute. Timings from an emulator are NOT phone timings.
 */
@RunWith(AndroidJUnit4::class)
class GpuKernelsInstrumentedTest {
    companion object {
        private var ctx: GpuContext? = null

        @BeforeClass @JvmStatic fun setUp() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            ctx = GpuContext.create(app)
            say("INFO ${ctx?.info ?: "EGL context could not be created"}")
        }

        @AfterClass @JvmStatic fun tearDown() { ctx?.close(); ctx = null }

        fun say(s: String) { Log.i("GPUTEST", s); println("GPUTEST $s") }

        /** Best of [reps] wall times (ms) after one warm-up. */
        fun bestMs(reps: Int = 3, block: () -> Unit): Double {
            block()
            var best = Double.MAX_VALUE
            repeat(reps) { val t = System.nanoTime(); block(); best = minOf(best, (System.nanoTime() - t) / 1e6) }
            return best
        }
    }

    private fun gpu(): GpuContext { assumeTrue("no ES 3.1 compute on this device", ctx?.computeSupported == true); return ctx!! }

    @Test fun contextProbeReportsTheDevice() {
        val c = ctx
        assertNotNull("EGL context", c)
        val i = c!!.info
        say("PROBE esVersion=${i.esMajor}.${i.esMinor} compute=${i.computeSupported} renderer=${i.glRenderer} vulkan=${i.vulkanText}")
        assertTrue(i.glVersion.startsWith("OpenGL ES"))
        assertTrue(i.glRenderer.isNotEmpty())
        if (i.computeSupported) {
            assertTrue(i.maxWorkGroupInvocations >= 128)
            assertTrue(i.maxSsboBytes >= 16L * 1024 * 1024)
        }
    }

    @Test fun denoiseMatchesCpuWithinTenthOfAMillimetre() {
        val c = gpu()
        for ((name, pts) in listOf("slab 20k" to GpuFixtures.slab(20_000, 11), "slab 60k" to GpuFixtures.slab(60_000, 12), "sparse 5k (no stride)" to GpuFixtures.sparseBox(5_000))) {
            val n = pts.size / 3
            val cpu = ObjectDenoise.smooth(pts, 0.012f, 2)
            val g = GpuDenoise.smooth(c, pts, 0.012f, 2)
            assertTrue("$name used GPU: ${g.fallbackReason}", g.usedGpu)
            var worst = 0f; var sum = 0.0
            for (k in cpu.indices) { val d = abs(cpu[k] - g.value[k]); worst = max(worst, d); sum += d }
            say("DENOISE $name n=$n max diff ${worst * 1000f} mm mean diff ${sum / cpu.size * 1000.0} mm")
            assertTrue("$name max diff ${worst * 1000f} mm", worst < 1e-4f)
        }
        val big = GpuFixtures.slab(60_000, 13)
        val cpuMs = bestMs(1) { ObjectDenoise.smooth(big, 0.012f, 2) }
        val gpuMs = bestMs { GpuDenoise.smooth(c, big, 0.012f, 2) }
        say("TIMING denoise 60k pts x2 iterations: cpu=${"%.1f".format(cpuMs)} ms gpu=${"%.1f".format(gpuMs)} ms")
    }

    @Test fun denoiseFallsBackToCpuWithoutContext() {
        val pts = GpuFixtures.slab(3_000, 5)
        val run = GpuDenoise.smooth(null, pts, 0.012f, 2)
        assertFalse(run.usedGpu)
        assertArrayEquals(ObjectDenoise.smooth(pts, 0.012f, 2), run.value, 0f)
    }

    // ---- texture bake ----

    @Test fun textureSamplingMatchesCpuWithinTwoLevels() {
        val c = gpu()
        val kfs = listOf(GpuFixtures.keyframe(640, 480, 1), GpuFixtures.keyframe(640, 480, 2), GpuFixtures.keyframe(800, 600, 3))
        for ((side, charts, scale) in listOf(Triple(512, 9, 1.0f), Triple(1024, 16, 0.8f), Triple(2048, 36, 1.3f))) {
            val p = GpuFixtures.texelPlan(side, charts, scale, 3, 640, 480)
            val cpu = GpuTextureBake.cpuSample(p, kfs)
            val g = GpuTextureBake.sample(c, p, kfs)
            assertTrue("GPU used: ${g.fallbackReason}", g.usedGpu)
            var worst = 0; var filled = 0
            for (i in cpu.indices) {
                if ((cpu[i] == 0) != (g.value[i] == 0)) throw AssertionError("filled mismatch at $i")
                if (cpu[i] == 0) continue
                filled++
                for (sh in intArrayOf(0, 8, 16)) worst = max(worst, abs(((cpu[i] shr sh) and 0xFF) - ((g.value[i] shr sh) and 0xFF)))
                assertEquals(0xFF, (g.value[i] ushr 24))
            }
            say("TEXBAKE atlas ${side}x$side charts=$charts filled=$filled worst channel diff=$worst levels")
            assertTrue("worst diff $worst", worst <= 2)
        }
        val p = GpuFixtures.texelPlan(2048, 36, 1.3f, 3, 640, 480)
        val map = TexelMapBuilder.build(p)
        val cpuMs = bestMs { GpuTextureBake.cpuSample(p, kfs, map) }
        val gpuMs = bestMs { GpuTextureBake.sample(c, p, kfs) }
        say("TIMING texture sampling 2048x2048 atlas: cpu=${"%.1f".format(cpuMs)} ms gpu(incl. texel map build)=${"%.1f".format(gpuMs)} ms")
    }

    // ---- RANSAC ----

    @Test fun ransacChoosesTheIdenticalPlane() {
        val c = gpu()
        val p = GpuFixtures.room(60_000, 9)
        val idx = IntArray(p.size / 3) { it }
        for (seed in 1..8) {
            val cpu = GpuRansac.cpuBestPlane(p, idx, idx.size, 300, 4000, 0.02f, 300, kotlin.random.Random(seed))
            val g = GpuRansac.bestPlane(c, p, idx, idx.size, 300, 4000, 0.02f, 300, kotlin.random.Random(seed))
            assertTrue("GPU used: ${g.fallbackReason}", g.usedGpu)
            assertNotNull(cpu)
            assertArrayEquals("seed $seed", cpu!!, g.value!!, 0.0)
        }
        say("RANSAC 8 seeds: GPU plane identical to CPU plane (double equality)")
        val cpuMs = bestMs { GpuRansac.cpuBestPlane(p, idx, idx.size, 1000, 20000, 0.02f, 300, kotlin.random.Random(1)) }
        val gpuMs = bestMs { GpuRansac.bestPlane(c, p, idx, idx.size, 1000, 20000, 0.02f, 300, kotlin.random.Random(1)) }
        say("TIMING ransac 1000 hypotheses x 20000 pts: cpu=${"%.1f".format(cpuMs)} ms gpu=${"%.1f".format(gpuMs)} ms")
    }

    // ---- image ops ----

    @Test fun nv21ToArgbMatchesCpu() {
        val c = gpu()
        val w = 1280; val h = 960
        val nv21 = GpuFixtures.nv21(w, h)
        val cpu = GpuImageOps.nv21ToArgbCpu(nv21, w, h)
        val g = GpuImageOps.nv21ToArgb(c, nv21, w, h)
        assertTrue("GPU used: ${g.fallbackReason}", g.usedGpu)
        var worst = 0
        for (i in cpu.indices) for (sh in intArrayOf(0, 8, 16)) worst = max(worst, abs(((cpu[i] shr sh) and 0xFF) - ((g.value[i] shr sh) and 0xFF)))
        say("NV21 ${w}x$h worst channel diff=$worst levels")
        assertTrue(worst <= 1)
        val cpuMs = bestMs { GpuImageOps.nv21ToArgbCpu(nv21, w, h) }
        val gpuMs = bestMs { GpuImageOps.nv21ToArgb(c, nv21, w, h) }
        say("TIMING nv21->argb ${w}x$h: cpu=${"%.1f".format(cpuMs)} ms gpu=${"%.1f".format(gpuMs)} ms")
    }

    @Test fun sharpnessMatchesCpu() {
        val c = gpu()
        for ((w, h, rs, ps) in listOf(listOf(1920, 1080, 1920 + 64, 1), listOf(1280, 960, 1280, 1), listOf(640, 480, 1280, 2))) {
            val y = GpuFixtures.lumaPlane(w, h, rs, ps)
            val cpu = Sharpness.laplacianVariance(y, w, h, rs, ps)
            val g = GpuImageOps.laplacianVariance(c, y, w, h, rs, ps)
            assertTrue("GPU used: ${g.fallbackReason}", g.usedGpu)
            val rel = abs(cpu - g.value) / max(1e-9, abs(cpu))
            say("SHARPNESS ${w}x$h stride $rs/$ps cpu=$cpu gpu=${g.value} rel diff=$rel")
            assertTrue("rel $rel", rel < 1e-4)
        }
        val y = GpuFixtures.lumaPlane(1920, 1080, 1920, 1)
        val cpuMs = bestMs { Sharpness.laplacianVariance(y, 1920, 1080, 1920, 1) }
        val gpuMs = bestMs { GpuImageOps.laplacianVariance(c, y, 1920, 1080, 1920, 1) }
        say("TIMING laplacian variance 1920x1080: cpu=${"%.1f".format(cpuMs)} ms gpu=${"%.1f".format(gpuMs)} ms")
    }

    @Test fun roiSignatureMatchesCpu() {
        val c = gpu()
        val w = 1280; val h = 960
        val y = GpuFixtures.lumaPlane(w, h, w, 1)
        val hull = GpuFixtures.roiHull()
        val roi = SpinRoi.fromHull(hull, w, h)
        assertNotNull(roi)
        val cpu = roi!!.signature(y, w, 1)
        val g = GpuImageOps.roiSignature(c, roi, y, w, 1)
        assertTrue("GPU used: ${g.fallbackReason}", g.usedGpu)
        var worst = 0f
        for (i in cpu.indices) worst = max(worst, abs(cpu[i] - g.value[i]))
        say("ROI signature ${roi.grid}x${roi.grid} worst diff=$worst luma levels")
        assertTrue(worst < 1e-3f)
        val cpuMs = bestMs(5) { roi.signature(y, w, 1) }
        val gpuMs = bestMs(5) { GpuImageOps.roiSignature(c, roi, y, w, 1) }
        say("TIMING roi signature: cpu=${"%.2f".format(cpuMs)} ms gpu=${"%.2f".format(gpuMs)} ms")
    }

    // ---- bench ----

    @Test fun benchRunsInAboutASecondAndBuildsAProfile() {
        val c = gpu()
        val b = GpuBench.run(c)
        say("BENCH $b")
        assertTrue(b.reason, b.ok)
        val prof = GpuProfile.fromBench(b)
        say("PROFILE $prof")
        assertTrue(prof.available)
    }
}
