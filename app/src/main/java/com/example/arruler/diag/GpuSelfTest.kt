package com.example.arruler.diag

import android.content.Context
import android.opengl.GLES20
import com.example.arruler.gpu.GpuBench
import com.example.arruler.gpu.GpuBenchResult
import com.example.arruler.gpu.GpuContext
import com.example.arruler.gpu.GpuDenoise
import com.example.arruler.gpu.GpuFixtures
import com.example.arruler.gpu.GpuGate
import com.example.arruler.gpu.GpuImageOps
import com.example.arruler.gpu.GpuKernel
import com.example.arruler.gpu.GpuRansac
import com.example.arruler.gpu.GpuRun
import com.example.arruler.gpu.GpuTextureBake
import com.example.arruler.gpu.GpuVerification
import com.example.arruler.gpu.KernelCost
import com.example.arruler.gpu.TexelMapBuilder
import com.example.arruler.objscan.ObjectDenoise
import com.example.arruler.texture.Sharpness
import com.example.arruler.texture.SpinRoi
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Outcome of one kernel's equivalence check on this device. Times in ms; [units] in [GpuKernel.unit] of the timing run. */
data class KernelResult(
    val kernel: GpuKernel,
    val pass: Boolean,
    /** Largest GPU-vs-CPU difference seen, in [errorUnit]; null when the check could not run. */
    val maxError: Double?,
    val tolerance: Double,
    val errorUnit: String,
    val cpuMs: Double?,
    val gpuMs: Double?,
    val units: Long,
    /** Why it failed (or a remark). */
    val note: String?,
) {
    val speedup: Double? get() = if (cpuMs != null && gpuMs != null && gpuMs > 0.0) cpuMs / gpuMs else null

    fun line(): String {
        val err = if (maxError == null) "no result" else "max err ${fmt(maxError)} $errorUnit (tol ${fmt(tolerance)})"
        val t = if (cpuMs != null && gpuMs != null) ", cpu ${f1(cpuMs)} ms, gpu ${f1(gpuMs)} ms, x${f1(speedup ?: 0.0)}" else ""
        return "${kernel.name} ${if (pass) "PASS" else "FAIL"}: $err$t" + (note?.let { " [$it]" } ?: "")
    }

    companion object {
        fun fmt(v: Double): String = when {
            v.isNaN() || v.isInfinite() -> v.toString()
            v == 0.0 -> "0"
            abs(v) < 0.001 -> String.format(Locale.US, "%.1e", v)
            else -> String.format(Locale.US, "%.4g", v)
        }
        fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)
    }
}

/** The whole self-test: GL strings, per-kernel results, the benchmark, the wall time. */
data class GpuSelfTestReport(
    val renderer: String,
    val glVersion: String,
    val glVendor: String,
    val contextNote: String?,
    val results: List<KernelResult>,
    val bench: GpuBenchResult?,
    val totalMs: Double,
    val atMs: Long,
) {
    val passed: Set<GpuKernel> get() = results.filter { it.pass }.map { it.kernel }.toSet()

    /** Cost model from the measured timings; only kernels with both timings get a row. */
    fun costs(): Map<GpuKernel, KernelCost> {
        val fixed = if (bench?.ok == true) bench.fixedMs else 1.0
        val out = LinkedHashMap<GpuKernel, KernelCost>()
        for (r in results) {
            val cpu = r.cpuMs ?: continue
            val gpu = r.gpuMs ?: continue
            if (r.units <= 0) continue
            out[r.kernel] = KernelCost(cpu * 1e6 / r.units, max(0.0, gpu - fixed) * 1e6 / r.units, fixed)
        }
        return out
    }

    fun toVerification(key: String) = GpuVerification(key, renderer, glVersion, passed, costs(), bench?.takeIf { it.ok }?.gpuScore ?: 0.0, atMs)

    /** Report lines (no heading). */
    fun lines(): List<String> {
        val out = ArrayList<String>()
        out += "Self-test: ${passed.size}/${GpuKernel.values().size} kernels PASS, ${String.format(Locale.US, "%.1f", totalMs / 1000.0)} s" + (contextNote?.let { " ($it)" } ?: "")
        for (r in results) out += "  " + r.line()
        bench?.let { out += "  Bench: $it" }
        return out
    }
}

/**
 * The equivalence checks of the instrumented test (same fixtures, same tolerances, see docs/GPU.md) run on the phone itself.
 * Every kernel runs in its own try with a GL error check; a driver exception is a FAIL for that kernel, never a crash.
 * Run it on a worker thread (it blocks ~10-20 s on a phone). [progress] gets (text, 0..1).
 */
