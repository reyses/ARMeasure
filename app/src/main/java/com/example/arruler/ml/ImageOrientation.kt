package com.example.arruler.ml

import java.nio.ByteBuffer

/**
 * Pure image-orientation math and YUV conversion for the ML Kit input (JVM-testable, no Android types).
 *
 * ARCore's CPU image ([com.google.ar.core.Frame.acquireCameraImage]) is NOT rotated to the display: it is
 * the sensor's native orientation (landscape on phones), with IMAGE_PIXELS coordinates u right / v down, size w x h.
 * ML Kit wants to be told how many degrees CLOCKWISE the image must be rotated to appear upright for the display,
 * and returns boxes in that upright image.
 *
 *   rotation = (sensorOrientation - displayRotation + 360) % 360      (back-facing camera)
 *
 * sensorOrientation = CameraCharacteristics.SENSOR_ORIENTATION (clockwise degrees the sensor image must be turned to
 * match a device held in natural orientation; 90 on nearly every phone), displayRotation = Display.getRotation()
 * as degrees (ROTATION_0/90/180/270 = 0/90/180/270, counter-clockwise from natural to the current UI rotation).
 * Portrait phone: (90 - 0) = 90. Landscape with the left edge down (ROTATION_90): (90 - 90) = 0, the sensor image is
 * already upright. ROTATION_270: (90 - 270 + 360) = 180.
 *
 * Boxes from ML Kit are mapped back to IMAGE_PIXELS with [uprightToImage]; taps go the other way ([imageToUpright]).
 */
object ImageOrientation {
    /** Surface.ROTATION_0..3 -> 0, 90, 180, 270 degrees. */
    fun surfaceRotationDegrees(surfaceRotation: Int): Int = ((surfaceRotation % 4) + 4) % 4 * 90

    /** Clockwise degrees (0, 90, 180, 270) to rotate the sensor image so it is upright on the display. */
    fun rotationDegrees(sensorOrientation: Int, displayRotationDegrees: Int): Int =
        ((sensorOrientation - displayRotationDegrees) % 360 + 360) % 360

    /** Size of the upright image for a [w] x [h] sensor image rotated by [rot]. */
    fun uprightSize(w: Int, h: Int, rot: Int): IntArray = if (rot % 180 == 0) intArrayOf(w, h) else intArrayOf(h, w)

    /** Sensor-image point (x, y) of a [w] x [h] image -> upright (clockwise rotation by [rot]). Continuous pixel coordinates. */
    fun imageToUpright(x: Float, y: Float, w: Int, h: Int, rot: Int): FloatArray = when (rot) {
        0 -> floatArrayOf(x, y)
        90 -> floatArrayOf(h - y, x)
        180 -> floatArrayOf(w - x, h - y)
        270 -> floatArrayOf(y, w - x)
        else -> throw IllegalArgumentException("rotation must be 0/90/180/270, was $rot")
    }

    /** Exact inverse of [imageToUpright]; [w] x [h] is the SENSOR image size. */
    fun uprightToImage(x: Float, y: Float, w: Int, h: Int, rot: Int): FloatArray = when (rot) {
        0 -> floatArrayOf(x, y)
        90 -> floatArrayOf(y, h - x)
        180 -> floatArrayOf(w - x, h - y)
        270 -> floatArrayOf(w - y, x)
        else -> throw IllegalArgumentException("rotation must be 0/90/180/270, was $rot")
    }

    /** Axis-aligned box (left, top, right, bottom) in the upright image -> axis-aligned box in sensor IMAGE_PIXELS. */
    fun uprightBoxToImage(l: Float, t: Float, r: Float, b: Float, w: Int, h: Int, rot: Int): FloatArray {
        val a = uprightToImage(l, t, w, h, rot); val c = uprightToImage(r, b, w, h, rot)
        return floatArrayOf(minOf(a[0], c[0]), minOf(a[1], c[1]), maxOf(a[0], c[0]), maxOf(a[1], c[1]))
    }

    /**
     * YUV_420_888 planes -> NV21 (Y plane, then interleaved V U at half resolution). Handles row and pixel strides
     * (planar I420 has pixelStride 1, semi-planar NV12/NV21 has 2).
     */
    fun toNv21(
        w: Int, h: Int,
        y: ByteBuffer, yRowStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
    ): ByteArray {
        val out = ByteArray(w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2))
        for (r in 0 until h) {
            val base = r * yRowStride
            for (c in 0 until w) out[r * w + c] = y.get(base + c)
        }
        var o = w * h
        for (r in 0 until (h + 1) / 2) for (c in 0 until (w + 1) / 2) {
            val i = r * uvRowStride + c * uvPixelStride
            out[o++] = v.get(i)
            out[o++] = u.get(i)
        }
        return out
    }
}
