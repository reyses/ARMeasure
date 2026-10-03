package com.example.arruler.gpu

enum class GpuKernel(
    /** What one "size unit" is for [GpuProfile.useGpu]. */
    val unit: String,
    /** Cost of one unit of this kernel relative to one point of a denoise iteration (nominal, until calibrated per kernel on a phone). */
    val relativeCost: Double,
) {
    DENOISE("points", 1.0),
    TEXTURE_BAKE("atlas texels", 0.02),
    RANSAC("point x hypothesis", 0.004),
    YUV_CONVERT("pixels", 0.03),
    SHARPNESS("pixels", 0.015),
    ROI_SIGNATURE("ROI pixels", 0.005),
}

/**
 * Cost model of one kernel: wall time of the CPU path is `size x cpuNsPerUnit`; of the GPU path
 * `gpuFixedMs + size x gpuNsPerUnit` (the fixed part is context call + upload + dispatch + readback latency).
 */
data class KernelCost(val cpuNsPerUnit: Double, val gpuNsPerUnit: Double, val gpuFixedMs: Double) {
    fun cpuMs(size: Long) = size * cpuNsPerUnit / 1e6
    fun gpuMs(size: Long) = gpuFixedMs + size * gpuNsPerUnit / 1e6
}

/**
 * Per-device decision table: which kernels run on the GPU at which size. Pure and JVM-testable. The GPU wins only when its
 * time is below [margin] x the CPU time (a 20 % cushion by default, so a near tie stays on the CPU, which is the reference).
 */
class GpuProfile(
    val available: Boolean,
    /** Denoise throughput of the GPU path in million points per second (60k-point benchmark); 0 when unavailable. */
    val gpuScore: Double,
    val costs: Map<GpuKernel, KernelCost>,
    val margin: Double = 0.8,
) {
    fun useGpu(kernel: GpuKernel, size: Int): Boolean = useGpu(kernel, size.toLong())

    fun useGpu(kernel: GpuKernel, size: Long): Boolean {
        if (!available) return false
        val c = costs[kernel] ?: return false
        return c.gpuMs(size) < margin * c.cpuMs(size)
    }

    /** Smallest size at which the GPU is used, or null when it never wins (GPU per-unit cost not below the CPU's). */
    fun breakEvenUnits(kernel: GpuKernel): Long? {
        if (!available) return null
        val c = costs[kernel] ?: return null
        val gain = margin * c.cpuNsPerUnit - c.gpuNsPerUnit
        if (gain <= 0.0) return null
        return Math.floor(c.gpuFixedMs * 1e6 / gain).toLong() + 1
    }

    /** Copy with one kernel's cost replaced by a measurement (e.g. from the instrumented timing printouts). */
    fun withMeasured(kernel: GpuKernel, cost: KernelCost) = GpuProfile(available, gpuScore, costs + (kernel to cost), margin)

    override fun toString(): String =
        if (!available) "GpuProfile(unavailable)"
        else "GpuProfile(score=${"%.2f".format(gpuScore)} Mpt/s, " + GpuKernel.values().joinToString { "${it.name} >= ${breakEvenUnits(it) ?: "never"} ${it.unit}" } + ")"

    companion object {
        val NONE = GpuProfile(false, 0.0, emptyMap())

        /**
         * Builds the table from the denoise benchmark: the denoise row is measured; every other kernel assumes the same
         * speed-up and fixed overhead with its [GpuKernel.relativeCost] unit cost - a stand-in until each kernel is timed on
         * the phone (use [withMeasured]).
         */
        fun fromBench(b: GpuBenchResult): GpuProfile {
            if (!b.ok) return NONE
            val costs = GpuKernel.values().associateWith {
                KernelCost(b.cpuNsPerPoint * it.relativeCost, b.gpuNsPerPoint * it.relativeCost, b.fixedMs)
            }
            return GpuProfile(true, b.gpuScore, costs)
        }
    }
}
