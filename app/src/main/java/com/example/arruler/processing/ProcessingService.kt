package com.example.arruler.processing

import com.example.arruler.depth.PlaneExtractor
import com.example.arruler.depth.PlaneKind
import com.example.arruler.depth.RoomFromPlanes
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.ObjectPipeline
import com.example.arruler.objscan.SupportPlane
import com.example.arruler.objscan.TriMesh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything the router needs to know right now. */
data class RouteSignals(
    val tier: Tier,
    val pcAvailable: Boolean,
    val userPref: UserPref,
    val onWifi: Boolean,
    val batteryLow: Boolean,
    val thermal: Int
)

/** A unit of heavy work. Point jobs carry [cloud]; photogrammetry carries [photos]. */
class ProcessingJob(
    val type: JobType,
    val quality: ObjectQuality? = null,
    val estimate: JobEstimate,
    val cloud: CloudData? = null,
    val photos: List<PhotoFrame> = emptyList(),
    val workDir: File,
    /** Object jobs: the placed box and the horizontal support plane (also sent to the PC in the manifest). */
    val objectBox: ObjectBox? = null,
    val supportPlane: SupportPlane? = null,
) {
    /** Side output of the phone runner for OBJECT_MESH (a PC job returns its mesh inside the result ZIP). */
    @Volatile var mesh: TriMesh? = null
}

sealed interface ProcessingState {
    data class Routed(val decision: RouteDecision) : ProcessingState
    /** PC upload on mobile data above 20 MB: call process again with confirmedMobileUpload = true. */
    data class NeedsConfirmation(val decision: RouteDecision) : ProcessingState
    data class Warning(val message: String) : ProcessingState
    data object Packaging : ProcessingState
    data class Uploading(val fraction: Float) : ProcessingState
    data class Queued(val position: Int) : ProcessingState
    data class Running(val progress: Float, val stage: String) : ProcessingState
    data class Downloading(val fraction: Float) : ProcessingState
    data class Done(val backend: Backend, val result: ResultJson, val resultZip: File?) : ProcessingState
    data class Failed(val message: String) : ProcessingState
}

fun interface JobPackager {
    fun pack(job: ProcessingJob, dest: File)
}

/** Writes the upload ZIP with [JobPackage]. */
class DefaultJobPackager(
    private val appVersion: String,
    private val device: () -> DeviceSummary? = { null },
    private val now: () -> String = ::isoNow
) : JobPackager {
    override fun pack(job: ProcessingJob, dest: File) {
        val meta = PackageMeta(appVersion, now(), device(), job.quality, job.objectBox, job.supportPlane)
        if (job.type == JobType.PHOTOGRAMMETRY) JobPackage.writePhotoJob(dest, job.photos, meta)
        else JobPackage.writePointJob(dest, job.type, requireNotNull(job.cloud) { "point job without cloud" }, meta)
    }
}

fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

/** Runs a job on the phone. */
fun interface PhoneRunner {
    suspend fun run(job: ProcessingJob): ResultJson
}

/** SCAN_ANALYZE via the existing pure pipeline, OBJECT_MESH via objscan/ ([ObjectPipeline]). */
class DefaultPhoneRunner : PhoneRunner {
    override suspend fun run(job: ProcessingJob): ResultJson = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()
        when (job.type) {
            JobType.SCAN_ANALYZE -> {
                val cloud = requireNotNull(job.cloud) { "no cloud" }
                val planes = PlaneExtractor().extract(cloud.xyz)
                val room = RoomFromPlanes.build(planes)
                    ?: throw IllegalStateException("Room not found: need floor, ceiling and 3 walls in the scan")
                val area = room.outline.area().toDouble()
                val per = room.outline.perimeter().toDouble()
                val h = room.height.toDouble()
                ResultJson(
                    jobType = job.type.wire,
                    measures = Measures(
                        areaM2 = Estimate.exact(area), perimeterM = Estimate.exact(per),
                        heightM = Estimate.exact(h), volumeM3 = Estimate.exact(area * h),
                        wallCount = room.wallCount
                    ),
                    stats = ProcessingStats("phone", (System.nanoTime() - t0) / 1_000_000,
                        notes = listOf("planes=${planes.size}", "walls=${planes.count { it.kind == PlaneKind.WALL }}"))
                )
            }
            JobType.OBJECT_MESH -> runObject(job, t0)
            JobType.PHOTOGRAMMETRY -> throw UnsupportedOperationException("Photogrammetry runs only on the PC")
        }
    }
}

