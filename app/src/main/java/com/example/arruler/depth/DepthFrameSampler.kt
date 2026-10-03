package com.example.arruler.depth

import android.media.Image
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

/** World-space points from one frame: packed xyz (meters) and a parallel confidence in 0..1. */
class DepthSample(val xyz: FloatArray, val confidence: FloatArray) {
    val count: Int get() = confidence.size
}

/**
 * Turns one ARCore [Frame]'s raw depth + raw confidence into world-space points.
 * Needs Config.DepthMode.AUTOMATIC or RAW_DEPTH_ONLY on the session. Main-thread / callback use:
 * the Frame and its Images are only valid inside the session-update callback.
 *
 * Image geometry: raw depth (DEPTH16, 2 bytes little-endian per pixel, millimeters) and confidence
 * (Y8) share a size and are aligned with the unrotated CPU image, so they pair with
 * `Camera.getImageIntrinsics()` (scaled to the depth size) and `Camera.getPose()` (sensor-aligned
 * camera pose), not the display-oriented ones.
 */
class DepthFrameSampler(
    /** Take every [step]-th pixel in both directions. */
    private val step: Int = 2,
    /** Minimum confidence as a fraction of 255. */
    private val minConfidence: Float = 0.4f,
    private val minDepthM: Float = 0.3f,
    private val maxDepthM: Float = 6f
) {
    init { require(step >= 1) }

    /** Returns null when depth is not available yet (warm-up, disabled mode, unsupported). */
    fun sample(frame: Frame): DepthSample? {
        var depth: Image? = null
        var conf: Image? = null
        try {
            try {
                depth = frame.acquireRawDepthImage16Bits()
                conf = frame.acquireRawDepthConfidenceImage()
            } catch (_: NotYetAvailableException) {
                return null
            } catch (_: IllegalStateException) {
                // depth mode disabled -> ARCore throws; treat as unavailable
                return null
            }
            val camera = frame.camera
            if (camera.trackingState != com.google.ar.core.TrackingState.TRACKING) return null

            val w = depth.width; val h = depth.height
            val ci = camera.imageIntrinsics
            val dims = ci.imageDimensions
            val fl = ci.focalLength; val pp = ci.principalPoint
            val k = DepthMath.scaleIntrinsics(Intrinsics(fl[0], fl[1], pp[0], pp[1], dims[0], dims[1]), w, h)

            val pose = FloatArray(16)
            camera.pose.toMatrix(pose, 0)

            val dPlane = depth.planes[0]
            val cPlane = conf.planes[0]
            val dBuf = dPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val cBuf = cPlane.buffer
            val dRow = dPlane.rowStride; val dPix = dPlane.pixelStride
            val cRow = cPlane.rowStride; val cPix = cPlane.pixelStride

            val maxPts = ((w + step - 1) / step) * ((h + step - 1) / step)
            val xyz = FloatArray(maxPts * 3)
            val cf = FloatArray(maxPts)
            val tmp = FloatArray(3)
            val minMm = (minDepthM * 1000f).toInt(); val maxMm = (maxDepthM * 1000f).toInt()
            val minC = (minConfidence * 255f).toInt()
            var n = 0
            var v = 0
            while (v < h) {
                var u = 0
                while (u < w) {
                    val mm = dBuf.getShort(v * dRow + u * dPix).toInt() and 0xFFFF
                    if (mm in minMm..maxMm) {
                        val c = cBuf.get(v * cRow + u * cPix).toInt() and 0xFF
                        if (c >= minC) {
                            DepthMath.unproject(u.toFloat(), v.toFloat(), mm, k, tmp)
                            DepthMath.transformPoint(pose, tmp[0], tmp[1], tmp[2], xyz, n * 3)
                            cf[n] = c / 255f
                            n++
                        }
                    }
                    u += step
                }
                v += step
            }
            return DepthSample(xyz.copyOf(n * 3), cf.copyOf(n))
        } finally {
            conf?.close()
            depth?.close()
        }
    }
}
