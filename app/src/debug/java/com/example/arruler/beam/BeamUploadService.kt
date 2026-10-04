package com.example.arruler.beam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.example.arruler.processing.AndroidPairingStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Android side of the uploader: network state, the JobScheduler job (network constraint, backoff), a progress notification. */
object BeamUpload {
    private const val TAG = "Beam"
    const val JOB_ID = 7302
    private const val CHANNEL = "beam_upload"
    private const val NOTIF_ID = 7303
    const val JOB_ID_WIFI = 7304
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val BIG_BYTES = UploadGate.BIG_BYTES

    /** One line for Settings: what the uploader is doing or why it waits. */
    val status = MutableStateFlow("")

    fun queueDir(ctx: Context) = File(ctx.filesDir, "beam/queue")

    /** true = metered, false = unmetered (Wi-Fi), null = no network. */
    fun metered(ctx: Context): Boolean? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Schedules (or re-schedules) the upload job; it runs when the network constraint of the current policy holds. */
    fun kick(ctx: Context) {
        try {
            val queue = BeamQueue(queueDir(ctx))
            if (queue.pending().isEmpty()) { status.value = "nothing queued"; return }
            val policy = BeamSettings(ctx).read()
            if (!policy.beamToPc) { status.value = "Beam to PC is off"; return }
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            // Small bundles may go over any network (tailnet / LAN), so the main job has no Wi-Fi constraint; a bundle that
            // must wait for Wi-Fi gets a second job with the UNMETERED constraint that runs when Wi-Fi returns.
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, BeamUploadJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
            if (queue.pending().any { UploadGate.needsWifi(policy, it.zip.length()) }) {
                js.schedule(JobInfo.Builder(JOB_ID_WIFI, ComponentName(ctx, BeamUploadJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
            }
            status.value = "${queue.pending().size} bundle(s) queued, ${queue.pendingBytes() / (1024 * 1024)} MB"
        } catch (e: Throwable) {
            Log.w(TAG, "cannot schedule the upload job: ${e.message}")
        }
    }

    /** Runs the queue now on the calling (background) thread. Returns true when something stays queued and should be retried. */
    fun runOnce(ctx: Context, cancelled: () -> Boolean): Boolean {
        if (!running.compareAndSet(false, true)) return false // the other job is already uploading
        try {
            return runLocked(ctx, cancelled)
        } finally {
            running.set(false)
        }
    }

    private fun runLocked(ctx: Context, cancelled: () -> Boolean): Boolean {
        val policy = BeamSettings(ctx).read()
        val pairing = try { AndroidPairingStore(ctx).load() } catch (_: Throwable) { null }
        val queue = BeamQueue(queueDir(ctx))
        val pending = queue.pending()
        if (pending.isEmpty()) { status.value = "nothing queued"; return false }
        val metered = metered(ctx)
        val urls0 = pairing?.allUrls() ?: emptyList()
        val blocked = UploadGate.blockedReason(policy, pairing != null, urls0, metered, pending.minOf { it.zip.length() })
        if (blocked != null || pairing == null) {
            status.value = "${pending.size} bundle(s) waiting: ${blocked ?: "no PC paired"}"
            // A policy block is not a failure: no retry loop, the next kick (launch, pairing, new bundle) tries again.
            return blocked == "no network"
        }
        val runner = QueueRunner(queue) { e ->
            val urls = UploadGate.urlsFor(policy, urls0, e.zip.length(), metered)
            if (urls.isEmpty()) null else ChunkedUploader(HttpBeamTransport(pairing, urls))
        }
        val big = queue.pendingBytes() > BIG_BYTES
        try {
            val summary = runner.run({ name, p ->
                status.value = "uploading $name ${(p * 100).toInt()} %"
                if (big) notify(ctx, "Sending Beam bundle to the PC", (p * 100).toInt())
            }, cancelled)
            status.value = "sent ${summary.sent}, queued ${summary.kept}, rejected ${summary.rejected}" + if (summary.lastReason.isNotEmpty()) " (${summary.lastReason})" else ""
            // Bundles waiting only for Wi-Fi are not retried here; the UNMETERED job runs them.
            return summary.kept > 0 && queue.pending().any { !UploadGate.needsWifi(policy, it.zip.length()) || metered == false }
        } catch (e: Throwable) {
            status.value = "upload failed: ${e.message}"
            Log.w(TAG, "upload failed", e)
            return true
        } finally {
            cancelNotification(ctx)
        }
    }

    private fun notify(ctx: Context, title: String, percent: Int) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Beam upload", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(title)
                .setProgress(100, percent, false).setOngoing(true).setOnlyAlertOnce(true).build()
            nm.notify(NOTIF_ID, n)
        } catch (_: Throwable) {
            // no notification permission: uploading goes on without it
        }
    }

    private fun cancelNotification(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Throwable) { }
    }
}

/** Runs [BeamUpload.runOnce] off the main thread; JobScheduler re-runs it with backoff when something stays queued. */
class BeamUploadJobService : JobService() {
    @Volatile private var stopped = false
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        worker = Thread({
            val retry = try { BeamUpload.runOnce(applicationContext) { stopped } } catch (_: Throwable) { true }
            jobFinished(params, retry && !stopped)
        }, "beam-upload").also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true // reschedule; the queue state is on disk and resumes at the next chunk
    }
}