/** Phone path of OBJECT_MESH: isolate, measure and mesh with objscan, then express it as the protocol result. */
private fun runObject(job: ProcessingJob, t0: Long): ResultJson {
    val cloud = requireNotNull(job.cloud) { "no cloud" }
    val box = requireNotNull(job.objectBox) { "no object box" }
    val plane = requireNotNull(job.supportPlane) { "no support plane" }
    val q = when (job.quality) {
        ObjectQuality.FINE -> com.example.arruler.objscan.ObjectQuality.FINE
        else -> com.example.arruler.objscan.ObjectQuality.QUICK
    }
    val out = ObjectPipeline.run(cloud.xyz, cloud.hits, box, plane, q)
    job.mesh = out.mesh
    val m = out.measures
    val variants = LinkedHashMap<String, Double>()
    variants["bounding_box"] = m.orientedBoxVolume.toDouble()
    variants["convex_hull"] = m.hullVolume.toDouble()
    variants["occupancy"] = m.occupancyVolume.toDouble()
    out.meshVolume?.let { variants["mesh"] = it }
    val s = out.stats
    return ResultJson(
        jobType = job.type.wire,
        measures = Measures(
            heightM = Estimate.exact(m.maxHeight.toDouble()),
            volumeM3 = Estimate(m.volumeLow.toDouble(), m.volumeHigh.toDouble(), m.volumeRecommended.toDouble()),
            volumeVariantsM3 = variants,
            objectDims = ObjectDims(m.footprint.length.toDouble(), m.footprint.width.toDouble(), m.maxHeight.toDouble()),
        ),
        stats = ProcessingStats(
            "phone", (System.nanoTime() - t0) / 1_000_000,
            notes = listOf(
                "quality=${q.name} voxel=${q.voxelSize * 1000f} mm",
                "points: in=${s.input} box=${s.afterBox} support=${s.afterSupport} outliers=${s.afterOutliers} kept=${s.afterComponent}",
                if (out.mesh == null) "mesh: not built" else "mesh: ${out.mesh.triangleCount} triangles",
            ),
        ),
    )
}

/**
 * Single entry point: route, then run on the phone or upload/poll/download with the PC.
 * Polling starts at [POLL_START_MS], grows x1.5 while nothing changes up to [POLL_MAX_MS], and
 * resets when progress or stage changes. Cancelling the collector cancels the remote job.
 */
