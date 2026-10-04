package com.example.arruler.devlink

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.arruler.BuildConfig
import com.example.arruler.diag.DeviceReportCollector
import com.example.arruler.diag.DeviceReportData
import com.example.arruler.diag.ReportFormat
import com.example.arruler.gpu.GpuGate
import com.example.arruler.processing.HttpPcLink
import com.example.arruler.processing.PairingInfo
import com.example.arruler.processing.PcLinkException
import com.example.arruler.processing.ProcJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DevLink"

/** Debug build: the real dev link. */
object DevEntries {
    val entry: DevEntry = DebugDevEntry
}

private object DebugDevEntry : DevEntry {
    override val enabled = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendingCrash = AtomicBoolean(false)

    override fun onCreate(app: Application) {
        CrashRecorder.install(File(app.filesDir, "crash"))
    }

    override fun onPairing(context: Context, pairing: PairingInfo?) {
        if (pairing == null) return
        val app = context.applicationContext
        val store = CrashStore(File(app.filesDir, "crash"))
        if (store.pending() == null || !sendingCrash.compareAndSet(false, true)) return
        scope.launch {
            try {
                DevLinkClient(app, pairing).sendCrashIfAny(store)
            } catch (e: Exception) {
                Log.w(TAG, "crash upload failed: ${e.message}")
            } finally {
                sendingCrash.set(false)
            }
        }
    }

    override fun recordError(context: Context, where: String, error: Throwable) {
        try {
            CrashStore(File(context.applicationContext.filesDir, "crash"))
                .write("handled in $where (${Thread.currentThread().name})", error, System.currentTimeMillis(), BuildConfig.GIT_COMMIT)
        } catch (e: Exception) {
            Log.w(TAG, "could not record the error: ${e.message}")
        }
    }

    override suspend fun sendReport(context: Context, pairing: PairingInfo?, text: String): String? {
        if (pairing == null) return null
        return DevLinkClient(context.applicationContext, pairing).sendText("crash", text) // the PC accepts logs|crash|diagnostics; a handled error is filed as a crash report
    }

    @Composable
    override fun SettingsSection(pairing: PairingInfo?) {
        val ctx = LocalContext.current.applicationContext
        val scope = rememberCoroutineScope()
        val installMsg by InstallStatus.message.collectAsState()
        var busy by remember { mutableStateOf(false) }
        var updateText by remember { mutableStateOf("") }
        var progress by remember { mutableStateOf<Float?>(null) }
        var logsText by remember { mutableStateOf("") }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Build commit ${BuildConfig.GIT_COMMIT}", style = MaterialTheme.typography.bodyMedium)
            if (pairing == null) {
                Text("Pair a PC first.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val active = HttpPcLink.lastWorkingUrl(pairing)
                val shown = active ?: pairing.allUrls().first()
                Text(
                    "PC URL in use: $shown (${UrlKind.label(shown)}${if (active == null) ", not contacted yet" else ""})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                enabled = pairing != null && !busy,
                onClick = {
                    busy = true; progress = null; updateText = "Checking the PC..."
                    scope.launch {
                        updateText = try {
                            DevLinkClient(ctx, pairing!!).updateFromPc { progress = it }
                        } catch (e: Exception) {
                            "Update failed: ${e.message}"
                        }
                        progress = null; busy = false
                    }
                },
            ) { Text("Update from PC") }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            if (updateText.isNotEmpty()) Text(updateText, style = MaterialTheme.typography.bodySmall)
            if (installMsg.isNotEmpty()) Text(installMsg, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                enabled = pairing != null && !busy,
                onClick = {
                    busy = true; logsText = "Collecting logs and diagnostics..."
                    scope.launch {
                        logsText = try {
                            DevLinkClient(ctx, pairing!!).sendLogsAndDiagnostics()
                        } catch (e: Exception) {
                            "Send failed: ${e.message}"
                        }
                        busy = false
                    }
                },
            ) { Text("Send logs + diagnostics to PC") }
            if (logsText.isNotEmpty()) Text(logsText, style = MaterialTheme.typography.bodySmall)
        }
    }
}


/** Why an install did not start or finish, filled by [InstallResultReceiver]. */
object InstallStatus {
    val message = MutableStateFlow("")
}

/** Records the stack trace of an uncaught exception to filesDir/crash/last.txt, then lets the previous handler run. */
object CrashRecorder {
    private class Handler(private val store: CrashStore, private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                store.write(t.name, e, System.currentTimeMillis(), BuildConfig.GIT_COMMIT)
            } catch (_: Throwable) {
                // never mask the original crash
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun install(dir: File) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(CrashStore(dir), prev))
    }
}

/** The network and system side of the dev link, for one pairing. */
class DevLinkClient(private val app: Context, private val pairing: PairingInfo) {
    private val link = HttpPcLink(pairing)

