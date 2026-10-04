package com.example.arruler.beam

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pure: the recording size for a display, 720 px on the short side, even numbers, aspect kept. */
object ScreenRecordGeometry {
    const val SHORT_SIDE = 720
    const val BITRATE = 4_000_000
    const val FPS = 30

    fun size(displayW: Int, displayH: Int): Pair<Int, Int> {
        if (displayW <= 0 || displayH <= 0) return 720 to 1280
        val short = minOf(displayW, displayH)
        val scale = minOf(1.0, SHORT_SIDE.toDouble() / short)
        fun even(v: Double) = maxOf(2, (Math.round(v) / 2 * 2).toInt())
        return even(displayW * scale) to even(displayH * scale)
    }
}

/** State shared between the settings UI, the consent activity and the service (all in one process). */
object ScreenRecord {
    @Volatile var recording = false
        internal set
    @Volatile private var finished: File? = null
    @Volatile private var latch: CountDownLatch? = null

    /** Opens the system consent prompt (a translucent activity); the recording starts when the user accepts. */
    fun requestStart(context: Context) {
        if (recording) return
        context.startActivity(Intent(context, BeamConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Asks the service to stop and waits up to [waitMs] for the finished MP4; null when nothing was recording. */
    fun stopAndGet(context: Context, waitMs: Long = 4000): File? {
        if (!recording) return finished.also { finished = null }
        val l = CountDownLatch(1)
        latch = l
        context.startService(Intent(context, BeamProjectionService::class.java).setAction(BeamProjectionService.ACTION_STOP))
        l.await(waitMs, TimeUnit.MILLISECONDS)
        return finished.also { finished = null }
    }

    internal fun done(file: File?) {
        recording = false
        finished = file
        latch?.countDown()
    }
}

/** Translucent, no UI of its own: shows the system MediaProjection consent prompt and hands the grant to the service. */
class BeamConsentActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, BeamProjectionService::class.java).setAction(BeamProjectionService.ACTION_START)
                .putExtra(BeamProjectionService.EXTRA_CODE, r.resultCode).putExtra(BeamProjectionService.EXTRA_DATA, data)
            try { startForegroundService(i) } catch (e: Throwable) { Log.w("Beam", "cannot start the projection service: ${e.message}") }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mpm.createScreenCaptureIntent())
        }
    }
}

/** Foreground service (type mediaProjection, debug manifest only): records the screen to MP4, 720p 4 Mbps, no audio. */
class BeamProjectionService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var out: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (ScreenRecord.recording) return
        startForegroundNow()
        try {
            val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION") val data: Intent = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_DATA)) ?: return fail("no grant")
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data) ?: return fail("no projection")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stop() } }, null)
            val (w, h, dpi) = displayInfo()
            val (rw, rh) = ScreenRecordGeometry.size(w, h)
            val dir = File(filesDir, "beam/screen").apply { mkdirs() }
            val f = File(dir, "screen-${System.currentTimeMillis()}.mp4")
            out = f
            @Suppress("DEPRECATION") val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(rw, rh)
            r.setVideoFrameRate(ScreenRecordGeometry.FPS)
            r.setVideoEncodingBitRate(ScreenRecordGeometry.BITRATE)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            recorder = r
            display = p.createVirtualDisplay("beam-screen", rw, rh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
            r.start()
            ScreenRecord.recording = true
        } catch (e: Throwable) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(why: String) {
        Log.w("Beam", "screen recording failed: $why")
        release()
        ScreenRecord.done(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stop() {
        val f = out
        var ok = false
        try { recorder?.stop(); ok = true } catch (_: Throwable) { }
        release()
        ScreenRecord.done(if (ok && f != null && f.length() > 0) f else null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        try { display?.release() } catch (_: Throwable) { }
        try { recorder?.release() } catch (_: Throwable) { }
        try { projection?.stop() } catch (_: Throwable) { }
        display = null; recorder = null; projection = null
    }

    override fun onDestroy() {
        if (ScreenRecord.recording) stop()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun displayInfo(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), resources.displayMetrics.densityDpi)
        } else {
            val m = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, m.densityDpi)
        }
    }

    private fun startForegroundNow() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Beam screen recording", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Beam is recording the screen").setContentText("Stops when the Beam session ends.").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(ID, n)
    }

    companion object {
        const val ACTION_START = "com.example.arruler.beam.SCREEN_START"
        const val ACTION_STOP = "com.example.arruler.beam.SCREEN_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "beam_screen"
        private const val ID = 7301
    }
}
