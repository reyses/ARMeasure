package com.example.arruler.gpu

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.arruler.objscan.ObjectDenoise
import com.example.arruler.texture.Intrinsics
import com.example.arruler.texture.Keyframe
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
import java.util.Random
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
        for ((name, pts) in listOf("slab 20k" to GpuBench.slab(20_000, 11), "slab 60k" to GpuBench.slab(60_000, 12), "sparse 5k (no stride)" to sparseBox(5_000))) {
            val n = pts.size / 3
            val cpu = ObjectDenoise.smooth(pts, 0.012f, 2)
            val g = GpuDenoise.smooth(c, pts, 0.012f, 2)
            assertTrue("$name used GPU: ${g.fallbackReason}", g.usedGpu)
            var worst = 0f; var sum = 0.0
            for (k in cpu.indices) { val d = abs(cpu[k] - g.value[k]); worst = max(worst, d); sum += d }
            say("DENOISE $name n=$n max diff ${worst * 1000f} mm mean diff ${sum / cpu.size * 1000.0} mm")
            assertTrue("$name max diff ${worst * 1000f} mm", worst < 1e-4f)
        }
        val big = GpuBench.slab(60_000, 13)
        val cpuMs = bestMs(1) { ObjectDenoise.smooth(big, 0.012f, 2) }
        val gpuMs = bestMs { GpuDenoise.smooth(c, big, 0.012f, 2) }
        say("TIMING denoise 60k pts x2 iterations: cpu=${"%.1f".format(cpuMs)} ms gpu=${"%.1f".format(gpuMs)} ms")
    }

    /** Points on two crossing noisy faces with a sparse density so most balls stay under the 48-neighbour cap. */
    private fun sparseBox(n: Int): FloatArray {
        val r = Random(21)
        val p = FloatArray(n * 3)
        for (i in 0 until n) {
            val a = r.nextFloat() * 0.5f; val b = r.nextFloat() * 0.3f; val e = (r.nextGaussian() * 0.003).toFloat()
            if (i % 2 == 0) { p[i * 3] = a; p[i * 3 + 1] = b; p[i * 3 + 2] = e } else { p[i * 3] = a; p[i * 3 + 1] = e; p[i * 3 + 2] = b }
        }
        return p
    }

    @Test fun denoiseFallsBackToCpuWithoutContext() {
        val pts = GpuBench.slab(3_000, 5)
        val run = GpuDenoise.smooth(null, pts, 0.012f, 2)
        assertFalse(run.usedGpu)
        assertArrayEquals(ObjectDenoise.smooth(pts, 0.012f, 2), run.value, 0f)
    }

    // ---- texture bake ----

    private fun keyframe(w: Int, h: Int, seed: Long): Keyframe {
        val r = Random(seed)
        val px = IntArray(w * h) {
            val x = it % w; val y = it / w
            (0xFF shl 24) or (((x * 255 / w + r.nextInt(30)) and 0xFF) shl 16) or (((y * 255 / h) and 0xFF) shl 8) or (r.nextInt(256))
        }
        return Keyframe(FloatArray(16), Intrinsics(500f, 500f, w / 2f, h / 2f, w, h), px)
    }

    /** nCharts charts (2 triangles each) laid out on a grid of cells in an atlas, each pointing into one of the keyframes. */
    private fun plan(side: Int, nCharts: Int, scale: Float, views: Int, w: Int, h: Int): TexelPlan {
        val r = Random(3)
        val perRow = Math.ceil(Math.sqrt(nCharts.toDouble())).toInt()
        val cell = side / perRow
        val chartPx = ((cell - 8) / scale).toInt()
        val triUV = FloatArray(nCharts * 12); val bestView = IntArray(nCharts * 2); val chartOf = IntArray(nCharts * 2)
        val minU = FloatArray(nCharts); val minV = FloatArray(nCharts); val rx = IntArray(nCharts); val ry = IntArray(nCharts)
        for (c in 0 until nCharts) {
            val u0 = 2f + r.nextFloat() * (w - chartPx - 6); val v0 = 2f + r.nextFloat() * (h - chartPx - 6)
            val s = chartPx.toFloat()
            val o = c * 12
            val q = floatArrayOf(u0, v0, u0 + s, v0, u0 + s, v0 + s, u0, v0, u0 + s, v0 + s, u0, v0 + s)
            q.copyInto(triUV, o)
            for (t in 0..1) { bestView[c * 2 + t] = c % views; chartOf[c * 2 + t] = c }
            minU[c] = u0; minV[c] = v0
            rx[c] = (c % perRow) * cell; ry[c] = (c / perRow) * cell
        }
        return TexelPlan(side, 2, scale, triUV, bestView, chartOf, minU, minV, rx, ry)
    }

    @Test fun textureSamplingMatchesCpuWithinTwoLevels() {
        val c = gpu()
        val kfs = listOf(keyframe(640, 480, 1), keyframe(640, 480, 2), keyframe(800, 600, 3))
        for ((side, charts, scale) in listOf(Triple(512, 9, 1.0f), Triple(1024, 16, 0.8f), Triple(2048, 36, 1.3f))) {
            val p = plan(side, charts, scale, 3, 640, 480)
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
        val p = plan(2048, 36, 1.3f, 3, 640, 480)
        val map = TexelMapBuilder.build(p)
        val cpuMs = bestMs { GpuTextureBake.cpuSample(p, kfs, map) }
        val gpuMs = bestMs { GpuTextureBake.sample(c, p, kfs) }
        say("TIMING texture sampling 2048x2048 atlas: cpu=${"%.1f".format(cpuMs)} ms gpu(incl. texel map build)=${"%.1f".format(gpuMs)} ms")
    }

    // ---- RANSAC ----

    private fun room(n: Int, seed: Long): FloatArray {
        val r = Random(seed)
        val p = FloatArray(n * 3)
        for (i in 0 until n) when (i % 4) {
            0, 1 -> { p[i * 3] = r.nextFloat() * 4f; p[i * 3 + 1] = (r.nextGaussian() * 0.005).toFloat(); p[i * 3 + 2] = r.nextFloat() * 4f }
            2 -> { p[i * 3] = r.nextFloat() * 4f; p[i * 3 + 1] = r.nextFloat() * 2.5f; p[i * 3 + 2] = (r.nextGaussian() * 0.005).toFloat() }
            else -> { p[i * 3] = r.nextFloat() * 4f; p[i * 3 + 1] = r.nextFloat() * 2.5f; p[i * 3 + 2] = r.nextFloat() * 4f }
        }
        return p
    }

    @Test fun ransacChoosesTheIdenticalPlane() {
        val c = gpu()
        val p = room(60_000, 9)
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
        val r = Random(4)
        val nv21 = ByteArray(w * h * 3 / 2).also { r.nextBytes(it) }
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

    private fun lumaPlane(w: Int, h: Int, rowStride: Int, pixelStride: Int): ByteArray {
        val r = Random(6)
        val y = ByteArray(rowStride * h + pixelStride)
        for (j in 0 until h) for (i in 0 until w) y[j * rowStride + i * pixelStride] = (((i / 8 + j / 8) % 2) * 120 + 60 + r.nextInt(20)).toByte()
        return y
    }

    @Test fun sharpnessMatchesCpu() {
        val c = gpu()
        for ((w, h, rs, ps) in listOf(listOf(1920, 1080, 1920 + 64, 1), listOf(1280, 960, 1280, 1), listOf(640, 480, 1280, 2))) {
            val y = lumaPlane(w, h, rs, ps)
            val cpu = Sharpness.laplacianVariance(y, w, h, rs, ps)
            val g = GpuImageOps.laplacianVariance(c, y, w, h, rs, ps)
            assertTrue("GPU used: ${g.fallbackReason}", g.usedGpu)
            val rel = abs(cpu - g.value) / max(1e-9, abs(cpu))
            say("SHARPNESS ${w}x$h stride $rs/$ps cpu=$cpu gpu=${g.value} rel diff=$rel")
            assertTrue("rel $rel", rel < 1e-4)
        }
        val y = lumaPlane(1920, 1080, 1920, 1)
        val cpuMs = bestMs { Sharpness.laplacianVariance(y, 1920, 1080, 1920, 1) }
        val gpuMs = bestMs { GpuImageOps.laplacianVariance(c, y, 1920, 1080, 1920, 1) }
        say("TIMING laplacian variance 1920x1080: cpu=${"%.1f".format(cpuMs)} ms gpu=${"%.1f".format(gpuMs)} ms")
    }

    @Test fun roiSignatureMatchesCpu() {
        val c = gpu()
        val w = 1280; val h = 960
        val y = lumaPlane(w, h, w, 1)
        val hull = listOf(floatArrayOf(300f, 200f), floatArrayOf(900f, 220f), floatArrayOf(950f, 700f), floatArrayOf(350f, 740f))
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
