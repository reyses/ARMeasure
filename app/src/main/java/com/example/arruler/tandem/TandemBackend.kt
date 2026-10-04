package com.example.arruler.tandem

import com.example.arruler.processing.JobStatus
import com.example.arruler.processing.JobType
import com.example.arruler.processing.PcLink
import com.example.arruler.processing.PcLinkException
import com.example.arruler.processing.ObjectQuality
import com.example.arruler.processing.ServerInfo
import com.example.arruler.processing.Tier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The other phone as a processing backend: the same contract [ProcessingService] uses for the PC ([PcLink]), over a [TandemSession].
 * `submit` sends the normal job ZIP as a FILE and a TASK PACKAGE_JOB, `status` mirrors TASK_PROGRESS / TASK_RESULT, `download` returns the normal
 * result ZIP the helper wrote (processing/ResultPackage). A dropped link is reported as a network [PcLinkException], which is exactly what
 * the service already treats as "backend unreachable" (it falls back to the phone for SCAN_ANALYZE).
 */
class TandemBackend(
    private val session: TandemSession,
    scope: CoroutineScope,
    private val peerName: () -> String = { "other phone" },
    private val appVersion: String = "dev",
) : PcLink {
    private val status = ConcurrentHashMap<String, JobStatus>()
    private val resultTag = ConcurrentHashMap<String, String>()
    private val counter = AtomicInteger()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.messages.collect { m ->
                when (m) {
                    is TandemMessage.TaskProgress -> if (status[m.id].let { it == null || it is JobStatus.Queued || it is JobStatus.Running }) {
                        status[m.id] = JobStatus.Running(m.fraction.coerceIn(0f, 1f), m.stage)
                    }
                    is TandemMessage.TaskResult -> {
                        if (m.ok) { resultTag[m.id] = m.ref; status[m.id] = JobStatus.Done }
                        else status[m.id] = JobStatus.Failed(m.error ?: "failed")
                    }
                    else -> Unit
                }
            }
        }
    }

    override suspend fun ping(): ServerInfo {
        if (!session.isOpen) throw unreachable()
        return ServerInfo(peerName(), appVersion, gpu = "", apiVersion = 1)
    }

    override suspend fun submit(zip: File, type: JobType, onProgress: (Float) -> Unit): String {
        require(type != JobType.PHOTOGRAMMETRY) { "photogrammetry only runs on the PC" }
        if (!session.isOpen) throw unreachable()
        val id = "p${counter.incrementAndGet()}"
        status[id] = JobStatus.Queued(0)
        try {
            session.sendFile("job-$id.zip", zip, onProgress)
            session.send(TandemMessage.Task(id, TaskKind.PACKAGE_JOB, "job-$id.zip", mapOf("type" to type.wire)))
        } catch (e: PeerClosedException) {
            throw unreachable()
        }
        return id
    }

    override suspend fun status(jobId: String): JobStatus {
        val s = status[jobId] ?: throw PcLinkException(404, "not_found", "unknown job $jobId")
        if (s is JobStatus.Done || s is JobStatus.Failed || s is JobStatus.Cancelled) return s
        if (!session.isOpen) throw unreachable()
        return s
    }

    override suspend fun download(jobId: String, dest: File, onProgress: (Float) -> Unit): File {
        val tag = resultTag[jobId] ?: throw PcLinkException(409, "not_ready", "job $jobId has no result")
        val f = try { session.awaitFile(tag) } catch (e: PeerClosedException) { throw unreachable() }
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        if (!f.renameTo(dest)) { f.copyTo(dest, overwrite = true); f.delete() }
        session.forget(tag)
        onProgress(1f)
        return dest
    }

    override suspend fun cancel(jobId: String) {
        status[jobId] = JobStatus.Cancelled
        runCatching { session.send(TandemMessage.Task(jobId, TaskKind.PACKAGE_JOB, params = mapOf("cancel" to "1"))) }
    }

    private fun unreachable() = PcLinkException(0, "network", "the other phone is not connected", network = true)
}

/**
 * Where a job should run once a peer is part of the picture. The existing [com.example.arruler.processing.Router] still decides PHONE vs
 * PC; this adds PEER on top (see docs/TANDEM.md for the exact Router edit). Pure.
 */
enum class PeerRoute { NONE, PEER }

object TandemRouting {
    /**
     * PC first for DETAILED (the PC is the only photogrammetry backend). Otherwise PEER for QUICK / FINE when a peer is paired and either the
     * local phone is not HIGH or the capture is dual (both phones captured, so there is something to merge).
     * [pcWouldRun] = what [com.example.arruler.processing.Router] returned for this job (PC), so AUTO keeps a PC choice for big jobs.
     */
    fun decide(
        job: JobType, quality: ObjectQuality?, localTier: Tier, peerPaired: Boolean, dualCapture: Boolean, pcAvailable: Boolean, pcWouldRun: Boolean,
    ): PeerRoute {
        if (!peerPaired) return PeerRoute.NONE
        val pcOnly = job == JobType.PHOTOGRAMMETRY || quality == ObjectQuality.DETAILED || quality == ObjectQuality.DETAILED_SPLAT
        if (pcOnly) return PeerRoute.NONE
        if (dualCapture) return PeerRoute.PEER
        if (pcAvailable && pcWouldRun) return PeerRoute.NONE
        return if (localTier != Tier.HIGH) PeerRoute.PEER else PeerRoute.NONE
    }
}