class ProcessingService(
    private val signals: () -> RouteSignals,
    private val pc: PcLink?,
    private val packager: JobPackager,
    private val phone: PhoneRunner = DefaultPhoneRunner(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {
    fun process(job: ProcessingJob, confirmedMobileUpload: Boolean = false): Flow<ProcessingState> = channelFlow {
        val s = signals()
        val decision = Router.decide(
            job.type, job.quality, job.estimate, s.tier, s.pcAvailable && pc != null,
            s.userPref, s.onWifi, s.batteryLow, s.thermal
        )
        send(ProcessingState.Routed(decision))
        if (decision.blocked) {
            send(ProcessingState.Failed(decision.warning ?: decision.reason)); return@channelFlow
        }
        if (decision.needsConfirmation && !confirmedMobileUpload) {
            send(ProcessingState.NeedsConfirmation(decision)); return@channelFlow
        }
        decision.warning?.let { send(ProcessingState.Warning(it)) }
        if (decision.backend == Backend.PHONE) runPhone(job)
        else runPc(job, s)
    }.flowOn(Dispatchers.Default)

    private suspend fun ProducerScope<ProcessingState>.runPhone(job: ProcessingJob) {
        send(ProcessingState.Running(0f, "analysing on the phone"))
        try {
            val r = phone.run(job)
            send(ProcessingState.Done(Backend.PHONE, r, null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(ProcessingState.Failed(e.message ?: "Phone processing failed"))
        }
    }

    private suspend fun ProducerScope<ProcessingState>.runPc(job: ProcessingJob, s: RouteSignals) {
        val link = pc ?: run { send(ProcessingState.Failed("No PC paired")); return }
        val zip = File(job.workDir, "job_${System.nanoTime()}.zip")
        var jobId: String? = null
        try {
            send(ProcessingState.Packaging)
            withContext(Dispatchers.IO) { job.workDir.mkdirs(); packager.pack(job, zip) }
            send(ProcessingState.Uploading(0f))
            jobId = try {
                link.submit(zip, job.type) { f -> trySend(ProcessingState.Uploading(f)) }
            } catch (e: PcLinkException) {
                if (e.network && job.type == JobType.SCAN_ANALYZE && s.userPref != UserPref.PC) {
                    send(ProcessingState.Warning("PC unreachable, running on the phone"))
                    runPhone(job); return
                }
                throw e
            }
            send(ProcessingState.Uploading(1f))
            pollUntilDone(link, jobId)?.let { send(it); return }
            val dest = File(job.workDir, "result_$jobId.zip")
            send(ProcessingState.Downloading(0f))
            link.download(jobId, dest) { f -> trySend(ProcessingState.Downloading(f)) }
            val contents = ResultPackage.read(dest)
            send(ProcessingState.Done(Backend.PC, contents.result, dest))
        } catch (e: CancellationException) {
            jobId?.let { id -> withContext(NonCancellable) { runCatching { link.cancel(id) } } }
            throw e
        } catch (e: PcLinkException) {
            send(ProcessingState.Failed("PC: ${e.message}"))
        } catch (e: PackageFormatException) {
            send(ProcessingState.Failed("PC returned a bad result: ${e.message}"))
        } catch (e: Exception) {
            send(ProcessingState.Failed(e.message ?: "PC processing failed"))
        } finally {
            zip.delete()
        }
    }

    /** Returns null when the job is DONE, else the terminal failure state. */
    private suspend fun ProducerScope<ProcessingState>.pollUntilDone(link: PcLink, id: String): ProcessingState? {
        var interval = POLL_START_MS
        var last: JobStatus? = null
        var errors = 0
        while (true) {
            val st = try {
                link.status(id).also { errors = 0 }
            } catch (e: PcLinkException) {
                if (!e.network || ++errors > MAX_POLL_ERRORS) return ProcessingState.Failed("PC: ${e.message}")
                interval = nextInterval(interval)
                delayFn(interval); continue
            }
            when (st) {
                JobStatus.Done -> return null
                is JobStatus.Failed -> return ProcessingState.Failed("PC: ${st.message}")
                JobStatus.Cancelled -> return ProcessingState.Failed("PC: job was cancelled")
                is JobStatus.Queued -> send(ProcessingState.Queued(st.position))
                is JobStatus.Running -> send(ProcessingState.Running(st.progress, st.stage))
            }
            interval = if (st != last) POLL_START_MS else nextInterval(interval)
            last = st
            delayFn(interval)
        }
    }

    companion object {
        const val POLL_START_MS = 2_000L
        const val POLL_MAX_MS = 10_000L
        const val MAX_POLL_ERRORS = 5
        fun nextInterval(cur: Long): Long = minOf(POLL_MAX_MS, cur * 3 / 2)
    }
}
