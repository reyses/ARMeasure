package com.example.arruler.beam

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.arruler.BuildConfig
import com.example.arruler.devlink.TailBuffer
import com.example.arruler.diag.DeviceReportCollector
import com.example.arruler.diag.DeviceReportData
import com.example.arruler.diag.ReportFormat
import com.example.arruler.gpu.GpuGate
import com.example.arruler.processing.PairingInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Beam"

/** Debug build: the real Beam. */
object BeamEntries {
    val entry: BeamEntry = DebugBeamEntry
}

private object DebugBeamEntry : BeamEntry {
    override val enabled = true

    private lateinit var app: Application
    private lateinit var settings: BeamSettings
    private lateinit var root: File
    private lateinit var queue: BeamQueue
    @Volatile private var ready = false
    private var launchMs = 0L
    @Volatile private var policy = BeamPolicy()
    @Volatile private var paired = false

    @Volatile private var appSession: BeamSession? = null
    @Volatile private var runSession: BeamSession? = null
    @Volatile private var captureActive = false
    @Volatile private var activity: Activity? = null
    private val started = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "beam-bundle") }
    private val hit = HitQualityDebouncer()
    private val fps = FpsAggregator()
    private var snap: ScreenSnap? = null
    @Volatile private var diagnosticsCache: String? = null

    private val active: List<BeamSession> get() = listOfNotNull(appSession, runSession)
    private val recording: Boolean get() = ready && policy.beamToPc && paired

    override fun onCreate(app: Application) {
        if (ready) return
        this.app = app
        launchMs = System.currentTimeMillis()
        settings = BeamSettings(app)
        root = File(app.filesDir, "beam/sessions")
        queue = BeamQueue(BeamUpload.queueDir(app))
        policy = settings.read()
        // Before DevEntries uploads and deletes it: keep the previous run's crash text for its (orphaned) session.
        try {
            val crash = File(app.filesDir, "crash/last.txt")
            if (crash.isFile) crash.copyTo(File(app.filesDir, "beam/pending-crash.txt"), overwrite = true)
        } catch (_: Throwable) {
        }
        snap = ScreenSnap({ activity }, { snapRing() })
        ready = true
        installCrashHook()
        app.registerActivityLifecycleCallbacks(Lifecycle())
        registerThermal(app)
        worker.execute { recoverSessions() }
    }

    override fun onPairing(context: Context, pairing: PairingInfo?) {
        if (!ready) return
        paired = pairing != null
        if (paired && started.get() > 0 && appSession == null) startAppSession()
        if (paired) BeamUpload.kick(app)
    }

    // ---------------------------------------------------------------- events

    override fun event(type: String, data: Map<String, Any?>) {
        if (!recording) return
        when (type) {
            "frame" -> { fps.onFrame(System.nanoTime() / 1_000_000L)?.let { deliver(EventTypes.FPS, it) }; return }
            EventTypes.HIT_QUALITY -> if (!hit.shouldEmit(data["quality"]?.toString() ?: "", data["kind"]?.toString() ?: "")) return
            EventTypes.CAPTURE_START -> { captureActive = true; updateSnap() }
            EventTypes.CAPTURE_STOP -> { captureActive = false; updateSnap() }
        }
        deliver(type, data)
        if (type == EventTypes.ERROR) snap?.snapNow()
    }

    private fun deliver(type: String, data: Map<String, Any?>) {
        for (s in active) s.timeline.event(type, data)
    }

    override fun startRun(kind: String, label: String): String {
        if (!recording) return ""
        endRun("replaced")
        val s = BeamSession.create(root, kind, label)
        runSession = s
        s.timeline.event(EventTypes.SESSION_START, mapOf("kind" to kind, "label" to label, "id" to s.id))
        hit.reset()
        updateSnap()
        return s.id
    }

    override fun endRun(reason: String) {
        val s = runSession ?: return
        runSession = null
        finishSession(s, reason)
        updateSnap()
    }

    override fun attach(role: String, file: File) {
        for (s in active) s.attach(role, file)
        // An export is also an event the PC timeline shows.
        if (role == BundleRoles.EXPORT) deliver(EventTypes.EXPORT, mapOf("files" to listOf(file.name)))
    }

    override fun snapNow(reason: String) {
        if (recording && policy.includeScreenSnapshots) snap?.snapNow()
    }

    // ---------------------------------------------------------------- sessions

    private fun startAppSession() {
        if (!recording || appSession != null) return
        val s = BeamSession.create(root, "app", "")
        appSession = s
        s.timeline.event(EventTypes.SESSION_START, mapOf("kind" to "app", "id" to s.id))
        s.timeline.event(EventTypes.APP_START, mapOf("commit" to BuildConfig.GIT_COMMIT, "version" to BuildConfig.VERSION_NAME,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}", "api" to Build.VERSION.SDK_INT))
        updateSnap()
    }

    private fun endAppSession(reason: String) {
        val s = appSession ?: return
        appSession = null
        s.timeline.event(EventTypes.APP_STOP, mapOf("reason" to reason))
        runSession?.let { r -> runSession = null; finishSession(r, "app_background") }
        finishSession(s, reason)
        updateSnap()
    }

    private fun finishSession(s: BeamSession, reason: String) {
        s.timeline.event(EventTypes.SESSION_END, mapOf("reason" to reason, "events" to s.timeline.total))
        try {
            if (ScreenRecord.recording) ScreenRecord.stopAndGet(app)?.let { s.attach(BundleRoles.SCREEN_RECORDING, it) }
        } catch (_: Throwable) {
        }
        s.close(reason)
        worker.execute { bundleAndQueue(s.dir, null) }
    }

    private fun snapRing(): SnapRing? = (runSession ?: appSession)?.let { SnapRing(it.snapDir) }

    private fun updateSnap() {
        val want = recording && policy.includeScreenSnapshots && (runSession != null || (appSession != null && captureActive))
        if (want) snap?.start() else snap?.stop()
    }

    /** Builds the ZIP for one finished session directory, queues it, deletes the directory, kicks the uploader. */
    private fun bundleAndQueue(dir: File, crashText: String?) {
        try {
            val rec = BeamSession.load(dir) ?: run { dir.deleteRecursively(); return }
            if (!policy.beamToPc || !SessionBundler.worthSending(rec)) { dir.deleteRecursively(); return }
            val texts = BundleTexts(logcat = logcat(), diagnostics = diagnostics(), crash = crashText)
            val id = BundleIdentity(app.packageName, BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT)
            val tmp = File(app.filesDir, "beam/tmp").apply { mkdirs() }
            val zip = File(tmp, rec.meta.id + ".zip")
            SessionBundler.build(rec, id, texts, policy, zip, System.currentTimeMillis())
            queue.enqueue(zip, rec.meta.id)
            queue.prune()
            dir.deleteRecursively()
            BeamUpload.kick(app)
        } catch (e: Throwable) {
            Log.w(TAG, "bundle failed for ${dir.name}: ${e.message}")
        }
    }

    /** At launch: bundle every session the previous run left (closed ones as they are, open ones as "crash"). */
    private fun recoverSessions() {
        try {
            val pendingCrash = File(app.filesDir, "beam/pending-crash.txt")
            val crashText = pendingCrash.takeIf { it.isFile }?.readText()
            // Only what an earlier run left: a session of THIS run has a meta.json written after launch.
            val dirs = root.listFiles { f -> f.isDirectory && File(f, BeamSession.META).lastModified() < launchMs }?.sortedBy { it.name } ?: emptyList()
            val orphans = BeamSession.orphans(root).map { it.name }.filter { n -> dirs.any { it.name == n } }.toSet()
            for (d in dirs) {
                if (d.name in orphans) {
                    // The timeline file's last write is the best end time we have.
                    val end = File(d, BeamSession.TIMELINE).lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                    try { File(d, BeamSession.CLOSED).writeText("""{"reason":"crash","endWallMs":$end}""") } catch (_: Throwable) { }
                }
            }
            val crashOwner = orphans.lastOrNull()
            for (d in dirs) bundleAndQueue(d, if (d.name == crashOwner) crashText else null)
            pendingCrash.delete()
            BeamUpload.kick(app)
        } catch (e: Throwable) {
            Log.w(TAG, "recovery failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- text collectors

    private fun logcat(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
        val tail = TailBuffer(LOGCAT_BYTES)
        p.inputStream.use { s ->
            val buf = ByteArray(32 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; tail.write(buf, 0, n) }
        }
        p.waitFor()
        String(tail.bytes(), Charsets.UTF_8)
    } catch (e: Exception) {
        "logcat unavailable: ${e.message}"
    }

    /** Device, GL and camera report without an ARCore session (a second session would fight the live AR view for the camera). */
    private fun diagnostics(): String {
        diagnosticsCache?.let { return it }
        val text = try {
            val data = DeviceReportData(
                runCatching { DeviceReportCollector.basics(app) }.getOrNull(), runCatching { DeviceReportCollector.gl(app) }.getOrNull(),
                null, runCatching { DeviceReportCollector.cameras(app) }.getOrNull(),
            )
            ReportFormat.format(data, listOf(GpuGate.statusLine()), System.currentTimeMillis())
        } catch (e: Throwable) {
            "diagnostics unavailable: ${e.message}"
        }
        diagnosticsCache = text
        return text
    }

    // ---------------------------------------------------------------- hooks

    private fun installCrashHook() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                if (recording) {
                    deliver(EventTypes.ERROR, mapOf("message" to (e.message ?: e.javaClass.name), "stack" to e.stackTraceToString(), "thread" to t.name, "fatal" to true))
                }
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun registerThermal(app: Application) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.addThermalStatusListener(app.mainExecutor) { status -> event(EventTypes.THERMAL, mapOf("status" to status)) }
        } catch (_: Throwable) {
        }
    }

    /** Foreground tracking: the app session lasts from the first started activity to 1.5 s after the last one stopped. */
    private class Lifecycle : Application.ActivityLifecycleCallbacks {
        private val endLater = Runnable { if (started.get() == 0) endAppSession("background") }

        override fun onActivityStarted(a: Activity) {
            main.removeCallbacks(endLater)
            if (started.incrementAndGet() == 1) startAppSession()
        }

        override fun onActivityStopped(a: Activity) {
            if (started.decrementAndGet() <= 0) {
                started.set(0)
                main.postDelayed(endLater, 1500)
            }
        }

        override fun onActivityResumed(a: Activity) { activity = a }
        override fun onActivityPaused(a: Activity) { if (activity === a) activity = null }
        override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    }

    // ---------------------------------------------------------------- settings UI

    @Composable
    override fun SettingsSection(pairing: PairingInfo?) {
        val ctx = LocalContext.current.applicationContext
        val store = remember { BeamSettings(ctx) }
        var p by remember { mutableStateOf(store.read()) }
        val status by BeamUpload.status.collectAsState()
        fun set(n: BeamPolicy) { p = n; store.write(n); policy = n; updateSnap(); BeamUpload.kick(ctx) }
        @Composable
        fun row(label: String, value: Boolean, change: (Boolean) -> Unit) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = value, onCheckedChange = change)
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (pairing == null) "Pair a PC first: nothing is recorded until then."
                else "Sends diagnostics, screen snapshots and camera video of a session to your own PC only (tailnet or LAN). Off = nothing recorded.",
                style = MaterialTheme.typography.bodySmall,
            )
            row("Beam to PC", p.beamToPc) { set(p.copy(beamToPc = it)) }
            row("Wi-Fi only", p.wifiOnly) { set(p.copy(wifiOnly = it)) }
            row("Allow over the public tunnel", p.allowOverTunnel) { set(p.copy(allowOverTunnel = it)) }
            row("Include camera video", p.includeCameraVideo) { set(p.copy(includeCameraVideo = it)) }
            row("Include screen snapshots", p.includeScreenSnapshots) { set(p.copy(includeScreenSnapshots = it)) }
            row("Full screen recording", p.fullScreenRecording) {
                set(p.copy(fullScreenRecording = it))
                if (it) ScreenRecord.requestStart(ctx) else worker.execute { ScreenRecord.stopAndGet(ctx)?.delete() }
            }
            if (p.fullScreenRecording && !ScreenRecord.recording) {
                OutlinedButton(onClick = { ScreenRecord.requestStart(ctx) }) { Text("Start screen recording (system prompt)") }
            }
            Button(onClick = { endAppSession("manual"); if (started.get() > 0) startAppSession() }) { Text("Send this session now") }
            if (status.isNotEmpty()) Text("Upload: $status", style = MaterialTheme.typography.bodySmall)
        }
    }

    private const val LOGCAT_BYTES = 1024 * 1024
}
