package com.example.arruler.gpu

import com.example.arruler.texture.Keyframe
import com.example.arruler.texture.sampleBilinear
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The packed layout TextureBaker produces after chart packing (the inputs of its rasterise stage 4), as plain arrays:
 * [triUV] 6 floats per triangle (keyframe pixel coordinates of its 3 vertices), [bestView] keyframe index per triangle
 * (-1 unseen), [chartOf] chart per triangle (-1 none), per chart the keyframe-space origin ([cMinU], [cMinV]) and the
 * atlas offset ([rx], [ry] = chart rectangle corner; the texel origin is rx + [pad], ry + [pad]), and the global [scale].
 */
class TexelPlan(
    val side: Int, val pad: Int, val scale: Float,
    val triUV: FloatArray, val bestView: IntArray, val chartOf: IntArray,
    val cMinU: FloatArray, val cMinV: FloatArray, val rx: IntArray, val ry: IntArray,
) {
    val triangleCount: Int get() = bestView.size
}

/** Texel -> triangle map, per-triangle sampling parameters. Pure. */
internal object TexelMapBuilder {
    /** Per texel the triangle that rasterises it (the LAST one wins, as in the CPU loop), -1 for none. */
    fun build(plan: TexelPlan): IntArray {
        val side = plan.side
        val map = IntArray(side * side) { -1 }
        for (t in 0 until plan.triangleCount) {
            val c = plan.chartOf[t]; if (c < 0) continue
            val ox = plan.rx[c] + plan.pad; val oy = plan.ry[c] + plan.pad
            val o = t * 6
            val s = plan.scale
            val ax = ox + (plan.triUV[o] - plan.cMinU[c]) * s; val ay = oy + (plan.triUV[o + 1] - plan.cMinV[c]) * s
            val bx = ox + (plan.triUV[o + 2] - plan.cMinU[c]) * s; val by = oy + (plan.triUV[o + 3] - plan.cMinV[c]) * s
            val cx = ox + (plan.triUV[o + 4] - plan.cMinU[c]) * s; val cy = oy + (plan.triUV[o + 5] - plan.cMinV[c]) * s
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (abs(area) < 1e-9f) continue
            val inv = 1f / area
            val x0 = max(0, floor(min(ax, min(bx, cx))).toInt()); val x1 = min(side - 1, ceil(max(ax, max(bx, cx))).toInt())
            val y0 = max(0, floor(min(ay, min(by, cy))).toInt()); val y1 = min(side - 1, ceil(max(ay, max(by, cy))).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val px = x + 0.5f; val py = y + 0.5f
                val w0 = ((cx - bx) * (py - by) - (cy - by) * (px - bx)) * inv
                val w1 = ((ax - cx) * (py - cy) - (ay - cy) * (px - cx)) * inv
                val w2 = 1f - w0 - w1
                if (w0 < -0.01f || w1 < -0.01f || w2 < -0.01f) continue
                map[y * side + x] = t
            }
        }
        return map
    }

    /** vec4 per triangle: (cMinU, cMinV, texel origin x, texel origin y); view index per triangle. */
    fun triangleParams(plan: TexelPlan): Pair<FloatArray, IntArray> {
        val nT = plan.triangleCount
        val p = FloatArray(nT * 4)
        val v = IntArray(nT) { plan.bestView[it] }
        for (t in 0 until nT) {
            val c = plan.chartOf[t]; if (c < 0) continue
            p[t * 4] = plan.cMinU[c]; p[t * 4 + 1] = plan.cMinV[c]
            p[t * 4 + 2] = (plan.rx[c] + plan.pad).toFloat(); p[t * 4 + 3] = (plan.ry[c] + plan.pad).toFloat()
        }
        return p to v
    }

    /** Keyframe table: 3 ints per keyframe (pixel offset, width, height) and all pixels concatenated. */
    class Packed(val meta: IntArray, val pixels: IntArray)

    fun packKeyframes(kfs: List<Keyframe>): Packed {
        val meta = IntArray(kfs.size * 3)
        var total = 0
        for ((i, k) in kfs.withIndex()) { meta[i * 3] = total; meta[i * 3 + 1] = k.intrinsics.width; meta[i * 3 + 2] = k.intrinsics.height; total += k.argb.size }
        val px = IntArray(total)
        var o = 0
        for (k in kfs) { System.arraycopy(k.argb, 0, px, o, k.argb.size); o += k.argb.size }
        return Packed(meta, px)
    }
}

/**
 * GPU per-texel sampling stage of TextureBaker (stage 4: rasterise the atlas), one invocation per atlas texel: look up the
 * triangle in the precomputed texel map, derive the keyframe coordinate, bilinear-sample that keyframe. Chart packing, the
 * solid cells, gutter dilation and the output mesh stay on the CPU. Texels no triangle covers are returned as 0. Equivalence
 * target: within 2 levels per channel of [cpuSample]. CPU fallback = [cpuSample] (which calls the same sampleBilinear).
 */
object GpuTextureBake {
    const val GROUP = 8 // 8 x 8 = 64 threads