object GpuSelfTest {
    const val DENOISE_TOL_MM = 0.1
    const val TEXTURE_TOL_LEVELS = 2.0
    const val RANSAC_TOL = 0.0
    const val NV21_TOL_LEVELS = 1.0
    const val SHARP_TOL_REL = 1e-4
    const val ROI_TOL_LEVELS = 1e-3

    /** PASS only when the GPU really produced the value (not the CPU fallback) and the error is within tolerance. Pure. */
    fun judge(error: Double?, tolerance: Double, usedGpu: Boolean): Boolean =
        usedGpu && error != null && !error.isNaN() && error <= tolerance

    private class Wedged(msg: String) : RuntimeException(msg)

    private class Ctx(val gl: GpuContext) {
        var wedged = false
        fun <T> gpu(run: GpuRun<T>): GpuRun<T> {
            if (run.fallbackReason?.contains("timed out") == true) wedged = true
            return run
        }
        fun clearGl() { gl.call { while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ } } }
        fun checkGl(where: String) { gl.call { gl.checkGl(where) } }
    }

    private fun <T> timed(block: () -> T): Pair<T, Double> {
        val t = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - t) / 1e6
    }

    private fun bestOf(reps: Int, block: () -> Unit): Double {
        var best = Double.MAX_VALUE
        repeat(reps) { best = minOf(best, timed(block).second) }
        return best
    }

    fun run(app: Context, progress: (String, Float) -> Unit = { _, _ -> }): GpuSelfTestReport {
        val t0 = System.nanoTime()
        fun elapsed() = (System.nanoTime() - t0) / 1e6
        val kernels = GpuKernel.values()
        fun failAll(why: String, renderer: String = "", ver: String = "", vendor: String = "") = GpuSelfTestReport(
            renderer.ifEmpty { "n/a" }, ver, vendor, why,
            kernels.map { KernelResult(it, false, null, 0.0, "", null, null, 0, why) }, null, elapsed(), System.currentTimeMillis(),
        )
        progress("Creating the GL context", 0f)
        val gl = try { GpuContext.create(app) } catch (e: Throwable) { null }
            ?: return failAll("EGL context could not be created")
        try {
            val info = gl.info
            if (!gl.computeSupported) return failAll("no ES 3.1 compute (${info.glVersion})", info.glRenderer, info.glVersion, info.glVendor)
            val c = Ctx(gl)
            val results = ArrayList<KernelResult>()
            val checks: List<Pair<GpuKernel, (Ctx) -> KernelResult>> = listOf(
                GpuKernel.DENOISE to ::denoise, GpuKernel.TEXTURE_BAKE to ::texture, GpuKernel.RANSAC to ::ransac,
                GpuKernel.YUV_CONVERT to ::nv21, GpuKernel.SHARPNESS to ::sharpness, GpuKernel.ROI_SIGNATURE to ::roi,
            )
            for ((i, kc) in checks.withIndex()) {
                val (kernel, check) = kc
                progress("Checking ${kernel.name.lowercase().replace('_', ' ')} (${i + 1}/${checks.size})", i / (checks.size + 1f))
                results += if (c.wedged) KernelResult(kernel, false, null, 0.0, "", null, null, 0, "skipped: the GL driver stopped responding")
                else try {
                    c.clearGl()
                    val r = check(c)
                    c.checkGl(kernel.name)
                    r
                } catch (e: Throwable) {
                    if ((e.message ?: "").contains("timed out")) c.wedged = true
                    KernelResult(kernel, false, null, 0.0, "", null, null, 0, "${e.javaClass.simpleName}: ${(e.message ?: "").take(160)}")
                }
            }
            progress("Benchmark (about 1 s)", checks.size / (checks.size + 1f))
            val bench = try { if (c.wedged) null else GpuBench.run(gl) } catch (e: Throwable) { null }
            progress("Done", 1f)
            return GpuSelfTestReport(info.glRenderer, info.glVersion, info.glVendor, null, results, bench, elapsed(), System.currentTimeMillis())
        } finally {
            try { gl.close() } catch (e: Throwable) { /* best effort */ }
        }
    }

    private fun fail(k: GpuKernel, tol: Double, unit: String, note: String) = KernelResult(k, false, null, tol, unit, null, null, 0, note)

    private fun denoise(c: Ctx): KernelResult {
        var worst = 0.0
        var cpuMs = 0.0; var gpuMs = 0.0; var units = 0L
        for ((i, pts) in listOf(GpuFixtures.slab(20_000, 11), GpuFixtures.sparseBox(5_000)).withIndex()) {
            val (cpu, cms) = timed { ObjectDenoise.smooth(pts, 0.012f, 2) }
            val g = c.gpu(GpuDenoise.smooth(c.gl, pts, 0.012f, 2))
            if (!g.usedGpu) return fail(GpuKernel.DENOISE, DENOISE_TOL_MM, "mm", g.fallbackReason ?: "fell back to CPU")
            for (k in cpu.indices) worst = max(worst, abs(cpu[k] - g.value[k]) * 1000.0)
            if (i == 0) {
                cpuMs = cms; units = (pts.size / 3).toLong()
                gpuMs = bestOf(2) { GpuDenoise.smooth(c.gl, pts, 0.012f, 2) }
            }
        }
        return KernelResult(GpuKernel.DENOISE, judge(worst, DENOISE_TOL_MM, true), worst, DENOISE_TOL_MM, "mm", cpuMs, gpuMs, units, null)
    }

    private fun texture(c: Ctx): KernelResult {
        val kfs = listOf(GpuFixtures.keyframe(640, 480, 1), GpuFixtures.keyframe(640, 480, 2), GpuFixtures.keyframe(800, 600, 3))
        var worst = 0.0; var mismatch = 0
        var cpuMs = 0.0; var gpuMs = 0.0; var units = 0L
        for ((side, charts, scale) in listOf(Triple(512, 9, 1.0f), Triple(1024, 16, 0.8f))) {
            val p = GpuFixtures.texelPlan(side, charts, scale, 3, 640, 480)
            val map = TexelMapBuilder.build(p)
            val (cpu, cms) = timed { GpuTextureBake.cpuSample(p, kfs, map) }
            val g = c.gpu(GpuTextureBake.sample(c.gl, p, kfs))
            if (!g.usedGpu) return fail(GpuKernel.TEXTURE_BAKE, TEXTURE_TOL_LEVELS, "levels", g.fallbackReason ?: "fell back to CPU")
            for (i in cpu.indices) {
                if ((cpu[i] == 0) != (g.value[i] == 0)) { mismatch++; continue }
                if (cpu[i] == 0) continue
                for (sh in intArrayOf(0, 8, 16)) worst = max(worst, abs(((cpu[i] shr sh) and 0xFF) - ((g.value[i] shr sh) and 0xFF)).toDouble())
                if ((g.value[i] ushr 24) != 0xFF) worst = max(worst, 255.0)
            }
            if (side == 1024) {
                cpuMs = cms; units = side.toLong() * side
                gpuMs = bestOf(2) { GpuTextureBake.sample(c.gl, p, kfs) }
            }
        }
        val err = if (mismatch > 0) max(worst, 256.0) else worst
        return KernelResult(GpuKernel.TEXTURE_BAKE, judge(err, TEXTURE_TOL_LEVELS, true), err, TEXTURE_TOL_LEVELS, "levels", cpuMs, gpuMs, units,
            if (mismatch > 0) "$mismatch texels filled on one side only" else null)
    }

    private fun ransac(c: Ctx): KernelResult {
        val p = GpuFixtures.room(30_000, 9)
        val idx = IntArray(p.size / 3) { it }
        var worst = 0.0
        for (seed in 1..4) {
            val cpu = GpuRansac.cpuBestPlane(p, idx, idx.size, 300, 4000, 0.02f, 300, kotlin.random.Random(seed))
            val g = c.gpu(GpuRansac.bestPlane(c.gl, p, idx, idx.size, 300, 4000, 0.02f, 300, kotlin.random.Random(seed)))
            if (!g.usedGpu) return fail(GpuKernel.RANSAC, RANSAC_TOL, "plane units", g.fallbackReason ?: "fell back to CPU")
            val gv = g.value
            if (cpu == null || gv == null) { if ((cpu == null) != (gv == null)) worst = Double.POSITIVE_INFINITY; continue }
            for (k in cpu.indices) worst = max(worst, abs(cpu[k] - gv[k]))
        }
        val iters = 1000; val sample = 20000
        val big = GpuFixtures.room(sample, 10)
        val bigIdx = IntArray(sample) { it }
        val cpuMs = bestOf(1) { GpuRansac.cpuBestPlane(big, bigIdx, sample, iters, sample, 0.02f, 300, kotlin.random.Random(1)) }
        val gpuMs = bestOf(2) { GpuRansac.bestPlane(c.gl, big, bigIdx, sample, iters, sample, 0.02f, 300, kotlin.random.Random(1)) }
        return KernelResult(GpuKernel.RANSAC, judge(worst, RANSAC_TOL, true), worst, RANSAC_TOL, "plane units", cpuMs, gpuMs, sample.toLong() * iters, null)
    }

    private fun nv21(c: Ctx): KernelResult {
        val w = 1280; val h = 960
        val nv = GpuFixtures.nv21(w, h)
        val cpu = com.example.arruler.gpu.GpuImageOps.nv21ToArgbCpu(nv, w, h)
        val g = c.gpu(GpuImageOps.nv21ToArgb(c.gl, nv, w, h))
        if (!g.usedGpu) return fail(GpuKernel.YUV_CONVERT, NV21_TOL_LEVELS, "levels", g.fallbackReason ?: "fell back to CPU")
        var worst = 0.0
        for (i in cpu.indices) for (sh in intArrayOf(0, 8, 16)) worst = max(worst, abs(((cpu[i] shr sh) and 0xFF) - ((g.value[i] shr sh) and 0xFF)).toDouble())
        val cpuMs = bestOf(2) { GpuImageOps.nv21ToArgbCpu(nv, w, h) }
        val gpuMs = bestOf(2) { GpuImageOps.nv21ToArgb(c.gl, nv, w, h) }
        return KernelResult(GpuKernel.YUV_CONVERT, judge(worst, NV21_TOL_LEVELS, true), worst, NV21_TOL_LEVELS, "levels", cpuMs, gpuMs, w.toLong() * h, null)
    }

    private fun sharpness(c: Ctx): KernelResult {
        var worst = 0.0
        for ((w, h, rs, ps) in listOf(listOf(1920, 1080, 1920 + 64, 1), listOf(1280, 960, 1280, 1), listOf(640, 480, 1280, 2))) {
            val y = GpuFixtures.lumaPlane(w, h, rs, ps)
            val cpu = Sharpness.laplacianVariance(y, w, h, rs, ps)
            val g = c.gpu(GpuImageOps.laplacianVariance(c.gl, y, w, h, rs, ps))
            if (!g.usedGpu) return fail(GpuKernel.SHARPNESS, SHARP_TOL_REL, "relative", g.fallbackReason ?: "fell back to CPU")
            worst = max(worst, abs(cpu - g.value) / max(1e-9, abs(cpu)))
        }
        val y = GpuFixtures.lumaPlane(1920, 1080, 1920, 1)
        val cpuMs = bestOf(2) { Sharpness.laplacianVariance(y, 1920, 1080, 1920, 1) }
        val gpuMs = bestOf(2) { GpuImageOps.laplacianVariance(c.gl, y, 1920, 1080, 1920, 1) }
        return KernelResult(GpuKernel.SHARPNESS, judge(worst, SHARP_TOL_REL, true), worst, SHARP_TOL_REL, "relative", cpuMs, gpuMs, 1920L * 1080, null)
    }

    private fun roi(c: Ctx): KernelResult {
        val w = 1280; val h = 960
        val y = GpuFixtures.lumaPlane(w, h, w, 1)
        val roi: SpinRoi = SpinRoi.fromHull(GpuFixtures.roiHull(), w, h) ?: return fail(GpuKernel.ROI_SIGNATURE, ROI_TOL_LEVELS, "levels", "fixture ROI not built")
        val cpu = roi.signature(y, w, 1)
        val g = c.gpu(GpuImageOps.roiSignature(c.gl, roi, y, w, 1))
        if (!g.usedGpu) return fail(GpuKernel.ROI_SIGNATURE, ROI_TOL_LEVELS, "levels", g.fallbackReason ?: "fell back to CPU")
        var worst = 0.0
        for (i in cpu.indices) worst = max(worst, abs(cpu[i] - g.value[i]).toDouble())
        val cpuMs = bestOf(5) { roi.signature(y, w, 1) }
        val gpuMs = bestOf(5) { GpuImageOps.roiSignature(c.gl, roi, y, w, 1) }
        return KernelResult(GpuKernel.ROI_SIGNATURE, judge(worst, ROI_TOL_LEVELS, true), worst, ROI_TOL_LEVELS, "levels", cpuMs, gpuMs,
            (roi.x1 - roi.x0).toLong() * (roi.y1 - roi.y0), null)
    }
}