    private fun device() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Ask the PC, download, verify, hand to the system installer. Returns the line to show. */
    suspend fun updateFromPc(onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(app.packageName, "UTF-8")
        val text = try {
            link.getText("/v1/dev/apk?package=$enc")
        } catch (e: PcLinkException) {
            return@withContext if (e.httpStatus == 404) "The PC has no APK for ${app.packageName}" else "PC: ${e.message}"
        }
        val info = ProcJson.json.decodeFromString<ApkInfo>(text)
        when (val d = UpdateDecision.decide(BuildConfig.GIT_COMMIT, installedVersionCode(), info)) {
            is UpdateCheck.UpToDate -> UpdateDecision.upToDateText(BuildConfig.GIT_COMMIT)
            is UpdateCheck.Unusable -> "Cannot update: ${d.why}"
            is UpdateCheck.Available -> {
                val dir = File(app.cacheDir, "dev-update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, "update.apk")
                link.downloadTo(d.info.url, dest, onProgress)
                if (!Sha256.verify(dest, d.info.sha256, d.info.size)) {
                    dest.delete()
                    return@withContext "Download failed the sha256 / size check; not installing"
                }
                if (!ApkInstaller.canInstall(app)) {
                    ApkInstaller.openUnknownSourcesSettings(app)
                    return@withContext "Allow ARMeasure to install apps in the settings page that opened, then tap Update again"
                }
                InstallStatus.message.value = ""
                ApkInstaller.install(app, dest)
                "Downloaded commit ${d.info.commit} (${d.info.size / 1024} KB, sha256 ok); confirm the install on screen"
            }
        }
    }

    /** Logcat of this process plus the diagnostics report, as two uploads. Returns the line with both ids. */
    suspend fun sendLogsAndDiagnostics(): String = withContext(Dispatchers.IO) {
        val logsId = upload("logs", (header() + "\n" + logcat()).toByteArray(Charsets.UTF_8).let { trimTail(it) })
        val diag = try {
            val data = try { DeviceReportCollector.collectAll(app) } catch (e: Throwable) { DeviceReportData(null, null, null, null) }
            header() + "\n" + ReportFormat.format(data, listOf(GpuGate.statusLine()), System.currentTimeMillis())
        } catch (e: Throwable) {
            header() + "\ndiagnostics unavailable: ${e.message}"
        }
        val diagId = upload("diagnostics", diag.toByteArray(Charsets.UTF_8))
        "Sent. logs id $logsId, diagnostics id $diagId"
    }

    /** One text upload of [kind] with the app/device header in front. Returns the line to show. */
    suspend fun sendText(kind: String, text: String): String = withContext(Dispatchers.IO) {
        val id = upload(kind, (header() + "\n" + text).toByteArray(Charsets.UTF_8).let { trimTail(it) })
        "Report sent to the PC (id $id)"
    }

    suspend fun sendCrashIfAny(store: CrashStore): String? {
        val text = store.pending() ?: return null
        val id = upload("crash", text.toByteArray(Charsets.UTF_8))
        store.clear()
        return id
    }

    private suspend fun upload(kind: String, content: ByteArray): String {
        val fields = DevUpload.fields(device(), app.packageName, BuildConfig.GIT_COMMIT, kind)
        val body = DevUpload.body(fields, DevUpload.fileName(kind, BuildConfig.GIT_COMMIT, System.currentTimeMillis()), content)
        val reply = link.postMultipart("/v1/dev/logs", body)
        return ProcJson.json.decodeFromString<DevUpload.Receipt>(reply).id
    }

    private fun header() = "ARMeasure ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} commit ${BuildConfig.GIT_COMMIT} (${BuildConfig.BUILD_TYPE})\n" +
        "device ${device()} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) pid ${android.os.Process.myPid()}"

    private fun trimTail(b: ByteArray): ByteArray = TailBuffer(DevUpload.MAX_LOG_BYTES).also { it.write(b) }.bytes()

    /** `logcat -d --pid=<mine>`, last ~2 MB. Reading the own process's log needs no permission. */
    private fun logcat(): String {
        return try {
            val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
            val tail = TailBuffer(DevUpload.MAX_LOG_BYTES)
            p.inputStream.use { s ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    tail.write(buf, 0, n)
                }
            }
            p.waitFor()
            String(tail.bytes(), Charsets.UTF_8)
        } catch (e: Exception) {
            "logcat unavailable: ${e.message}"
        }
    }

    private fun installedVersionCode(): Int =
        try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        } catch (e: Exception) {
            0
        }
}

/** PackageInstaller session install; the system shows its own confirmation. */
object ApkInstaller {
    const val ACTION = "com.example.arruler.devlink.INSTALL_RESULT"

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("armeasure.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}

/** Receives the installer's status: opens the system confirmation, or records why the install stopped. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(confirm) } catch (e: Exception) { InstallStatus.message.value = "Cannot open the install prompt: ${e.message}" }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
            InstallStatus.message.value = "Install did not finish: $why"
            Log.w(TAG, "install status $status: $why")
        }
    }
}
