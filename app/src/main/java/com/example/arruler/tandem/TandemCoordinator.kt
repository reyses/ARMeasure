package com.example.arruler.tandem

import com.example.arruler.objscan.ObjectMeasurements
import com.example.arruler.objscan.ObjectMeasures
import com.example.arruler.objscan.ObjectPipeline
import com.example.arruler.objscan.TriMesh
import com.example.arruler.processing.JobType
import com.example.arruler.processing.ResultJson
import com.example.arruler.processing.Tier
import com.example.arruler.processing.toSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.example.arruler.objscan.ObjectBox as DepthBox

data class TandemOptions(
    val clockSamples: Int = 16,
    val clockTimeoutMs: Long = 2_000,
    /** Both phones start capturing this long after the START message, so the message has time to arrive. */
    val startLeadMs: Long = 1_500,
    /** The helper's partial result must arrive within this, else the leader continues alone. */
    val helperTimeoutMs: Long = 60_000,
    val slabChunks: Int = 6,
    /** Marching-cubes grids smaller than this are meshed on the leader alone. */
    val splitMeshMinCells: Long = 2_000_000L,
    val registration: RegistrationOptions? = null,
    /** Merge even when a symmetric object makes the yaw ambiguous (otherwise the leader falls back to single-phone). */
    val allowAmbiguous: Boolean = false,
)

class TandemResult(
    val singlePhone: Boolean,
    val measures: ObjectMeasurements,
    val mesh: TriMesh?,
    val meshVolume: Double?,
    /** Points the measures and the mesh were made from (leader frame). */
    val points: FloatArray,
    val registration: RegistrationResult?,
    val plan: SplitPlan,
    val notes: List<String>,
    val timingsMs: Map<String, Long>,
    val slabsLeader: Int,
    val slabsHelper: Int,
) {
    fun toResultJson(durationMs: Long): ResultJson = TandemMerge.resultJson(measures, mesh, meshVolume, singlePhone, notes, durationMs)
}

/**
 * The leader side of a tandem session; drives IDLE -> PAIRED -> CLOCK_SYNCED -> CAPTURING -> PROCESSING -> MERGED (FAILED on error).
 *
 * Flow of an object job: [pair], [syncClock], [startCapture], [stopCapture], [process]. [process] isolates the leader's own capture while the
 * helper isolates its own (TASK PARTIAL_OBJECT), registers the helper's cloud into the leader frame, fuses, measures, and meshes (the marching
 * cubes grid is cut into z slabs that either phone pulls, re-planned after every slab from the CURRENT speeds, so a throttling phone gets less).
 * If the peer drops, times out, or cannot be registered, the leader finishes alone and marks the result single-phone.
 */
