package com.example.arruler.tandem

import com.example.arruler.objscan.ObjectPipeline
import com.example.arruler.processing.DefaultPhoneRunner
import com.example.arruler.processing.JobEstimate
import com.example.arruler.processing.JobPackage
import com.example.arruler.processing.JobType
import com.example.arruler.processing.PhoneRunner
import com.example.arruler.processing.ProcessingJob
import com.example.arruler.processing.ResultPackage
import com.example.arruler.processing.isoNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.example.arruler.processing.ObjectQuality as JobQuality

enum class TandemState { IDLE, PAIRED, CLOCK_SYNCED, CAPTURING, PROCESSING, MERGED, FAILED }

/** Bakes a triangle subset with this phone's keyframes; the app implements it (needs the keyframe store). */
fun interface TextureRangeHandler {
    /** [mesh] is a point-job-independent mesh blob (binary PLY from TriMesh.toBinaryPly); returns a file the leader can pack. */
    suspend fun bake(mesh: File, params: Map<String, String>): File
}

/**
 * The follower side of a tandem session: answers the leader's messages. It never decides anything; the leader's
 * [TandemCoordinator] drives the state machine and this mirrors it ([state]) for the helper's own screen.
 */
class TandemHelper(
    private val session: TandemSession,
    private val capture: TandemCapture,
    private val local: () -> PeerCaps,
    private val clockNs: () -> Long,
    private val scope: CoroutineScope,
    private val workDir: File,
    private val deviceModel: String = "helper",
    private val appVersion: String = "dev",
    private val phone: PhoneRunner = DefaultPhoneRunner(),
    private val created: () -> String = ::isoNow,
    private val texture: TextureRangeHandler? = null,
) {
    private val _state = MutableStateFlow(TandemState.IDLE)
    val state: StateFlow<TandemState> = _state.asStateFlow()

    /** Fraction (0..1) of the current task, for the helper's progress bar. */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /** Set when the leader finished the job: whether this phone's data was merged, and the headline numbers. */
    private val _completed = MutableStateFlow<TandemMessage.Complete?>(null)
    val completed: StateFlow<TandemMessage.Complete?> = _completed.asStateFlow()

    @Volatile private var offsetNs = 0L
    @Volatile var leaderHello: TandemMessage.Hello? = null
        private set
    private val running = ConcurrentHashMap<String, Job>()

    /** Subscribes to the session and sends HELLO. Call once, right after the link is up. */
    suspend fun start() {
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.messages.collect { handle(it) }
        }
        sendHello()
    }

    private suspend fun sendHello() {
        val c = local()
        session.send(TandemMessage.Hello(deviceModel, c.tier.name, c.depthSupported, appVersion, c.benchMs))
    }

    /** Pushes the current thermal / battery state so the leader's planner can re-balance. */
    suspend fun reportStatus() {
        val c = local()
        session.send(TandemMessage.Status(c.thermalStatus, c.thermalHeadroom, c.batteryPct, c.charging, c.measuredFactor.toFloat()))
    }

    private suspend fun handle(m: TandemMessage) {
        when (m) {
            is TandemMessage.Hello -> { leaderHello = m; if (_state.value == TandemState.IDLE) _state.value = TandemState.PAIRED }
            is TandemMessage.Role -> Unit
            is TandemMessage.ClockPing -> {
                val t1 = clockNs()
                session.send(TandemMessage.ClockPong(m.id, m.t0, t1, clockNs()))
            }
            is TandemMessage.ClockSet -> { offsetNs = m.offsetNs; _state.value = TandemState.CLOCK_SYNCED }
            is TandemMessage.StartCapture -> {
                _state.value = TandemState.CAPTURING
                _progress.value = 0f
                scope.launch { runCatching { capture.start(m, m.atLeaderNs + offsetNs) }.onFailure { fail("capture start failed: ${it.message}") } }
            }
            is TandemMessage.StopCapture -> scope.launch { runCatching { capture.stop(m.atLeaderNs + offsetNs) }.onFailure { fail("capture stop failed: ${it.message}") } }
            is TandemMessage.Task -> startTask(m)
            is TandemMessage.Complete -> { _completed.value = m; _state.value = TandemState.MERGED }
            is TandemMessage.Bye -> _state.value = if (_state.value == TandemState.MERGED) TandemState.MERGED else TandemState.IDLE
            is TandemMessage.Error -> _state.value = TandemState.FAILED
            else -> Unit // pong / progress / result / status are leader-bound
        }
    }

    private suspend fun fail(message: String) {
        _state.value = TandemState.FAILED
        runCatching { session.send(TandemMessage.Error("helper", message)) }
    }

    private fun startTask(t: TandemMessage.Task) {
        if (t.params["cancel"] == "1") { running.remove(t.id)?.cancel(); return }
        _state.value = TandemState.PROCESSING
        val job = scope.launch {
            val t0 = System.nanoTime()
            try {
                session.send(TandemMessage.TaskProgress(t.id, 0f, "started"))
                val (tag, notes) = when (t.kind) {
                    TaskKind.PARTIAL_OBJECT -> partial(t)
                    TaskKind.MC_SLAB -> slab(t)
                    TaskKind.PACKAGE_JOB -> packageJob(t)
                    TaskKind.TEXTURE_RANGE -> textureRange(t)
                }
                _progress.value = 1f
                session.send(TandemMessage.TaskResult(t.id, true, tag, null, (System.nanoTime() - t0) / 1_000_000, notes))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { session.send(TandemMessage.TaskResult(t.id, false, "", e.message ?: e.javaClass.simpleName, (System.nanoTime() - t0) / 1_000_000)) }
            } finally {
                running.remove(t.id)
            }
        }
        running[t.id] = job
    }

    /** Isolate and denoise MY capture and send the isolated cloud back as a point-job ZIP. */
    private suspend fun partial(t: TandemMessage.Task): Pair<String, List<String>> = withContext(Dispatchers.Default) {
        val cap = capture.result()
        val out = ObjectPipeline.run(cap.points, cap.hits, cap.box, cap.plane, cap.quality)
        workDir.mkdirs()
        val zip = File(workDir, "partial_${t.id}.zip")
        TandemMerge.writePartial(zip, out.isolated, cap.box, cap.plane, cap.quality, appVersion, created())
        val tag = "partial-${t.id}.zip"
        session.sendFile(tag, zip) { _progress.value = it }
        zip.delete()
        tag to listOf("helper isolated ${out.isolated.size / 3} points", "points: in=${out.stats.input} kept=${out.stats.afterComponent}")
    }

    private suspend fun slab(t: TandemMessage.Task): Pair<String, List<String>> {
        val inFile = session.awaitFile(t.ref)
        val outBytes = withContext(Dispatchers.Default) { SlabMesher.processFieldSlab(inFile.readBytes()) }
        inFile.delete(); session.forget(t.ref)
        workDir.mkdirs()
        val f = File(workDir, "piece_${t.id}.bin").also { it.writeBytes(outBytes) }
        val tag = "piece-${t.id}.bin"
        session.sendFile(tag, f)
        f.delete()
        return tag to emptyList()
    }

    /** The PcLink-over-peer job: a normal job ZIP in, a normal result ZIP out. */
    private suspend fun packageJob(t: TandemMessage.Task): Pair<String, List<String>> {
        val zip = session.awaitFile(t.ref)
        session.forget(t.ref)
        workDir.mkdirs()
        val dest = File(workDir, "result_${t.id}.zip")
        withContext(Dispatchers.Default) {
            val c = JobPackage.read(zip)
            val type = JobType.fromWire(c.manifest.jobType) ?: error("unknown job type")
            require(type != JobType.PHOTOGRAMMETRY) { "photogrammetry only runs on the PC" }
            val job = ProcessingJob(
                type, c.manifest.quality?.let { runCatching { JobQuality.valueOf(it) }.getOrNull() },
                JobEstimate(pointCount = c.cloud?.count ?: 0), cloud = c.cloud, workDir = workDir,
                objectBox = c.manifest.box?.toObjectBox(), supportPlane = c.manifest.supportPlane?.toSupportPlane(),
            )
            val r = phone.run(job)
            val extra = job.mesh?.let { mapOf("mesh.ply" to it.toBinaryPly()) } ?: emptyMap()
            ResultPackage.write(dest, r.copy(stats = r.stats.copy(backend = "peer")), extra)
        }
        zip.delete()
        val tag = "result-${t.id}.zip"
        session.sendFile(tag, dest)
        dest.delete()
        return tag to emptyList()
    }

    private suspend fun textureRange(t: TandemMessage.Task): Pair<String, List<String>> {
        val h = texture ?: error("this phone cannot bake textures")
        val mesh = session.awaitFile(t.ref)
        val out = h.bake(mesh, t.params)
        val tag = "texture-${t.id}.bin"
        session.sendFile(tag, out)
        return tag to emptyList()
    }
}
