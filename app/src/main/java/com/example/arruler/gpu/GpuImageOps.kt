package com.example.arruler.gpu

import com.example.arruler.texture.Sharpness
import com.example.arruler.texture.SpinRoi
import kotlin.math.max
import kotlin.math.min

/**
 * Per-frame image kernels: NV21 (the output of texture/YuvConvert) to ARGB, Laplacian-variance sharpness
 * (texture/KeyframePolicy's [Sharpness.laplacianVariance] definition), and the luma grid signature of the spin ROI
 * ([SpinRoi.signature]). Each has a CPU twin that is the reference and the fallback.
 */
object GpuImageOps {
    private const val G2 = 8 // 8 x 8 = 64 threads
    private const val G1 = 64

    private const val BYTE_AT = """
uint byteAt(int i) { return (src[i >> 2] >> uint((i & 3) * 8)) & 0xFFu; }
"""

    internal val NV21_SHADER = """#version 310 es
layout(local_size_x = $G2, local_size_y = $G2) in;
precision highp float;
precision highp int;
layout(std430, binding = 0) readonly buffer Src { uint src[]; };
layout(std430, binding = 1) writeonly buffer Dst { uint dst[]; };
uniform int uW;
uniform int uH;
uniform int uRow0;
$BYTE_AT
int q(float v) { return clamp(int(v + 0.5), 0, 255); }
void main() {
    int x = int(gl_GlobalInvocationID.x);
    int y = (int(gl_WorkGroupID.y) + uRow0) * $G2 + int(gl_LocalInvocationID.y);
    if (x >= uW || y >= uH) return;
    float Y = float(byteAt(y * uW + x));
    int uv = uW * uH + (y >> 1) * uW + (x >> 1) * 2;
    float V = float(byteAt(uv)) - 128.0;
    float U = float(byteAt(uv + 1)) - 128.0;
    int r = q(Y + 1.402 * V);
    int g = q(Y - 0.344136 * U - 0.714136 * V);
    int b = q(Y + 1.772 * U);
    dst[y * uW + x] = 0xFF000000u | (uint(r) << 16) | (uint(g) << 8) | uint(b);
}
"""

    internal val DOWNSAMPLE_SHADER = """#version 310 es
layout(local_size_x = $G1) in;
precision highp float;
precision highp int;
layout(std430, binding = 0) readonly buffer Src { uint src[]; };
layout(std430, binding = 1) writeonly buffer G { float g[]; };
uniform int uW;
uniform int uH;
uniform int uDs;
uniform int uRowStride;
uniform int uPixStride;
uniform int uBase;
$BYTE_AT
void main() {
    int id = (int(gl_WorkGroupID.x) + uBase) * $G1 + int(gl_LocalInvocationID.x);
    if (id >= uW * uH) return;
    int i = id % uW; int j = id / uW;
    int s = 0;
    for (int dj = 0; dj < uDs; dj++) {
        int row = (j * uDs + dj) * uRowStride;
        for (int di = 0; di < uDs; di++) s += int(byteAt(row + (i * uDs + di) * uPixStride));
    }
    g[id] = float(s) * (1.0 / float(uDs * uDs));
}
"""

    internal val LAPLACE_SHADER = """#version 310 es
layout(local_size_x = $G1) in;
precision highp float;
precision highp int;
layout(std430, binding = 0) readonly buffer G { float g[]; };
layout(std430, binding = 1) writeonly buffer R { vec2 rows[]; };
uniform int uW;
uniform int uH;
uniform int uBase;
void main() {
    int j = (int(gl_WorkGroupID.x) + uBase) * $G1 + int(gl_LocalInvocationID.x);
    if (j >= uH) return;
    float s = 0.0; float s2 = 0.0;
    if (j >= 1 && j < uH - 1) {
        for (int i = 1; i < uW - 1; i++) {
            float l = g[(j - 1) * uW + i] + g[(j + 1) * uW + i] + g[j * uW + i - 1] + g[j * uW + i + 1] - 4.0 * g[j * uW + i];
            s += l; s2 += l * l;
        }
    }
    rows[j] = vec2(s, s2);
}
"""

