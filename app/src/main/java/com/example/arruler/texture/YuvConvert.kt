package com.example.arruler.texture

/** YUV_420_888 planes to NV21 (Y then interleaved V,U), honouring row and pixel strides. Pure. */
object YuvConvert {
    fun toNv21(
        y: ByteArray, yRowStride: Int,
        u: ByteArray, v: ByteArray, uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int
    ): ByteArray {
        val out = ByteArray(width * height * 3 / 2)
        for (j in 0 until height) System.arraycopy(y, j * yRowStride, out, j * width, width)
        var o = width * height
        for (j in 0 until height / 2) for (i in 0 until width / 2) {
            val idx = j * uvRowStride + i * uvPixelStride
            out[o++] = v[idx]
            out[o++] = u[idx]
        }
        return out
    }
}
