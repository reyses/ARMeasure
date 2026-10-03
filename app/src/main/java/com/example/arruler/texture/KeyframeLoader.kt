package com.example.arruler.texture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/** Pure sizing rule of [KeyframeLoader]: how many pixels each decoded keyframe may have. */
object KeyframeBudget {
    /** Total ARGB pixels held while baking: 4 bytes each, so MID 24 M = 96 MB, HIGH 40 M = 160 MB, LOW 12 M = 48 MB. */
    fun totalPixels(tier: com.example.arruler.processing.Tier): Int = when (tier) {
        com.example.arruler.processing.Tier.LOW -> 12_000_000
        com.example.arruler.processing.Tier.MID -> 24_000_000
        com.example.arruler.processing.Tier.HIGH -> 40_000_000
    }

    /** Pixels per photo for [count] photos in [total] (never below 100 k, never above the photo's own size). */
    fun perImage(total: Int, count: Int, own: Int): Int = minOf(own, max(100_000, total / max(1, count)))

    /** Target size of a [w] x [h] photo that must fit in [pixels], same aspect, at most the original. */
    fun targetSize(w: Int, h: Int, pixels: Int): Pair<Int, Int> {
        if (w * h <= pixels) return w to h
        val s = sqrt(pixels.toDouble() / (w.toDouble() * h))
        return max(8, (w * s).toInt()) to max(8, (h * s).toInt())
    }
}

/** Decodes saved keyframe JPEGs for the baker within a pixel budget (a 120 photo walk at 1080p is 1 GB as ARGB). Android. */
object KeyframeLoader {
    /** [onEach] gets the index (0-based) after each photo; [cancelled] stops the loop early. */
    fun load(
        dir: File, records: List<KeyframeRecord>, totalPixels: Int,
        onEach: (Int) -> Unit = {}, cancelled: () -> Boolean = { false },
    ): List<Keyframe> {
        val out = ArrayList<Keyframe>(records.size)
        for ((i, r) in records.withIndex()) {
            if (cancelled()) break
            val f = File(dir, r.file)
            if (!f.isFile) continue
            val bmp = decode(f, KeyframeBudget.perImage(totalPixels, records.size, r.width * r.height)) ?: continue
            try {
                val w = bmp.width; val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                out += Keyframe(r.pose.toFloatArray(), r.intrinsics().scaledTo(w, h), px)
            } finally {
                bmp.recycle()
            }
            onEach(i)
        }
        return out
    }

    /** The JPEG at (about) [pixels] pixels: a power-of-two decode, then an exact scale when it is still larger. */
    fun decode(file: File, pixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val (tw, th) = KeyframeBudget.targetSize(bounds.outWidth, bounds.outHeight, pixels)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = BitmapFactory.decodeFile(file.path, opts) ?: return null
        if (raw.width == tw && raw.height == th) return raw
        val scaled = Bitmap.createScaledBitmap(raw, tw, th, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    /**
     * The gallery photo: the sharpest keyframe, centre-cropped to a square of [size] px (the capture rules keep the
     * object in the middle of the image). Null when no keyframe can be read.
     */
    fun bestPhoto(dir: File, records: List<KeyframeRecord>, size: Int = 512, rotateDeg: Int = 0): Bitmap? {
        for (r in records.sortedByDescending { it.sharpness }.take(3)) {
            val bmp = decode(File(dir, r.file), size * size * 2) ?: continue
            val side = minOf(bmp.width, bmp.height)
            val m = android.graphics.Matrix().apply { postRotate(rotateDeg.toFloat()) }
            val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, m, true)
            if (sq !== bmp) bmp.recycle()
            val fin = if (side > size) Bitmap.createScaledBitmap(sq, size, size, true) else sq
            if (fin !== sq) sq.recycle()
            return fin
        }
        return null
    }
}