    internal val SHADER = """#version 310 es
layout(local_size_x = $GROUP, local_size_y = $GROUP) in;
precision highp float;
precision highp int;
layout(std430, binding = 0) readonly buffer TexTri { int texTri[]; };
layout(std430, binding = 1) readonly buffer TriP { vec4 triP[]; };
layout(std430, binding = 2) readonly buffer TriV { int triView[]; };
layout(std430, binding = 3) readonly buffer Meta { int kfMeta[]; };
layout(std430, binding = 4) readonly buffer Pix { uint kfPix[]; };
layout(std430, binding = 5) writeonly buffer Atlas { uint atlas[]; };
uniform int uSide;
uniform float uScale;
uniform int uRow0;

uint chan(uint px, int shift) { return (px >> uint(shift)) & 0xFFu; }

void main() {
    int x = int(gl_GlobalInvocationID.x);
    int y = (int(gl_WorkGroupID.y) + uRow0) * $GROUP + int(gl_LocalInvocationID.y);
    if (x >= uSide || y >= uSide) return;
    int t = texTri[y * uSide + x];
    if (t < 0) { atlas[y * uSide + x] = 0u; return; }
    vec4 P = triP[t];
    int v = triView[t];
    float px = float(x) + 0.5;
    float py = float(y) + 0.5;
    float u = P.x + (px - P.z) / uScale;
    float w = P.y + (py - P.w) / uScale;
    int off = kfMeta[v * 3]; int iw = kfMeta[v * 3 + 1]; int ih = kfMeta[v * 3 + 2];
    float fx0 = clamp(u - 0.5, 0.0, float(iw - 1));
    float fy0 = clamp(w - 0.5, 0.0, float(ih - 1));
    int x0 = int(fx0); int y0 = int(fy0);
    int x1 = min(x0 + 1, iw - 1); int y1 = min(y0 + 1, ih - 1);
    float fx = fx0 - float(x0); float fy = fy0 - float(y0);
    uint a = kfPix[off + y0 * iw + x0]; uint b = kfPix[off + y0 * iw + x1];
    uint c = kfPix[off + y1 * iw + x0]; uint d = kfPix[off + y1 * iw + x1];
    uint outc = 0xFF000000u;
    for (int k = 0; k < 3; k++) {
        int sh = 16 - 8 * k;
        float top = float(chan(a, sh)) * (1.0 - fx) + float(chan(b, sh)) * fx;
        float bot = float(chan(c, sh)) * (1.0 - fx) + float(chan(d, sh)) * fx;
        int val = clamp(int(top * (1.0 - fy) + bot * fy + 0.5), 0, 255);
        outc |= uint(val) << uint(sh);
    }
    atlas[y * uSide + x] = outc;
}
"""

    /** CPU reference of exactly the stage the GPU runs (and the fallback). Unfilled texels = 0. */
    fun cpuSample(plan: TexelPlan, keyframes: List<Keyframe>, texelMap: IntArray = TexelMapBuilder.build(plan)): IntArray {
        val out = IntArray(plan.side * plan.side)
        for (i in out.indices) {
            val t = texelMap[i]; if (t < 0) continue
            val c = plan.chartOf[t]
            val x = i % plan.side; val y = i / plan.side
            val ox = plan.rx[c] + plan.pad; val oy = plan.ry[c] + plan.pad
            out[i] = sampleBilinear(keyframes[plan.bestView[t]], plan.cMinU[c] + (x + 0.5f - ox) / plan.scale, plan.cMinV[c] + (y + 0.5f - oy) / plan.scale)
        }
        return out
    }

    fun sample(ctx: GpuContext?, plan: TexelPlan, keyframes: List<Keyframe>, policy: GpuProfile? = null): GpuRun<IntArray> {
        val t0 = System.nanoTime()
        val texelMap = TexelMapBuilder.build(plan)
        fun cpu(reason: String) = GpuRun(cpuSample(plan, keyframes, texelMap), false, (System.nanoTime() - t0) / 1e6, reason)
        if (ctx == null || !ctx.computeSupported) return cpu("no compute")
        if (policy != null && !policy.useGpu(GpuKernel.TEXTURE_BAKE, plan.side * plan.side)) return cpu("below GPU threshold")
        if (keyframes.isEmpty()) return cpu("no keyframes")
        return try {
            val (tp, tv) = TexelMapBuilder.triangleParams(plan)
            val packed = TexelMapBuilder.packKeyframes(keyframes)
            val n = plan.side * plan.side
            val out = ctx.call {
                val prog = ctx.program("texbake", SHADER)
                val bufs = IntArray(6)
                try {
                    bufs[0] = ctx.ssbo(n * 4, GpuBuffers.ints(texelMap))
                    bufs[1] = ctx.ssbo(max(1, tp.size) * 4, GpuBuffers.floats(tp))
                    bufs[2] = ctx.ssbo(max(1, tv.size) * 4, GpuBuffers.ints(tv))
                    bufs[3] = ctx.ssbo(packed.meta.size * 4, GpuBuffers.ints(packed.meta))
                    bufs[4] = ctx.ssbo(max(1, packed.pixels.size) * 4, GpuBuffers.ints(packed.pixels))
                    bufs[5] = ctx.ssbo(n * 4)
                    for (b in 0..5) ctx.bind(b, bufs[b])
                    ctx.uniform1i(prog, "uSide", plan.side); ctx.uniform1f(prog, "uScale", plan.scale)
                    // chunk by rows of work groups so one dispatch covers at most ~2M texels
                    val gx = (plan.side + GROUP - 1) / GROUP
                    val rowsPerChunk = max(1, 32768 / gx)
                    var gy = 0
                    while (gy < gx) {
                        val rows = min(rowsPerChunk, gx - gy)
                        ctx.uniform1i(prog, "uRow0", gy)
                        ctx.dispatch(prog, gx, rows)
                        gy += rows
                        if (gy < gx) ctx.finish()
                    }
                    GpuBuffers.toInts(ctx.download(bufs[5], n * 4), n)
                } finally { ctx.deleteBuffers(*bufs.filter { it != 0 }.toIntArray()) }
            }
            GpuRun(out, true, (System.nanoTime() - t0) / 1e6, null)
        } catch (e: GpuException) {
            cpu("GPU error: ${e.message}")
        }
    }
}
