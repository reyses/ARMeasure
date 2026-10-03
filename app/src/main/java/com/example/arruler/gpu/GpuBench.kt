package com.example.arruler.gpu

import com.example.arruler.objscan.ObjectDenoise
import java.util.Random

/**
 * Result of the ~1 s GPU micro-benchmark. Times in ms, throughput in million points per second, costs in ns per point.
 * [gpuScore] = 60 000 points / [gpuMs] (one denoise iteration including the CPU grid build, upload and readback).
 */
class GpuBenchResult(
    val ok: Boolean,
    val reason: String?,
    val points: Int,
    val gpuMs: Double,
    /** CPU time extrapolated to [points] from a smaller run at the same point density. */
    val cpuMsEstimated: Double,
    /** Round trip of a 64-point run (shader already compiled): the fixed GPU cost per call. */
    val fixedMs: Double,
    /** First-call cost: shader compile + link. */
    val compileMs: Double,
    val totalMs: Double,
) {
    val gpuScore: Double get() = if (ok && gpuMs > 0) points / gpuMs / 1000.0 else 0.0
    val speedup: Double get() = if (ok && gpuMs > 0) cpuMsEstimated / gpuMs else 0.0
    val cpuNsPerPoint: Double get() = cpuMsEstimated * 1e6 / points
    val gpuNsPerPoint: Double get() = maxOf(0.0, (gpuMs - fixedMs)) * 1e6 / points

    override fun toString() =
        if (!ok) "GpuBench(failed: $reason)"
        else "GpuBench(points=$points gpu=${"%.1f".format(gpuMs)} ms cpuEst=${"%.1f".format(cpuMsEstimated)} ms fixed=${"%.2f".format(fixedMs)} ms " +
            "compile=${"%.1f".format(compileMs)} ms score=${"%.2f".format(gpuScore)} Mpt/s speedup=${"%.2f".format(speedup)}x total=${"%.0f".format(totalMs)} ms)"
}

/** ~1 s benchmark: denoise on 60k synthetic points (a 4 mm-noise slab), GPU vs CPU. Run it from a worker thread. */
object GpuBench {
    const val POINTS = 60_000
    private const val CPU_POINTS = 6_000
    private const val RADIUS = 0.012f

    /** A flat slab: [n] points at a fixed density (~[DENSITY] per m^2) with 4 mm gaussian noise in z (metres). */
    internal fun slab(n: Int, seed: Long): FloatArray {
        val rnd = Random(seed)
        val side = Math.sqrt(n / DENSITY)
        val p = FloatArray(n * 3)
        for (i in 0 until n) {
            p[i * 3] = (rnd.nextDouble() * side).toFloat(); p[i * 3 + 1] = (rnd.nextDouble() * side).toFloat()
            p[i * 3 + 2] = (rnd.nextGaussian() * 0.004).toFloat()
        }
        return p
    }

    private const val DENSITY = 250_000.0 // points per m^2 (2 mm spacing)

    fun run(ctx: GpuContext?): GpuBenchResult {
        val t0 = System.nanoTime()
        fun fail(r: String) = GpuBenchResult(false, r, POINTS, 0.0, 0.0, 0.0, 0.0, (System.nanoTime() - t0) / 1e6)
        if (ctx == null || !ctx.computeSupported) return fail("no compute")
        return try {
            val big = slab(POINTS, 7); val tiny = slab(64, 8); val small = slab(CPU_POINTS, 9)
            fun gpu(p: FloatArray): Double {
                val r = GpuDenoise.smooth(ctx, p, RADIUS, 1)
                if (!r.usedGpu) throw GpuException(r.fallbackReason ?: "fell back")
                return r.millis
            }
            val compile = gpu(tiny)            // includes shader compile
            val fixed = minOf(gpu(tiny), gpu(tiny), gpu(tiny))
            gpu(big)                           // warm-up (driver buffer allocation)
            val gpuMs = minOf(gpu(big), gpu(big))
            val c0 = System.nanoTime()
            ObjectDenoise.smooth(small, RADIUS, 1)
            val cpuSmall = (System.nanoTime() - c0) / 1e6
            GpuBenchResult(true, null, POINTS, gpuMs, cpuSmall * POINTS / CPU_POINTS, fixed, compile, (System.nanoTime() - t0) / 1e6)
        } catch (e: GpuException) {
            fail("GPU error: ${e.message}")
        }
    }
}
