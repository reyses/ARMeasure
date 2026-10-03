package com.example.arruler.gpu

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** Candidate planes of one RANSAC round: `normal . p = d`, one entry per non-degenerate triplet, in draw order. Pure. */
internal class Hypotheses(val count: Int, val plane: DoubleArray /* nx ny nz d per hypothesis */, val anchor: IntArray /* point offset (index*3) of the first sample */)

/**
 * Parallel hypothesis scoring for depth/PlaneExtractor's 3-point RANSAC (its private `ransac`): the CPU draws the triplets
 * with the SAME seeded RNG in the SAME order, the GPU counts inliers per hypothesis (one 64-thread work group each,
 * shared-memory tree reduction, no atomics), the CPU re-counts in double only the hypotheses within a hair of the GPU
 * maximum (float vs double can flip a point exactly on the threshold) and picks the first best, exactly as the CPU loop
 * does. The chosen plane is therefore identical to the CPU path for the same seed. The refit stays in PlaneExtractor.
 */
object GpuRansac {
    const val GROUP = 64
    /** GPU counts within this many inliers of the GPU best are re-checked exactly on the CPU. */
    private const val RECHECK_MARGIN = 3
    private const val RECHECK_MAX = 96

    internal val SHADER = """#version 310 es
layout(local_size_x = $GROUP) in;
precision highp float;
precision highp int;
layout(std430, binding = 0) readonly buffer Pts { vec4 pts[]; };
layout(std430, binding = 1) readonly buffer Hyp { vec4 hyp[]; }; // 2 per hypothesis: (nx ny nz 0), (ax ay az 0)
layout(std430, binding = 2) writeonly buffer Cnt { int counts[]; };
uniform int uN;
uniform int uBase;
uniform float uThr;
shared int partial[$GROUP];

void main() {
    int h = int(gl_WorkGroupID.x) + uBase;
    int lid = int(gl_LocalInvocationID.x);
    vec3 n = hyp[h * 2].xyz;
    vec3 a = hyp[h * 2 + 1].xyz;
    int c = 0;
    for (int i = lid; i < uN; i += $GROUP) {
        if (abs(dot(n, pts[i].xyz - a)) <= uThr) c++;
    }
    partial[lid] = c;
    barrier();
    for (int s = $GROUP / 2; s > 0; s >>= 1) {
        if (lid < s) partial[lid] += partial[lid + s];
        barrier();
    }
    if (lid == 0) counts[h] = partial[0];
}
"""

    /** The CPU draw sequence of PlaneExtractor.ransac: subsample first (when n > [ransacSample]), then 3 draws per iteration. */
    internal fun subsample(idx: IntArray, n: Int, ransacSample: Int, random: Random): IntArray =
        if (n <= ransacSample) IntArray(n) { idx[it] } else IntArray(ransacSample) { idx[random.nextInt(n)] }

