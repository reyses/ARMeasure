package com.example.arruler.beam

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import java.io.File

/** Pure parts of the snapshot ring: geometry and file naming. */
object SnapGeometry {
    const val WIDTH_PX = 540
    const val JPEG_QUALITY = 70
    const val INTERVAL_MS = 2000L
    const val RING = 300

    /** Target size for a window of [w] x [h] px: 540 px wide, aspect kept, both at least 2. */
    fun target(w: Int, h: Int, targetW: Int = WIDTH_PX): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return targetW to targetW
        val tw = minOf(targetW, w)
        return tw to maxOf(2, Math.round(h.toFloat() * tw / w))
    }
}

/** Last [max] snapshots of one session in [dir]: snap-<wallMs>-<monoMs>.jpg, oldest pruned. */
class SnapRing(private val dir: File, private val max: Int = SnapGeometry.RING) {
    fun file(wallMs: Long, monoMs: Long): File = File(dir, "snap-%013d-%d.jpg".format(wallMs, monoMs))

    fun add(wallMs: Long, monoMs: Long, jpeg: ByteArray): File {
        dir.mkdirs()
        val f = file(wallMs, monoMs)
        f.writeBytes(jpeg)
        prune()
        return f
    }

    fun list(): List<File> = (dir.listFiles { x -> x.isFile && x.name.startsWith("snap-") && x.name.endsWith(".jpg") } ?: emptyArray()).sortedBy { it.name }

    fun prune() {
        val l = list()
        for (f in l.take((l.size - max).coerceAtLeast(0))) f.delete()
    }
}

/**
 * Grabs the app window with PixelCopy (API 26+, includes SurfaceView content such as the AR camera view), downscaled to
 * 540 px wide, JPEG q70, into the active session's ring. No permission is needed. The loop only runs while [active] says so.
 */
class ScreenSnap(
    private val activity: () -> Activity?,
    private val ring: () -> SnapRing?,
    private val clock: BeamClock = SystemBeamClock,
    private val onSnap: (File) -> Unit = {},
) {
    private val thread = HandlerThread("beam-snap").also { it.start() }
    private val bg = Handler(thread.looper)
    @Volatile private var running = false
    @Volatile private var inFlight = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            snapNow()
            bg.postDelayed(this, SnapGeometry.INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        bg.post(tick)
    }

    fun stop() {
        running = false
        bg.removeCallbacks(tick)
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    /** One snapshot now (also used around errors). Skips silently when no window, no ring, API < 26 or one is in flight. */
    fun snapNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || inFlight) return
        val act = activity() ?: return
        val r = ring() ?: return
        try {
            val win = act.window ?: return
            val decor = win.decorView
            if (decor.width <= 0 || decor.height <= 0 || !decor.isAttachedToWindow) return
            val (tw, th) = SnapGeometry.target(decor.width, decor.height)
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val wall = clock.wallMs()
            val mono = clock.monoMs()
            inFlight = true
            PixelCopy.request(win, bmp, { code ->
                try {
                    if (code == PixelCopy.SUCCESS) {
                        val out = ByteArrayOutputStream(64 * 1024)
                        bmp.compress(Bitmap.CompressFormat.JPEG, SnapGeometry.JPEG_QUALITY, out)
                        onSnap(r.add(wall, mono, out.toByteArray()))
                    }
                } catch (_: Throwable) {
                } finally {
                    bmp.recycle()
                    inFlight = false
                }
            }, bg)
        } catch (_: Throwable) {
            inFlight = false
        }
    }

    companion object {
        fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block) }
    }
}
