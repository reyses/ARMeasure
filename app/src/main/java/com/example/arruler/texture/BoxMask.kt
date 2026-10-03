package com.example.arruler.texture

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.ObjectBox
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Object-region masks for photogrammetry: the 8 corners of the [ObjectBox] (padded) projected with a keyframe's pose
 * and intrinsics, their convex hull, rasterised to a binary image (255 = object region, 0 = ignore). Pure JVM.
 * COLMAP reads them with `--ImageReader.mask_path <dir>`: the mask of `images/000001.jpg` is `<dir>/000001.jpg.png`.
 */
object BoxMask {
    const val DEFAULT_PAD_M = 0.02f
    private const val NEAR = 0.02f
    private val EDGES = listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)

    /** World corners of the box grown by [pad] meters on every side (index bits: 1 = +x, 2 = +y, 4 = +z of the local frame). */
    fun corners(box: ObjectBox, pad: Float = DEFAULT_PAD_M): List<Vec3> = (0 until 8).map { i ->
        val x = if (i and 1 != 0) box.w / 2 + pad else -box.w / 2 - pad
        val y = if (i and 2 != 0) box.h + pad else -pad
        val z = if (i and 4 != 0) box.d / 2 + pad else -box.d / 2 - pad
        box.toWorld(Vec3(x, y, z))
    }

    /** Pixel points of the box silhouette: projected corners plus the edge crossings with the near plane. */
    fun projectedPoints(box: ObjectBox, pose: FloatArray, k: Intrinsics, pad: Float = DEFAULT_PAD_M): List<FloatArray> {
        val cam = CameraPose(pose)
        val cs = corners(box, pad)
        val out = ArrayList<FloatArray>()
        for (c in cs) cam.project(c, k, NEAR)?.let { out.add(floatArrayOf(it[0], it[1])) }
        for ((a, b) in EDGES) {
            val da = -cam.toCamera(cs[a]).z; val db = -cam.toCamera(cs[b]).z
            if ((da - NEAR) * (db - NEAR) < 0f) {
                val t = (NEAR - da) / (db - da)
                val p = cs[a] + (cs[b] - cs[a]) * t
                cam.project(p, k, NEAR * 0.5f)?.let { out.add(floatArrayOf(it[0], it[1])) }
            }
        }
        return out
    }

    /** Convex hull (Andrew's monotone chain) of 2D points; fewer than 3 distinct points give them back as is. */
    fun convexHull(points: List<FloatArray>): List<FloatArray> {
        val p = points.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (p.size < 3) return p
        fun cross(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val h = ArrayList<FloatArray>()
        for (q in p) { while (h.size >= 2 && cross(h[h.size - 2], h[h.size - 1], q) <= 0f) h.removeAt(h.size - 1); h.add(q) }
        val lower = h.size + 1
        for (q in p.reversed().drop(1)) { while (h.size >= lower && cross(h[h.size - 2], h[h.size - 1], q) <= 0f) h.removeAt(h.size - 1); h.add(q) }
        h.removeAt(h.size - 1)
        return h
    }

    /** Hull of the box silhouette in pixels (empty if the box is entirely behind the camera). */
    fun hull(box: ObjectBox, pose: FloatArray, k: Intrinsics, pad: Float = DEFAULT_PAD_M): List<FloatArray> =
        convexHull(projectedPoints(box, pose, k, pad))

    /** True if pixel position (x, y) is inside the convex [hull] (given in the order [convexHull] returns). */
    fun inside(hull: List<FloatArray>, x: Float, y: Float): Boolean {
        if (hull.size < 3) return false
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]
            if ((b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]) < 0f) return false
        }
        return true
    }

    /** Binary mask, row-major [width] x [height], 255 inside the hull (pixel centres), 0 outside. */
    fun rasterise(hull: List<FloatArray>, width: Int, height: Int): ByteArray {
        val m = ByteArray(width * height)
        if (hull.size < 3) return m
        val x0 = maxOf(0, kotlin.math.floor(hull.minOf { it[0] }).toInt()); val x1 = minOf(width - 1, kotlin.math.ceil(hull.maxOf { it[0] }).toInt())
        val y0 = maxOf(0, kotlin.math.floor(hull.minOf { it[1] }).toInt()); val y1 = minOf(height - 1, kotlin.math.ceil(hull.maxOf { it[1] }).toInt())
        for (y in y0..y1) for (x in x0..x1) if (inside(hull, x + 0.5f, y + 0.5f)) m[y * width + x] = 0xFF.toByte()
        return m
    }

    /** One call: PNG bytes (8-bit grayscale) of the mask for a keyframe. */
    fun maskPng(box: ObjectBox, pose: FloatArray, k: Intrinsics, pad: Float = DEFAULT_PAD_M): ByteArray =
        grayPng(rasterise(hull(box, pose, k, pad), k.width, k.height), k.width, k.height)

    /** Minimal PNG encoder for an 8-bit grayscale image (no Android needed). */
    fun grayPng(gray: ByteArray, width: Int, height: Int): ByteArray {
        require(gray.size == width * height) { "size mismatch" }
        val raw = ByteArray((width + 1) * height)
        for (y in 0 until height) System.arraycopy(gray, y * width, raw, y * (width + 1) + 1, width)
        val def = Deflater(6)
        def.setInput(raw); def.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        def.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            val len = data.size
            out.write(byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()))
            val td = type.toByteArray(Charsets.US_ASCII) + data
            out.write(td)
            val crc = CRC32().apply { update(td) }.value
            out.write(byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte()))
        }
        fun be(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        chunk("IHDR", be(width) + be(height) + byteArrayOf(8, 0, 0, 0, 0))
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
