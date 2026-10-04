package com.example.arruler.devlink

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.arruler.BuildConfig
import com.example.arruler.processing.AndroidPairingStore
import com.example.arruler.processing.PairingInfo
import com.example.arruler.processing.PcLinkException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AutoUpdate"

/** What the banner shows. */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data class Offer(val info: ApkInfo) : UpdateUi
    data class AskMobile(val info: ApkInfo, val prompt: String) : UpdateUi
    data class Downloading(val info: ApkInfo, val progress: Float) : UpdateUi

    /** Downloaded and verified; waiting for "Install unknown apps" to be allowed in the system page that was opened. */
    data class NeedsPermission(val info: ApkInfo, val file: File) : UpdateUi
    data class Installing(val info: ApkInfo) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

/**
 * Debug builds: asks the paired PC for its newest APK at app start and when the app returns to the foreground (at most once
 * per 30 minutes), and shows a non-blocking banner when it differs from this build. Works over any network the pairing URLs
 * reach (tailnet first), mobile data included; a download over mobile data asks once. Decisions live in [AutoUpdate].
 */
object AutoUpdater {
    val state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)

    private const val PREFS = "dev_update"
    private const val K_AUTO = "auto_check"
    private const val K_MOBILE = "always_mobile"
    private const val K_NOTIFY = "notify"
    private const val CHANNEL = "dev_updates"
    private const val NOTIF_ID = 7305

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checking = AtomicBoolean(false)
    @Volatile private var lastCheckMs = 0L
    @Volatile private var dismissedCommit: String? = null
    @Volatile private var lastPairingKey: String? = null
    @Volatile private var installed = false
    private var started = 0
    private lateinit var app: Application

    fun install(application: Application) {
        if (installed) return
        installed = true
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (++started == 1) check(force = false)
            }

            override fun onActivityStopped(a: Activity) { if (started > 0) started-- }

            override fun onActivityResumed(a: Activity) { resumeAfterPermission() }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        })
    }

    /** At launch and whenever the pairing changes: a changed pairing is checked at once, the same one under the throttle. */
    fun onPairing(ctx: Context, pairing: PairingInfo) {
        if (!installed) return
        val key = pairing.allUrls().joinToString("|") + "#" + pairing.token.hashCode()
        val changed = lastPairingKey != key
        lastPairingKey = key
        check(force = changed)
    }

    // ---- settings ----

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun autoCheck(): Boolean = try { prefs().getBoolean(K_AUTO, true) } catch (_: Throwable) { true }
    fun setAutoCheck(on: Boolean) { prefs().edit().putBoolean(K_AUTO, on).apply() }
    fun alwaysMobile(): Boolean = try { prefs().getBoolean(K_MOBILE, false) } catch (_: Throwable) { false }
    fun setAlwaysMobile(on: Boolean) { prefs().edit().putBoolean(K_MOBILE, on).apply() }
    fun notifyOn(): Boolean = try { prefs().getBoolean(K_NOTIFY, true) } catch (_: Throwable) { true }
    fun setNotify(on: Boolean) { prefs().edit().putBoolean(K_NOTIFY, on).apply() }

    // ---- the check ----

    private fun check(force: Boolean) {
        try {
            if (!autoCheck()) return
            if (state.value !is UpdateUi.Idle && state.value !is UpdateUi.Failed) return
            val now = System.currentTimeMillis()
            if (!force && !AutoUpdate.shouldCheck(now, lastCheckMs)) return
            val pairing = try { AndroidPairingStore(app).load() } catch (_: Throwable) { null } ?: return
            if (!checking.compareAndSet(false, true)) return
            lastCheckMs = now
            scope.launch {
                try {
                    when (val o = DevLinkClient(app, pairing).fetchOffer(dismissedCommit)) {
                        is AutoUpdate.Offer.Show -> {
                            state.value = UpdateUi.Offer(o.info)
                            notifyOffer(o.info)
                        }
                        is AutoUpdate.Offer.None -> Log.i(TAG, "no update: ${o.why}")
                    }
                } catch (e: PcLinkException) {
                    Log.i(TAG, "update check: ${e.message}") // PC off or unreachable: stay quiet
                } catch (e: Exception) {
                    Log.w(TAG, "update check failed: ${e.message}")
                } finally {
                    checking.set(false)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "update check could not start: ${e.message}")
        }
    }

    // ---- the install ----

    fun dismiss() {
        (state.value as? UpdateUi.Offer)?.let { dismissedCommit = it.info.commit }
        state.value = UpdateUi.Idle
        cancelNotification()
    }

    fun clearFailure() { if (state.value is UpdateUi.Failed) state.value = UpdateUi.Idle }

    /** Install tapped: mobile data asks first (unless "always allow"), then download, verify and hand to the system installer. */
    fun onInstallTapped(info: ApkInfo) {
        when (val g = AutoUpdate.downloadGate(isMetered(), alwaysMobile(), info.size)) {
            is AutoUpdate.Gate.Go -> startDownload(info)
            is AutoUpdate.Gate.AskMobile -> state.value = UpdateUi.AskMobile(info, g.prompt)
            is AutoUpdate.Gate.NoNetwork -> state.value = UpdateUi.Failed("No network. Try again when online.")
        }
    }

    fun confirmMobile(info: ApkInfo, always: Boolean) {
        if (always) setAlwaysMobile(true)
        startDownload(info)
    }

    fun cancelMobile(info: ApkInfo) { state.value = UpdateUi.Offer(info) }

    private fun startDownload(info: ApkInfo) {
        val pairing = try { AndroidPairingStore(app).load() } catch (_: Throwable) { null }
        if (pairing == null) { state.value = UpdateUi.Failed("No PC paired."); return }
        state.value = UpdateUi.Downloading(info, 0f)
        cancelNotification()
        scope.launch {
            try {
                val file = DevLinkClient(app, pairing).downloadVerified(info) { p -> state.value = UpdateUi.Downloading(info, p) }
                if (file == null) state.value = UpdateUi.Failed("Download failed the sha256 / size check; not installing.")
                else proceedInstall(info, file)
            } catch (e: Exception) {
                state.value = UpdateUi.Failed("Download failed: ${e.message}")
            }
        }
    }

    private fun proceedInstall(info: ApkInfo, file: File) {
        if (!ApkInstaller.canInstall(app)) {
            state.value = UpdateUi.NeedsPermission(info, file)
            ApkInstaller.openUnknownSourcesSettings(app) // resumes in resumeAfterPermission() when the user comes back
            return
        }
        InstallStatus.message.value = ""
        state.value = UpdateUi.Installing(info)
        try {
            ApkInstaller.install(app, file)
        } catch (e: Exception) {
            state.value = UpdateUi.Failed("Install failed: ${e.message}")
        }
    }

    private fun resumeAfterPermission() {
        val s = state.value as? UpdateUi.NeedsPermission ?: return
        if (ApkInstaller.canInstall(app)) {
            scope.launch { proceedInstall(s.info, s.file) }
        }
    }

    /** True on mobile data (also over a VPN such as Tailscale, which reports the underlying network's metering); false when unmetered; null offline. */
    private fun isMetered(): Boolean? = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (cm.activeNetwork == null) null else cm.isActiveNetworkMetered
    } catch (_: Throwable) {
        null
    }

    // ---- notification (optional) ----

    private fun notifyOffer(info: ApkInfo) {
        try {
            if (!notifyOn()) return
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dev updates", NotificationManager.IMPORTANCE_DEFAULT))
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
            val pending = PendingIntent.getActivity(app, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("ARMeasure update available")
                .setContentText("${AutoUpdate.short(BuildConfig.GIT_COMMIT)} → ${AutoUpdate.short(info.commit)} (${AutoUpdate.sizeText(info.size)})")
                .setContentIntent(pending).setAutoCancel(true).build()
            nm.notify(NOTIF_ID, n)
        } catch (_: Throwable) {
            // no notification permission: the in-app banner is enough
        }
    }

    private fun cancelNotification() {
        try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Throwable) { }
    }
}