    internal val SIGNATURE_SHADER = """#version 310 es
layout(local_size_x = $G1) in;
precision highp float;
precision highp int;
layout(std430, binding = 0) readonly buffer Src { uint src[]; };
layout(std430, binding = 1) readonly buffer Valid { int valid[]; };
layout(std430, binding = 2) writeonly buffer Sig { float sig[]; };
uniform int uX0; uniform int uY0; uniform int uX1; uniform int uY1;
uniform int uGrid; uniform int uRowStride; uniform int uPixStride;
uniform int uBase;
$BYTE_AT
void main() {
    int id = (int(gl_WorkGroupID.x) + uBase) * $G1 + int(gl_LocalInvocationID.x);
    if (id >= uGrid * uGrid) return;
    sig[id] = 0.0;
    if (valid[id] == 0) return;
    int gx = id % uGrid; int gy = id / uGrid;
    float cw = float(uX1 - uX0) / float(uGrid); float ch = float(uY1 - uY0) / float(uGrid);
    int xa = uX0 + int(float(gx) * cw); int xb = max(xa + 1, uX0 + int(float(gx + 1) * cw));
    int ya = uY0 + int(float(gy) * ch); int yb = max(ya + 1, uY0 + int(float(gy + 1) * ch));
    int sx = max(1, (xb - xa) / 4); int sy = max(1, (yb - ya) / 4);
    int s = 0; int n = 0;
    for (int yy = ya; yy < yb; yy += sy) {
        for (int xx = xa; xx < xb; xx += sx) { s += int(byteAt(yy * uRowStride + xx * uPixStride)); n++; }
    }
    sig[id] = n > 0 ? float(s) / float(n) : 0.0;
}
"""

    // ---- NV21 -> ARGB ----

    private fun q(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)

