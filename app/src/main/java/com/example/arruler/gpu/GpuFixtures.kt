package com.example.arruler.gpu

import com.example.arruler.texture.Intrinsics
import com.example.arruler.texture.Keyframe
import java.util.Random

/**
 * Deterministic synthetic inputs for the GPU-vs-CPU equivalence checks. One source for the instrumented test
 * (androidTest) and the in-app self-test (diag/GpuSelfTest), so both judge the same data.
 */
object GpuFixtures {
    /** A flat slab of [n] points (~250 000 per m^2, 4 mm gaussian noise in z, metres). */
    fun slab(n: Int, seed: Long): FloatArray = GpuBench.slab(n, seed)

    /** Points on two crossing noisy faces with a sparse density so most balls stay under the 48-neighbour cap. */
    fun sparseBox(n: Int): FloatArray {
        val r = Random(21)
        val p = FloatArray(n * 3)
        for (i in 0 until n) {
            val a = r.nextFloat() * 0.5f; val b = r.nextFloat() * 0.3f; val e = (r.nextGaussian() * 0.003).toFloat()
            if (i % 2 == 0) { p[i * 3] = a; p[i * 3 + 1] = b; p[i * 3 + 2] = e } else { p[i * 3] = a; p[i * 3 + 1] = e; p[i * 3 + 2] = b }
        }
        return p
    }

    fun keyframe(w: Int, h: Int, seed: Long): Keyframe {
        val r = Random(seed)
        val px = IntArray(w * h) {
            val x = it % w; val y = it / w
            (0xFF shl 24) or (((x * 255 / w + r.nextInt(30)) and 0xFF) shl 16) or (((y * 255 / h) and 0xFF) shl 8) or (r.nextInt(256))
        }
        return Keyframe(FloatArray(16), Intrinsics(500f, 500f, w / 2f, h / 2f, w, h), px)
    }

    /** nCharts charts (2 triangles each) laid out on a grid of cells in an atlas, each pointing into one of the keyframes. */
    fun texelPlan(side: Int, nCharts: Int, scale: Float, views: Int, w: Int, h: Int): TexelPlan {
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

    /** Two noisy floor-like planes plus uniform clutter, [n] points (metres). */
    fun room(n: Int, seed: Long): FloatArray {
        val r = Random(seed)
        val p = FloatArray(n * 3)
        for (i in 0 until n) when (i % 4) {
            0, 1 -> { p[i * 3] = r.nextFloat() * 4f; p[i * 3 + 1] = (r.nextGaussian() * 0.005).toFloat(); p[i * 3 + 2] = r.nextFloat() * 4f }
            2 -> { p[i * 3] = r.nextFloat() * 4f; p[i * 3 + 1] = r.nextFloat() * 2.5f; p[i * 3 + 2] = (r.nextGaussian() * 0.005).toFloat() }
            else -> { p[i * 3] = r.nextFloat() * 4f; p[i * 3 + 1] = r.nextFloat() * 2.5f; p[i * 3 + 2] = r.nextFloat() * 4f }
        }
        return p
    }

    /** A checkerboard-ish luma plane with noise, with the given strides. */
    fun lumaPlane(w: Int, h: Int, rowStride: Int, pixelStride: Int): ByteArray {
        val r = Random(6)
        val y = ByteArray(rowStride * h + pixelStride)
        for (j in 0 until h) for (i in 0 until w) y[j * rowStride + i * pixelStride] = (((i / 8 + j / 8) % 2) * 120 + 60 + r.nextInt(20)).toByte()
        return y
    }

    /** Random NV21 bytes for a [w] x [h] image. */
    fun nv21(w: Int, h: Int): ByteArray = ByteArray(w * h * 3 / 2).also { Random(4).nextBytes(it) }

    /** The quadrilateral ROI used by the signature check. */
    fun roiHull(): List<FloatArray> = listOf(floatArrayOf(300f, 200f), floatArrayOf(900f, 220f), floatArrayOf(950f, 700f), floatArrayOf(350f, 740f))
}