/** The banner and dialogs of [AutoUpdater]; draws nothing while the state is Idle. */
@Composable
fun AutoUpdateBanner() {
    val st by AutoUpdater.state.collectAsState()
    val installMsg by InstallStatus.message.collectAsState()
    val s = st
    if (s is UpdateUi.AskMobile) {
        AlertDialog(
            onDismissRequest = { AutoUpdater.cancelMobile(s.info) },
            title = { Text(s.prompt) },
            text = { Text("The PC build is ${AutoUpdate.sizeText(s.info.size)}. \"Always allow\" stops this question.") },
            confirmButton = {
                Row {
                    TextButton(onClick = { AutoUpdater.confirmMobile(s.info, always = true) }) { Text("Always allow") }
                    TextButton(onClick = { AutoUpdater.confirmMobile(s.info, always = false) }) { Text("Download") }
                }
            },
            dismissButton = { TextButton(onClick = { AutoUpdater.cancelMobile(s.info) }) { Text("Cancel") } },
        )
    }
    if (s is UpdateUi.Idle || s is UpdateUi.AskMobile) return
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (s) {
                    is UpdateUi.Offer -> {
                        Column(Modifier.fillMaxWidth().clickable { AutoUpdater.onInstallTapped(s.info) }) {
                            Text(AutoUpdate.bannerText(BuildConfig.GIT_COMMIT, s.info.commit), style = MaterialTheme.typography.bodyMedium)
                            Text("${AutoUpdate.sizeText(s.info.size)} from your PC. Tap to download and install.", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { AutoUpdater.dismiss() }) { Text("Later") }
                            TextButton(onClick = { AutoUpdater.onInstallTapped(s.info) }) { Text("Install") }
                        }
                    }
                    is UpdateUi.Downloading -> {
                        Text("Downloading ${AutoUpdate.short(s.info.commit)} (${AutoUpdate.sizeText(s.info.size)}) ${(s.progress * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateUi.NeedsPermission -> {
                        Text("Allow ARMeasure to install apps in the settings page that opened, then come back. The install resumes by itself.", style = MaterialTheme.typography.bodyMedium)
                    }
                    is UpdateUi.Installing -> {
                        Text(if (installMsg.isNotEmpty()) installMsg else "Confirm the install on screen.", style = MaterialTheme.typography.bodyMedium)
                        if (installMsg.isNotEmpty()) TextButton(onClick = { AutoUpdater.dismiss() }) { Text("Close") }
                    }
                    is UpdateUi.Failed -> {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { AutoUpdater.clearFailure() }) { Text("Close") }
                    }
                }
            }
        }
    }
}

/** Settings > Dev rows of the automatic update. */
@Composable
fun AutoUpdateSettings() {
    var auto by remember { mutableStateOf(AutoUpdater.autoCheck()) }
    var mobile by remember { mutableStateOf(AutoUpdater.alwaysMobile()) }
    var notify by remember { mutableStateOf(AutoUpdater.notifyOn()) }
    @Composable
    fun row(label: String, value: Boolean, change: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = value, onCheckedChange = change)
        }
    }
    row("Check the PC for a new build at start", auto) { auto = it; AutoUpdater.setAutoCheck(it) }
    row("Always download updates on mobile data", mobile) { mobile = it; AutoUpdater.setAlwaysMobile(it) }
    row("Notify when an update is available", notify) { notify = it; AutoUpdater.setNotify(it) }
}