class TandemCoordinator(
    private val session: TandemSession,
    private val capture: TandemCapture,
    private val local: () -> PeerCaps,
    private val clockNs: () -> Long,
    private val scope: CoroutineScope,
    private val workDir: File,
    private val deviceModel: String = "leader",
    private val appVersion: String = "dev",
    private val options: TandemOptions = TandemOptions(),
    private val pcAvailable: () -> Boolean = { false },
) {
    private val _state = MutableStateFlow(TandemState.IDLE)
    val state: StateFlow<TandemState> = _state.asStateFlow()

    private val _peer = MutableStateFlow<PeerCaps?>(null)
    /** The helper as the planner sees it; updated by HELLO and STATUS. */
    val peer: StateFlow<PeerCaps?> = _peer.asStateFlow()

    /** Helper-side progress of its current task (0..1), for the leader's screen. */
    private val _peerProgress = MutableStateFlow(0f)
    val peerProgress: StateFlow<Float> = _peerProgress.asStateFlow()

    private val helloDef = CompletableDeferred<TandemMessage.Hello>()
    private val pongs = Channel<Pair<TandemMessage.ClockPong, Long>>(Channel.UNLIMITED)
    private val results = ConcurrentHashMap<String, CompletableDeferred<TandemMessage.TaskResult>>()
    private val ids = AtomicInteger()
    val clock = ClockEstimator(options.clockSamples)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.messages.collect { m ->
                when (m) {
                    is TandemMessage.Hello -> helloDef.complete(m)
                    is TandemMessage.ClockPong -> pongs.trySend(m to clockNs())
                    is TandemMessage.TaskResult -> results.getOrPut(m.id) { CompletableDeferred() }.complete(m)
                    is TandemMessage.TaskProgress -> _peerProgress.value = m.fraction
                    is TandemMessage.Status -> _peer.value = _peer.value?.copy(
                        thermalStatus = m.thermalStatus, thermalHeadroom = m.thermalHeadroom, batteryPct = m.batteryPct,
                        charging = m.charging, measuredFactor = m.speedFactor.toDouble(),
                    )
                    else -> Unit
                }
            }
        }
    }

    val helperAlive: Boolean get() = session.isOpen

    // ---------------------------------------------------------------------------------------------- pairing and clock

    /** Exchanges HELLO / ROLE; returns the helper's capabilities. */
    suspend fun pair(): PeerCaps {
        val c = local()
        session.send(TandemMessage.Hello(deviceModel, c.tier.name, c.depthSupported, appVersion, c.benchMs))
        session.send(TandemMessage.Role(TandemRole.LEADER))
        val h = withTimeoutOrNull(options.clockTimeoutMs * 5) { helloDef.await() } ?: run { _state.value = TandemState.FAILED; error("the other phone did not answer") }
        val caps = PeerCaps(
            h.device, runCatching { Tier.valueOf(h.tier) }.getOrDefault(Tier.MID), h.benchMs, depthSupported = h.depthSupported,
        )
        _peer.value = caps
        _state.value = TandemState.PAIRED
        return caps
    }

    /** NTP-style ping rounds; keeps the minimum-RTT sample and tells the helper the offset. Returns (offsetNs, bestRttNs). */
    suspend fun syncClock(): Pair<Long, Long> {
        check(_state.value == TandemState.PAIRED || _state.value == TandemState.CLOCK_SYNCED) { "pair first" }
        for (i in 1..options.clockSamples) {
            val t0 = clockNs()
            session.send(TandemMessage.ClockPing(i, t0))
            while (true) {
                val (pong, t3) = withTimeoutOrNull(options.clockTimeoutMs) { pongs.receive() } ?: break
                if (pong.id != i) continue
                clock.add(ClockSample(pong.t0, pong.t1, pong.t2, t3))
                break
            }
        }
        val off = clock.offsetNs ?: error("no clock reply from the other phone")
        val rtt = clock.bestRttNs ?: 0L
        session.send(TandemMessage.ClockSet(off, rtt))
        _state.value = TandemState.CLOCK_SYNCED
        return off to rtt
    }

    // ---------------------------------------------------------------------------------------------- capture

    /**
     * Both phones start at the same instant on the LEADER's clock, [TandemOptions.startLeadMs] from now. [req] carries the leader-frame hints;
     * its `atLeaderNs` is overwritten. Returns the start time (leader clock, ns).
     */
    suspend fun startCapture(req: TandemMessage.StartCapture): Long {
        check(_state.value == TandemState.CLOCK_SYNCED) { "sync the clocks first" }
        val at = clockNs() + options.startLeadMs * 1_000_000L
        val r = req.copy(atLeaderNs = at)
        session.send(r)
        _state.value = TandemState.CAPTURING
        capture.start(r, at)
        return at
    }

    suspend fun stopCapture() {
        check(_state.value == TandemState.CAPTURING) { "not capturing" }
        val at = clockNs()
        runCatching { session.send(TandemMessage.StopCapture(at)) }
        capture.stop(at)
    }

    // ---------------------------------------------------------------------------------------------- processing

    /** The plan for the job as it stands now, with the CURRENT thermal state of both phones. */
    fun currentPlan(job: TandemJobSpec): SplitPlan = WorkSplit.plan(job, local(), if (helperAlive) _peer.value else null, pcAvailable())

    suspend fun process(): TandemResult {
        check(_state.value == TandemState.CAPTURING || _state.value == TandemState.CLOCK_SYNCED) { "capture first" }
        _state.value = TandemState.PROCESSING
        val t0 = System.nanoTime()
        val timings = LinkedHashMap<String, Long>()
        val notes = ArrayList<String>()
        fun lap(name: String, since: Long) { timings[name] = (System.nanoTime() - since) / 1_000_000 }
        try {
            val own = capture.result()
            val voxel = own.quality.voxelSize
            val jobId = "j${ids.incrementAndGet()}"

            val helperDef = if (helperAlive) scope.async { requestPartial(jobId, own.quality.name, notes) } else null
            val tIso = System.nanoTime()
            val ownOut = withContext(Dispatchers.Default) { ObjectPipeline.run(own.points, own.hits, own.box, own.plane, own.quality) }
            lap("isolate_leader", tIso)
            val partial = helperDef?.await()
            if (helperDef != null) lap("helper_wait", tIso)
            if (partial == null && helperDef == null) notes += "peer was not reachable"

            var reg: RegistrationResult? = null
            var fused: FloatArray? = null
            if (partial != null) {
                val tReg = System.nanoTime()
                val cons = TandemMerge.constraints(own.box, own.plane, partial.box, partial.plane, yawPrior, yawHalfWidth)
                val ro = options.registration ?: RegistrationOptions(spacing = voxel)
                reg = withContext(Dispatchers.Default) { CloudRegistration.register(ownOut.isolated, partial.points, cons, ro) }
                lap("register", tReg)
                when {
                    !reg.ok -> notes += "registration failed (${reg.reason}); helper data not merged"
                    reg.ambiguous && !options.allowAmbiguous -> notes += "registration ambiguous (symmetric object, no yaw prior); helper data not merged"
                    else -> {
                        if (!reg.yawPriorUsed) notes += "yaw came from the geometry alone (no compass / planned-position prior): a symmetric object seen from opposite sides may be mirrored"
                        val tFuse = System.nanoTime()
                        fused = withContext(Dispatchers.Default) {
                            TandemMerge.fuse(ownOut.isolated, reg.helperToLeader.transformPoints(partial.points), own.box, voxel)
                        }
                        lap("fuse", tFuse)
                        notes += "registration fitness=%.3f rmse=%.2f mm yaw=%.1f deg%s".format(
                            reg.fitness, reg.rmseM * 1000, reg.yawDeg, if (reg.underconstrained) " (one direction follows the constraints)" else "",
                        )
                    }
                }
            }

            val merged = fused != null
            val points = fused ?: ownOut.isolated
            val measures: ObjectMeasurements
            var mesh: TriMesh?
            var meshVolume: Double?
            var slabsL = 0; var slabsH = 0
            if (!merged) {
                measures = ownOut.measures; mesh = ownOut.mesh; meshVolume = ownOut.meshVolume
                if (helperAlive || partial != null) notes += "leader data only"
            } else {
                measures = ObjectMeasures.measure(points, own.box, own.plane, voxel) ?: error("merged object could not be measured")
                val tMesh = System.nanoTime()
                val g = withContext(Dispatchers.Default) { SlabMesher.buildField(points, own.box, own.plane, voxel, dilate = own.quality.meshDilate) }
                if (g == null) { mesh = null }
                else if (helperAlive && g.nx.toLong() * g.ny * g.nz >= options.splitMeshMinCells) {
                    val r = distributedMesh(g, notes)
                    mesh = r.mesh; slabsL = r.leader; slabsH = r.helper
                } else {
                    mesh = withContext(Dispatchers.Default) { SlabMesher.mesh(points, own.box, own.plane, voxel, dilate = own.quality.meshDilate) }
                    slabsL = 1
                }
                if (mesh != null && mesh.triangleCount == 0) mesh = null
                meshVolume = mesh?.volume()
                lap("mesh", tMesh)
            }

            val spec = TandemJobSpec(
                JobType.OBJECT_MESH, TandemMerge.jobQuality(own.quality), own.points.size / 3, (partial?.points?.size ?: 0) / 3,
                (mesh?.vertexCount ?: 0).toLong() * 8, mesh?.triangleCount ?: 0,
            )
            val result = TandemResult(
                singlePhone = !merged, measures = measures, mesh = mesh, meshVolume = meshVolume, points = points, registration = reg,
                plan = currentPlan(spec), notes = notes, timingsMs = timings, slabsLeader = slabsL, slabsHelper = slabsH,
            )
            lap("total", t0)
            runCatching {
                session.send(TandemMessage.Complete(jobId, !merged, mapOf("volume_m3" to "%.6f".format(measures.volumeRecommended), "single_phone" to (!merged).toString())))
            }
            _state.value = TandemState.MERGED
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = TandemState.FAILED
            throw e
        }
    }

    /** Hints for the registration, set by the app from compass / planned positions before [process]. */
    @Volatile var yawPrior: Float? = null
    @Volatile var yawHalfWidth: Float = 30f

    private suspend fun requestPartial(jobId: String, quality: String, notes: MutableList<String>): PartialObject? {
        val id = "$jobId-partial"
        val def = results.getOrPut(id) { CompletableDeferred() }
        try {
            session.send(TandemMessage.Task(id, TaskKind.PARTIAL_OBJECT, params = mapOf("quality" to quality)))
        } catch (e: PeerClosedException) {
            notes += "peer link lost before the helper could start"; return null
        }
        val res = withTimeoutOrNull(options.helperTimeoutMs) {
            select<TandemMessage.TaskResult?> {
                def.onAwait { it }
                session.closed.onAwait { null }
            }
        }
        if (res == null) { notes += if (session.closed.isCompleted) "peer dropped while processing" else "helper timed out"; return null }
        if (!res.ok) { notes += "helper failed: ${res.error}"; return null }
        return try {
            val f = withTimeoutOrNull(options.helperTimeoutMs) { session.awaitFile(res.ref) } ?: run { notes += "helper result never arrived"; return null }
            TandemMerge.readPartial(f).also { f.delete(); session.forget(res.ref); notes += res.notes.map { "helper: $it" } }
        } catch (e: PeerClosedException) {
            notes += "peer dropped while sending its result"; null
        } catch (e: Exception) {
            notes += "helper result unreadable: ${e.message}"; null
        }
    }

    private class MeshOutcome(val mesh: TriMesh?, val leader: Int, val helper: Int)

    /**
     * Marching cubes in [TandemOptions.slabChunks] z slabs pulled by both phones. A device takes the next slab only if it would finish it no later
     * than the other could with the CURRENT speeds ([ChunkScheduler.shouldTake]), so thermal changes (STATUS messages) re-balance between slabs.
     * A slab the helper fails goes back to the queue and the helper is dropped.
     */
    private suspend fun distributedMesh(g: FieldGrid, notes: MutableList<String>): MeshOutcome = coroutineScope {
        val iso = 0.7f
        val rs = SlabMesher.ranges(g.nz, List(options.slabChunks) { 1.0 })
        val pieces = arrayOfNulls<TriMesh>(rs.size)
        val todo = ArrayDeque((rs.indices).toList())
        val lock = Mutex()
        val busy = doubleArrayOf(0.0, 0.0)
        var helperOk = true
        val counts = intArrayOf(0, 0)
        val t0 = clockNs()
        fun now() = (clockNs() - t0) / 1e9
        fun cost(i: Int) = (g.nx.toDouble() * g.ny * (rs[i].last - rs[i].first + 2)) / CostModel.MC_CELLS_PER_S

        suspend fun worker(dev: Int, run: suspend (Int) -> TriMesh) {
            while (true) {
                val pick = lock.withLock {
                    when {
                        todo.isEmpty() -> -2
                        dev == 1 && !helperOk -> -2
                        else -> {
                            val sp = doubleArrayOf(local().speed(), if (helperOk) (_peer.value?.speed() ?: 1.0) else 1e-9)
                            val c = cost(todo.first())
                            val t = now()
                            if (ChunkScheduler.shouldTake(dev, c, busy, sp, t)) {
                                val i = todo.removeFirst()
                                busy[dev] = maxOf(t, busy[dev]) + c / sp[dev]
                                i
                            } else -1
                        }
                    }
                }
                if (pick == -2) return
                if (pick == -1) { delay(2); continue }
                try {
                    pieces[pick] = run(pick)
                    lock.withLock { counts[dev]++; busy[dev] = now() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (dev == 0) throw e
                    lock.withLock { todo.addFirst(pick); helperOk = false }
                    notes += "peer lost during meshing (${e.message}); slab $pick re-queued on the leader"
                    return
                }
            }
        }

        val localRun: suspend (Int) -> TriMesh = { i ->
            withContext(Dispatchers.Default) {
                SlabMesher.extractIndexSpace(SlabMesher.slabField(g, rs[i].first, rs[i].last + 1), g.nx, g.ny, rs[i].last + 2 - rs[i].first, rs[i].first, iso)
            }
        }
        val remoteRun: suspend (Int) -> TriMesh = { i ->
            val id = "mc${ids.incrementAndGet()}-$i"
            val def = results.getOrPut(id) { CompletableDeferred() }
            val payload = SlabMesher.writeFieldSlab(
                g.nx, g.ny, rs[i].last + 2 - rs[i].first, rs[i].first, iso, SlabMesher.slabField(g, rs[i].first, rs[i].last + 1),
            )
            val f = File(workDir, "slab_$id.bin").also { workDir.mkdirs(); it.writeBytes(payload) }
            val tag = "slab-$id.bin"
            try {
                session.sendFile(tag, f)
                session.send(TandemMessage.Task(id, TaskKind.MC_SLAB, tag))
                val res = select<TandemMessage.TaskResult?> { def.onAwait { it }; session.closed.onAwait { null } }
                    ?: throw PeerClosedException("peer dropped")
                if (!res.ok) error(res.error ?: "slab failed")
                val back = session.awaitFile(res.ref)
                SlabMesher.readMeshPiece(back.readBytes()).also { back.delete(); session.forget(res.ref) }
            } finally {
                f.delete()
            }
        }

        val a = launch { worker(0, localRun) }
        val b = launch { worker(1, remoteRun) }
        a.join(); b.join()
        // slabs the helper handed back after the leader had already run out of work
        while (true) {
            val i = lock.withLock { todo.removeFirstOrNull() } ?: break
            pieces[i] = localRun(i)
            counts[0]++
        }
        val mesh = SlabMesher.stitch(pieces.map { requireNotNull(it) { "a slab was not meshed" } }, g)
        MeshOutcome(mesh, counts[0], counts[1])
    }

    /** Ends the session. */
    suspend fun end(reason: String = "done") {
        runCatching { session.send(TandemMessage.Bye(reason)) }
        session.close(reason)
        if (_state.value != TandemState.MERGED) _state.value = TandemState.IDLE
    }

    /** Re-exported so wiring code can build a leader-frame START without importing processing/. */
    fun startRequest(
        mode: CaptureMode, voxelMm: Int, box: DepthBox?, plane: com.example.arruler.objscan.SupportPlane?, yawPriorDeg: Float? = null,
    ) = TandemMessage.StartCapture(
        0L, mode, voxelMm, box?.toSpec(), plane?.toSpec(), box?.centre?.let { listOf(it.x, it.y, it.z) }, yawPriorDeg,
    )
}