    /** Reference: BT.601 full range (the YuvImage / JPEG convention) NV21 -> opaque ARGB. */
    fun nv21ToArgbCpu(nv21: ByteArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val yy = (nv21[y * w + x].toInt() and 0xFF).toFloat()
            val uv = w * h + (y shr 1) * w + (x shr 1) * 2
            val v = (nv21[uv].toInt() and 0xFF) - 128f
            val u = (nv21[uv + 1].toInt() and 0xFF) - 128f
            out[y * w + x] = (0xFF shl 24) or (q(yy + 1.402f * v) shl 16) or (q(yy - 0.344136f * u - 0.714136f * v) shl 8) or q(yy + 1.772f * u)
        }
        return out
    }

    fun nv21ToArgb(ctx: GpuContext?, nv21: ByteArray, w: Int, h: Int, policy: GpuProfile? = null): GpuRun<IntArray> {
        val t0 = System.nanoTime()
        fun cpu(reason: String) = GpuRun(nv21ToArgbCpu(nv21, w, h), false, (System.nanoTime() - t0) / 1e6, reason)
        require(nv21.size >= w * h * 3 / 2) { "NV21 buffer too small" }
        if (ctx == null || !ctx.computeSupported) return cpu("no compute")
        if (policy != null && !policy.useGpu(GpuKernel.YUV_CONVERT, w * h)) return cpu("below GPU threshold")
        return try {
            val out = ctx.call {
                val prog = ctx.program("nv21", NV21_SHADER)
                val bufs = IntArray(2)
                try {
                    bufs[0] = ctx.ssbo((nv21.size + 3) and 3.inv(), GpuBuffers.bytesPadded(nv21))
                    bufs[1] = ctx.ssbo(w * h * 4)
                    ctx.bind(0, bufs[0]); ctx.bind(1, bufs[1])
                    ctx.uniform1i(prog, "uW", w); ctx.uniform1i(prog, "uH", h)
                    dispatchRows(ctx, prog, (w + G2 - 1) / G2, (h + G2 - 1) / G2)
                    GpuBuffers.toInts(ctx.download(bufs[1], w * h * 4), w * h)
                } finally { ctx.deleteBuffers(*bufs.filter { it != 0 }.toIntArray()) }
            }
            GpuRun(out, true, (System.nanoTime() - t0) / 1e6, null)
        } catch (e: GpuException) { cpu("GPU error: ${e.message}") }
    }

    /** 2-D dispatch in row bands of at most ~16k work groups (short dispatches), band start in uniform uRow0. */
    private fun dispatchRows(ctx: GpuContext, prog: Int, gx: Int, gy: Int) {
        val rowsPerChunk = max(1, 16384 / max(1, gx))
        var r = 0
        while (r < gy) {
            val rows = min(rowsPerChunk, gy - r)
            ctx.uniform1i(prog, "uRow0", r)
            ctx.dispatch(prog, gx, rows)
            r += rows
            if (r < gy) ctx.finish()
        }
    }

    // ---- Laplacian variance ----

    fun laplacianVarianceCpu(y: ByteArray, w: Int, h: Int, rowStride: Int, pixelStride: Int = 1, downsample: Int = 0): Double =
        Sharpness.laplacianVariance(y, w, h, rowStride, pixelStride, downsample)

    fun laplacianVariance(
        ctx: GpuContext?, y: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int = 1, downsample: Int = 0,
        policy: GpuProfile? = null,
    ): GpuRun<Double> {
        val t0 = System.nanoTime()
        fun cpu(reason: String) = GpuRun(laplacianVarianceCpu(y, width, height, rowStride, pixelStride, downsample), false, (System.nanoTime() - t0) / 1e6, reason)
        val ds = if (downsample > 0) downsample else max(1, min(width, height) / 240)
        val w = width / ds; val h = height / ds
        if (w < 3 || h < 3) return GpuRun(0.0, false, 0.0, "too small")
        if (ctx == null || !ctx.computeSupported) return cpu("no compute")
        if (policy != null && !policy.useGpu(GpuKernel.SHARPNESS, width * height)) return cpu("below GPU threshold")
        return try {
            val rows = ctx.call {
                val down = ctx.program("downsample", DOWNSAMPLE_SHADER)
                val lap = ctx.program("laplace", LAPLACE_SHADER)
                val bufs = IntArray(3)
                try {
                    bufs[0] = ctx.ssbo((y.size + 3) and 3.inv(), GpuBuffers.bytesPadded(y))
                    bufs[1] = ctx.ssbo(w * h * 4)
                    bufs[2] = ctx.ssbo(h * 8)
                    ctx.bind(0, bufs[0]); ctx.bind(1, bufs[1])
                    ctx.uniform1i(down, "uW", w); ctx.uniform1i(down, "uH", h); ctx.uniform1i(down, "uDs", ds)
                    ctx.uniform1i(down, "uRowStride", rowStride); ctx.uniform1i(down, "uPixStride", pixelStride)
                    ctx.dispatchChunked(down, (w * h + G1 - 1) / G1)
                    ctx.bind(0, bufs[1]); ctx.bind(1, bufs[2])
                    ctx.uniform1i(lap, "uW", w); ctx.uniform1i(lap, "uH", h)
                    ctx.dispatchChunked(lap, (h + G1 - 1) / G1)
                    GpuBuffers.toFloats(ctx.download(bufs[2], h * 8), h * 2)
                } finally { ctx.deleteBuffers(*bufs.filter { it != 0 }.toIntArray()) }
            }
            var sum = 0.0; var sum2 = 0.0
            for (j in 1 until h - 1) { sum += rows[j * 2]; sum2 += rows[j * 2 + 1] }
            val n = (w - 2) * (h - 2)
            val mean = sum / n
            GpuRun(sum2 / n - mean * mean, true, (System.nanoTime() - t0) / 1e6, null)
        } catch (e: GpuException) { cpu("GPU error: ${e.message}") }
    }

    // ---- spin ROI luma signature ----

    fun roiSignatureCpu(roi: SpinRoi, y: ByteArray, rowStride: Int, pixelStride: Int = 1): FloatArray = roi.signature(y, rowStride, pixelStride)

    fun roiSignature(ctx: GpuContext?, roi: SpinRoi, y: ByteArray, rowStride: Int, pixelStride: Int = 1, policy: GpuProfile? = null): GpuRun<FloatArray> {
        val t0 = System.nanoTime()
        fun cpu(reason: String) = GpuRun(roiSignatureCpu(roi, y, rowStride, pixelStride), false, (System.nanoTime() - t0) / 1e6, reason)
        if (ctx == null || !ctx.computeSupported) return cpu("no compute")
        if (policy != null && !policy.useGpu(GpuKernel.ROI_SIGNATURE, (roi.x1 - roi.x0) * (roi.y1 - roi.y0))) return cpu("below GPU threshold")
        return try {
            val cells = roi.grid * roi.grid
            val valid = IntArray(cells) { if (roi.cellValid[it]) 1 else 0 }
            val out = ctx.call {
                val prog = ctx.program("signature", SIGNATURE_SHADER)
                val bufs = IntArray(3)
                try {
                    bufs[0] = ctx.ssbo((y.size + 3) and 3.inv(), GpuBuffers.bytesPadded(y))
                    bufs[1] = ctx.ssbo(cells * 4, GpuBuffers.ints(valid))
                    bufs[2] = ctx.ssbo(cells * 4)
                    for (b in 0..2) ctx.bind(b, bufs[b])
                    ctx.uniform1i(prog, "uX0", roi.x0); ctx.uniform1i(prog, "uY0", roi.y0); ctx.uniform1i(prog, "uX1", roi.x1); ctx.uniform1i(prog, "uY1", roi.y1)
                    ctx.uniform1i(prog, "uGrid", roi.grid); ctx.uniform1i(prog, "uRowStride", rowStride); ctx.uniform1i(prog, "uPixStride", pixelStride)
                    ctx.dispatchChunked(prog, (cells + G1 - 1) / G1)
                    GpuBuffers.toFloats(ctx.download(bufs[2], cells * 4), cells)
                } finally { ctx.deleteBuffers(*bufs.filter { it != 0 }.toIntArray()) }
            }
            GpuRun(out, true, (System.nanoTime() - t0) / 1e6, null)
        } catch (e: GpuException) { cpu("GPU error: ${e.message}") }
    }
}
