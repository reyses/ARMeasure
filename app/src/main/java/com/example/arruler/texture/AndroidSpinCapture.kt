package com.example.arruler.texture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.SpinCapture
import com.example.arruler.objscan.SpinProgress
import com.example.arruler.objscan.SupportPlane
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Android spin capture: the phone stands on a stand, the object turns. Mirrors [KeyframeCapture] (copy the planes
 * of `Frame.acquireCameraImage()`, JPEG on one background thread, `keyframes.json`) but the selection is [SpinSession]'s:
 * image change inside the box ROI ([SpinKeyframePolicy]), phone-moved detection, two turns. The activity calls [onFrame]
 * for every AR frame while the spin stage runs. Photos go to `<root>/spin_<time>/`, read back with [records] / [dir].
 */
class AndroidSpinCapture(private val root: File) : SpinCapture {
    override val available: Boolean = true

    private val _moved = MutableStateFlow(false)
    override val phoneMoved: StateFlow<Boolean> = _moved.asStateFlow()
    private val _progress = MutableStateFlow<SpinProgress?>(null)
    override val progress: StateFlow<SpinProgress?> = _progress.asStateFlow()
    private val _between = MutableStateFlow(false)
    override val betweenTurns: StateFlow<Boolean> = _between.asStateFlow()
    private val _canEnd = MutableStateFlow(false)
    override val canEndTurn: StateFlow<Boolean> = _canEnd.asStateFlow()

    private var session: SpinSession? = null
    private var encoder: ExecutorService? = null
    private val inFlight = AtomicInteger(0)
    private val written = Collections.synchronizedList(ArrayList<KeyframeRecord>())
    private var issued = 0
    private var lastAcquireNs = Long.MIN_VALUE
    private var cached: List<KeyframeRecord>? = null

    /** Folder of the current / last spin's photos. */
    @Volatile var dir: File? = null
        private set

    val photoCount: Int get() = session?.totalKept ?: 0

    override fun startSpinCapture(box: ObjectBox, plane: SupportPlane, hybrid: Boolean) {
        discard()
        val d = File(root, "spin_${System.currentTimeMillis()}").also { it.mkdirs() }
        dir = d
        session = SpinSession(box)
        encoder = Executors.newSingleThreadExecutor { r -> Thread(r, "spin-encoder").apply { isDaemon = true } }
        issued = 0; lastAcquireNs = Long.MIN_VALUE; cached = null
        written.clear()
        publish()
    }

    /** Stops taking photos (the photos stay until [discard] or the next start); [records] finalises them. */
    override fun stopSpinCapture() {
        session = null
        _moved.value = false
        _progress.value = null
        _between.value = false
        _canEnd.value = false
    }

    override fun nextTurn() {
        val s = session ?: return
        if (_between.value) s.startNextTurn() else s.endTurn()
        publish()
    }

    /** Deletes the photos of the last spin (Reset). */
    fun discard() {
        stopSpinCapture()
        encoder?.shutdownNow()
        encoder = null
        val old = dir
        dir = null
        cached = null
        written.clear()
        if (old != null) Thread { old.deleteRecursively() }.start()
    }

    private fun publish() {
        val s = session ?: return
        _moved.value = s.phoneMoved
        _between.value = s.stage == SpinStage.BETWEEN
        _canEnd.value = s.canEndTurn
        _progress.value = SpinProgress(s.turn, s.turns, s.keptInTurn)
    }

    /** Main thread, once per AR frame of the spin stage. */
    fun onFrame(frame: Frame) {
        val s = session ?: return
        if (s.stage != SpinStage.SPINNING) return
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) return
        if (frame.timestamp - lastAcquireNs < MIN_INTERVAL_NS || inFlight.get() >= 2) return
        lastAcquireNs = frame.timestamp
        val pose = FloatArray(16)
        cam.pose.toMatrix(pose, 0)
        val ik = cam.imageIntrinsics
        val dims = ik.imageDimensions
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
            val intr = Intrinsics(ik.focalLength[0], ik.focalLength[1], ik.principalPoint[0], ik.principalPoint[1], dims[0], dims[1]).scaledTo(w, h)
            val py = image.planes[0]
            val yBytes = ByteArray(py.buffer.remaining()).also { py.buffer.get(it) }
            val sharp = Sharpness.laplacianVariance(yBytes, w, h, py.rowStride, py.pixelStride)
            val verdict = s.onImage(true, pose, intr, yBytes, py.rowStride, py.pixelStride, sharp)
            publish()
            if (verdict != SpinVerdict.KEEP) return
            val pu = image.planes[1]; val pv = image.planes[2]
            val u = ByteArray(pu.buffer.remaining()).also { pu.buffer.get(it) }
            val v = ByteArray(pv.buffer.remaining()).also { pv.buffer.get(it) }
            val index = ++issued
            val ts = frame.timestamp
            val uvRow = pu.rowStride; val uvPix = pu.pixelStride; val yRow = py.rowStride
            val outDir = dir ?: return
            val exec = encoder ?: return
            inFlight.incrementAndGet()
            exec.execute {
                try {
                    val nv21 = YuvConvert.toNv21(yBytes, yRow, u, v, uvRow, uvPix, w, h)
                    val name = KeyframeStore.fileName(index)
                    FileOutputStream(File(outDir, name)).use { out ->
                        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
                    }
                    written.add(KeyframeRecord(name, ts, pose.toList(), intr.fx, intr.fy, intr.cx, intr.cy, w, h, sharp))
                } catch (e: Exception) {
                    Log.w(TAG, "spin photo $index encode failed: ${e.message}")
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        } finally {
            image.close()
        }
    }

    /** Waits for pending JPEG encodes, writes keyframes.json and returns the photos in capture order. Safe to call twice. */
    suspend fun records(): List<KeyframeRecord> = withContext(Dispatchers.IO) {
        synchronized(this@AndroidSpinCapture) {
            cached ?: run {
                val e = encoder
                if (e != null) { e.shutdown(); e.awaitTermination(20, TimeUnit.SECONDS) }
                val list = synchronized(written) { written.sortedBy { it.file } }
                dir?.let { KeyframeStore.write(it, list) }
                list.also { cached = it }
            }
        }
    }

    private companion object {
        const val TAG = "AndroidSpinCapture"
        const val MIN_INTERVAL_NS = 120_000_000L
        const val JPEG_QUALITY = 90
    }
}
