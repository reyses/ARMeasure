package com.example.arruler.texture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.example.arruler.geometry.Vec3
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures photo keyframes during an object scan (walk-around). Call [onFrame] from the session's per-frame
 * callback (main thread); it is cheap unless the geometry rules pass, then it copies the YUV planes, scores
 * sharpness and hands JPEG encoding to a single background thread. Output: `kf_000001.jpg ...` and `keyframes.json`
 * in [dir] (see [KeyframeStore]). The pose is `Camera.getPose()` (physical camera), intrinsics are scaled to the image size.
 */
class KeyframeCapture(
    private val dir: File,
    private val policy: KeyframePolicy = KeyframePolicy(),
    private val jpegQuality: Int = 90,
) {
    private val encoder: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "keyframe-encoder").apply { isDaemon = true } }
    private val inFlight = AtomicInteger(0)
    private val records = Collections.synchronizedList(ArrayList<KeyframeRecord>())
    private var lastAcquireNs = Long.MIN_VALUE
    private var issued = 0

    val keptCount: Int get() = policy.keptCount

    /** Per-verdict counters for the UI / logs. */
    val verdicts = java.util.EnumMap<KeyframeVerdict, Int>(KeyframeVerdict::class.java)

    private fun count(v: KeyframeVerdict) { verdicts[v] = (verdicts[v] ?: 0) + 1 }

    init { dir.mkdirs() }

    fun onFrame(frame: Frame, boxCentre: Vec3) {
        val cam = frame.camera
        val tracking = cam.trackingState == TrackingState.TRACKING
        if (!tracking) { count(KeyframeVerdict.NOT_TRACKING); return }
        if (frame.timestamp - lastAcquireNs < MIN_INTERVAL_NS || inFlight.get() >= 2) return
        val pose = FloatArray(16)
        cam.pose.toMatrix(pose, 0)
        val ik = cam.imageIntrinsics
        val dims = ik.imageDimensions
        val f = ik.focalLength
        val pp = ik.principalPoint
        val intr = Intrinsics(f[0], f[1], pp[0], pp[1], dims[0], dims[1])
        val g = policy.checkGeometry(true, pose, intr, boxCentre)
        if (g != KeyframeVerdict.KEEP) { count(g); return }
        lastAcquireNs = frame.timestamp

        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return
        } catch (e: Exception) {
            Log.w(TAG, "acquireCameraImage failed: ${e.message}")
            return
        }
        try {
            if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return
            val w = image.width; val h = image.height
            val py = image.planes[0]
            val yBytes = ByteArray(py.buffer.remaining()).also { py.buffer.get(it) }
            val sharp = Sharpness.laplacianVariance(yBytes, w, h, py.rowStride, py.pixelStride)
            val scaled = intr.scaledTo(w, h)
            val verdict = policy.decide(true, pose, scaled, boxCentre, sharp)
            count(verdict)
            if (verdict != KeyframeVerdict.KEEP) return
            val pu = image.planes[1]; val pv = image.planes[2]
            val u = ByteArray(pu.buffer.remaining()).also { pu.buffer.get(it) }
            val v = ByteArray(pv.buffer.remaining()).also { pv.buffer.get(it) }
            val index = ++issued
            val ts = frame.timestamp
            val uvRow = pu.rowStride; val uvPix = pu.pixelStride; val yRow = py.rowStride
            inFlight.incrementAndGet()
            encoder.execute {
                try {
                    val nv21 = YuvConvert.toNv21(yBytes, yRow, u, v, uvRow, uvPix, w, h)
                    val name = KeyframeStore.fileName(index)
                    FileOutputStream(File(dir, name)).use { out ->
                        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), jpegQuality, out)
                    }
                    records.add(KeyframeRecord(name, ts, pose.toList(), scaled.fx, scaled.fy, scaled.cx, scaled.cy, w, h, sharp))
                } catch (e: Exception) {
                    Log.w(TAG, "keyframe $index encode failed: ${e.message}")
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        } finally {
            image.close()
        }
    }

    /** Waits for pending encodes (up to [timeoutMs]), writes `keyframes.json` and returns the records in capture order. */
    fun finish(timeoutMs: Long = 20_000): List<KeyframeRecord> {
        encoder.shutdown()
        encoder.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        val list = synchronized(records) { records.sortedBy { it.file } }
        KeyframeStore.write(dir, list)
        return list
    }

    companion object {
        private const val TAG = "KeyframeCapture"
        private const val MIN_INTERVAL_NS = 200_000_000L
    }
}