    internal fun draw(p: FloatArray, sub: IntArray, iterations: Int, random: Random): Hypotheses {
        val plane = DoubleArray(iterations * 4); val anchor = IntArray(iterations)
        var k = 0
        repeat(iterations) {
            val a = sub[random.nextInt(sub.size)] * 3
            val b = sub[random.nextInt(sub.size)] * 3
            val c = sub[random.nextInt(sub.size)] * 3
            val ux = (p[b] - p[a]).toDouble(); val uy = (p[b + 1] - p[a + 1]).toDouble(); val uz = (p[b + 2] - p[a + 2]).toDouble()
            val vx = (p[c] - p[a]).toDouble(); val vy = (p[c + 1] - p[a + 1]).toDouble(); val vz = (p[c + 2] - p[a + 2]).toDouble()
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-9) return@repeat
            nx /= len; ny /= len; nz /= len
            plane[k * 4] = nx; plane[k * 4 + 1] = ny; plane[k * 4 + 2] = nz
            plane[k * 4 + 3] = nx * p[a] + ny * p[a + 1] + nz * p[a + 2]
            anchor[k] = a
            k++
        }
        return Hypotheses(k, plane, anchor)
    }

    /** Exact (double) inlier count of hypothesis [h], the CPU loop's arithmetic. */
    internal fun exactCount(p: FloatArray, sub: IntArray, hy: Hypotheses, h: Int, thr: Float): Int {
        val nx = hy.plane[h * 4]; val ny = hy.plane[h * 4 + 1]; val nz = hy.plane[h * 4 + 2]; val dd = hy.plane[h * 4 + 3]
        var cnt = 0
        for (i in sub) {
            val o = i * 3
            if (abs(nx * p[o] + ny * p[o + 1] + nz * p[o + 2] - dd) <= thr) cnt++
        }
        return cnt
    }

    /** First-best selection of the CPU loop (strictly greater wins) over [counts]; null plane if none. */
    internal fun pick(hy: Hypotheses, counts: IntArray, n: Int, subSize: Int, minInliers: Int): DoubleArray? {
        var bestCount = 0; var best = -1
        for (h in 0 until hy.count) if (counts[h] > bestCount) { bestCount = counts[h]; best = h }
        val scaled = bestCount.toLong() * n / subSize
        return if (best < 0 || scaled < minInliers) null else doubleArrayOf(hy.plane[best * 4], hy.plane[best * 4 + 1], hy.plane[best * 4 + 2], hy.plane[best * 4 + 3])
    }

    /** Verbatim CPU reference / fallback of PlaneExtractor.ransac (same signature semantics). */
    fun cpuBestPlane(p: FloatArray, idx: IntArray, n: Int, iterations: Int, ransacSample: Int, threshold: Float, minInliers: Int, random: Random): DoubleArray? {
        val sub = subsample(idx, n, ransacSample, random)
        val hy = draw(p, sub, iterations, random)
        val counts = IntArray(hy.count) { exactCount(p, sub, hy, it, threshold) }
        return pick(hy, counts, n, sub.size, minInliers)
    }

    /** Same result as [cpuBestPlane]; [ctx] null / compute-less / GL error -> the CPU. */
    fun bestPlane(
        ctx: GpuContext?, p: FloatArray, idx: IntArray, n: Int, iterations: Int, ransacSample: Int, threshold: Float, minInliers: Int,
        random: Random, policy: GpuProfile? = null,
    ): GpuRun<DoubleArray?> {
        val t0 = System.nanoTime()
        val sub = subsample(idx, n, ransacSample, random)
        val hy = draw(p, sub, iterations, random)
        fun cpu(reason: String): GpuRun<DoubleArray?> {
            val counts = IntArray(hy.count) { exactCount(p, sub, hy, it, threshold) }
            return GpuRun(pick(hy, counts, n, sub.size, minInliers), false, (System.nanoTime() - t0) / 1e6, reason)
        }
        if (hy.count == 0) return GpuRun(null, false, 0.0, "no hypotheses")
        if (ctx == null || !ctx.computeSupported) return cpu("no compute")
        if (policy != null && !policy.useGpu(GpuKernel.RANSAC, sub.size * hy.count)) return cpu("below GPU threshold")
        return try {
            val m = sub.size
            val pts = FloatArray(m * 4)
            for ((k, i) in sub.withIndex()) { pts[k * 4] = p[i * 3]; pts[k * 4 + 1] = p[i * 3 + 1]; pts[k * 4 + 2] = p[i * 3 + 2] }
            val hv = FloatArray(hy.count * 8)
            for (h in 0 until hy.count) {
                hv[h * 8] = hy.plane[h * 4].toFloat(); hv[h * 8 + 1] = hy.plane[h * 4 + 1].toFloat(); hv[h * 8 + 2] = hy.plane[h * 4 + 2].toFloat()
                val a = hy.anchor[h]
                hv[h * 8 + 4] = p[a]; hv[h * 8 + 5] = p[a + 1]; hv[h * 8 + 6] = p[a + 2]
            }
            val gpuCounts = ctx.call {
                val prog = ctx.program("ransac", SHADER)
                val bufs = IntArray(3)
                try {
                    bufs[0] = ctx.ssbo(m * 16, GpuBuffers.floats(pts))
                    bufs[1] = ctx.ssbo(hy.count * 32, GpuBuffers.floats(hv))
                    bufs[2] = ctx.ssbo(hy.count * 4)
                    for (b in 0..2) ctx.bind(b, bufs[b])
                    ctx.uniform1i(prog, "uN", m); ctx.uniform1f(prog, "uThr", threshold)
                    ctx.dispatchChunked(prog, hy.count, 512)
                    GpuBuffers.toInts(ctx.download(bufs[2], hy.count * 4), hy.count)
                } finally { ctx.deleteBuffers(*bufs.filter { it != 0 }.toIntArray()) }
            }
            // exact re-check of the near-top candidates (float vs double on-threshold flips)
            val top = gpuCounts.max()
            val cand = (0 until hy.count).filter { gpuCounts[it] >= top - RECHECK_MARGIN - m / 4000 }
            val counts = gpuCounts.copyOf()
            if (cand.size <= RECHECK_MAX) for (h in cand) counts[h] = exactCount(p, sub, hy, h, threshold)
            else for (h in cand.sortedByDescending { gpuCounts[it] }.take(RECHECK_MAX)) counts[h] = exactCount(p, sub, hy, h, threshold)
            GpuRun(pick(hy, counts, n, m, minInliers), true, (System.nanoTime() - t0) / 1e6, null)
        } catch (e: GpuException) {
            cpu("GPU error: ${e.message}")
        }
    }
}
