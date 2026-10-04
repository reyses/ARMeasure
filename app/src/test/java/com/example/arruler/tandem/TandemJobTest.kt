package com.example.arruler.tandem

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.Fixtures
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.ObjectPipeline
import com.example.arruler.objscan.ObjectVoxelCloud
import com.example.arruler.objscan.Scene
import com.example.arruler.objscan.SupportPlane
import com.example.arruler.processing.Backend
import com.example.arruler.processing.CloudData
import com.example.arruler.processing.DefaultJobPackager
import com.example.arruler.processing.DefaultPhoneRunner
import com.example.arruler.processing.JobEstimate
import com.example.arruler.processing.JobPackage
import com.example.arruler.processing.JobStatus
import com.example.arruler.processing.JobType
import com.example.arruler.processing.PackageMeta
import com.example.arruler.processing.PcLinkException
import com.example.arruler.processing.ProcessingJob
import com.example.arruler.processing.ProcessingService
import com.example.arruler.processing.ProcessingState
import com.example.arruler.processing.ResultPackage
import com.example.arruler.processing.RouteSignals
import com.example.arruler.processing.Tier
import com.example.arruler.processing.UserPref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import com.example.arruler.objscan.ObjectQuality as DepthQuality
import com.example.arruler.processing.ObjectQuality as JobQuality

/** Test double for the phone side of the capture: hands back a prepared capture and records when it was told to start. */
class FakeCapture(private val data: CaptureResult) : TandemCapture {
    @Volatile var startedAtLocalNs = -1L
    @Volatile var stoppedAtLocalNs = -1L
    @Volatile var request: TandemMessage.StartCapture? = null
    override suspend fun start(req: TandemMessage.StartCapture, atLocalNs: Long) { request = req; startedAtLocalNs = atLocalNs }
    override suspend fun stop(atLocalNs: Long) { stoppedAtLocalNs = atLocalNs }
    override fun result() = data
}

/** One synthetic 200 mm cube scanned by two phones from opposite sides, each in its own ARCore frame. */
class CubeTandem(seed: Long, val skewNs: Long = 1_234_567_890L) {
    val quality = DepthQuality.FINE
    val yawDeg = 71.0
    val truthT = SynthClouds.truth(yawDeg, 0.0, Vec3(-1.4f, 0.25f, 2.1f))
    private val inv = truthT.inverse()
    val leaderBox = Fixtures.userBox(0.2f, 0.2f, 0.2f, 0f, 0.03f)
    val leaderPlane = Fixtures.plane
    val helperBox = ObjectBox(inv.apply(leaderBox.centre), (-Math.toRadians(yawDeg)).toFloat(), leaderBox.w, leaderBox.d, leaderBox.h)
    val helperPlane = SupportPlane.horizontal(Fixtures.plane.d - truthT.t[1].toFloat())

    private fun side(rng: Random, keep: (Float) -> Boolean): Scene {
        val full = Fixtures.boxScene(rng, 0.2f, 0.2f, 0.2f, 0f, 0.003f, quality.voxelSize)
        val out = ArrayList<Float>()
        for (i in 0 until full.points.size / 3) {
            val x = full.points[i * 3]; val y = full.points[i * 3 + 1]; val z = full.points[i * 3 + 2]
            val isObject = i < full.objectPoints
            if (isObject && !keep(leaderBox.toLocal(x, y, z).x)) continue
            out += x; out += y; out += z
        }
        return Scene(out.toFloatArray(), 0)
    }

    private fun capture(points: FloatArray, box: ObjectBox, plane: SupportPlane): CaptureResult {
        val cloud = ObjectVoxelCloud(box, quality.voxelSize)
        cloud.addAll(points)
        val p = cloud.points()
        return CaptureResult(p, cloud.hitsFor(p), box, plane, quality)
    }

    val leaderCapture: CaptureResult
    val helperCapture: CaptureResult

    init {
        val l = side(Random(seed)) { it >= -0.02f }      // the +x half of the cube (plus 4 cm shared)
        val h = side(Random(seed + 1)) { it <= 0.02f }   // the -x half
        leaderCapture = capture(l.points, leaderBox, leaderPlane)
        helperCapture = capture(inv.transformPoints(h.points), helperBox, helperPlane)
    }

    val yawPrior: Float get() = (yawDeg + 12.0).toFloat()
}

class TandemJobTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var scope: CoroutineScope

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    @After fun tearDown() { scope.cancel() }

    private val leaderCaps = PeerCaps("Pixel 11 Pro", Tier.HIGH, benchMs = 400)
    private val helperCaps = PeerCaps("Pixel 9 Pro", Tier.HIGH, benchMs = 640)
    @Volatile private var helperNow = helperCaps

    private class Rig(
        val a: InMemoryChannel, val b: InMemoryChannel, val coordinator: TandemCoordinator, val helper: TandemHelper,
        val leaderCap: FakeCapture, val helperCap: FakeCapture, val skewNs: Long,
    )

    private fun rig(c: CubeTandem, options: TandemOptions, latencyMs: Long = 0): Rig {
        val (a, b) = InMemoryPeers.pair(tmp.newFolder(), latencyMs)
        val ls = TandemSession(a, scope); val hs = TandemSession(b, scope)
        val lc = FakeCapture(c.leaderCapture); val hc = FakeCapture(c.helperCapture)
        val coordinator = TandemCoordinator(
            ls, lc, { leaderCaps }, { System.nanoTime() }, scope, tmp.newFolder(), "leader", "test", options,
        )
        coordinator.yawPrior = c.yawPrior
        coordinator.yawHalfWidth = 35f
        val helper = TandemHelper(hs, hc, { helperNow }, { System.nanoTime() + c.skewNs }, scope, tmp.newFolder(), "Pixel 9 Pro", "test")
        return Rig(a, b, coordinator, helper, lc, hc, c.skewNs)
    }

    private suspend fun runToCapture(r: Rig, c: CubeTandem): Long {
        r.helper.start()
        val caps = r.coordinator.pair()
        assertEquals("Pixel 9 Pro", caps.name)
        assertEquals(TandemState.PAIRED, r.coordinator.state.value)
        val (offset, rtt) = r.coordinator.syncClock()
        println("clock sync: offset error %.3f ms (true skew %d ns), best rtt %.3f ms".format(Math.abs(offset - r.skewNs) / 1e6, r.skewNs, rtt / 1e6))
        assertTrue(Math.abs(offset - r.skewNs) < 5_000_000L)
        assertEquals(TandemState.CLOCK_SYNCED, r.coordinator.state.value)
        val at = r.coordinator.startCapture(r.coordinator.startRequest(CaptureMode.SPIN, 5, c.leaderBox, c.leaderPlane))
        assertEquals(TandemState.CAPTURING, r.coordinator.state.value)
        delay(60)
        r.coordinator.stopCapture()
        delay(60)
        return at
    }

    private fun hullError(m: com.example.arruler.objscan.ObjectMeasurements) = Math.abs(m.hullVolume - 0.008f) / 0.008f

    @Test fun fullTandemObjectJobBeatsEitherPhoneAlone() = runBlocking {
        CloudRegistration.trace = { if (it.startsWith("refine") || it.startsWith("cand")) println("    $it") }
        val c = CubeTandem(7)
        val r = rig(c, TandemOptions(startLeadMs = 50, splitMeshMinCells = 0, slabChunks = 4, registration = RegistrationOptions(spacing = 0.003f)))
        withTimeout(120_000) {
            val at = runToCapture(r, c)
            // both phones were told the same instant, each on its own clock
            val helperAtOnLeaderClock = r.helperCap.startedAtLocalNs - r.skewNs
            println("start instant: leader %d, helper on leader clock %d (diff %.3f ms)".format(r.leaderCap.startedAtLocalNs, helperAtOnLeaderClock, Math.abs(helperAtOnLeaderClock - at) / 1e6))
            assertEquals(at, r.leaderCap.startedAtLocalNs)
            assertTrue(Math.abs(helperAtOnLeaderClock - at) < 5_000_000L)

            val t0 = System.nanoTime()
            val res = r.coordinator.process()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(TandemState.MERGED, r.coordinator.state.value)
            assertFalse("notes: ${res.notes}", res.singlePhone)
            val reg = requireNotNull(res.registration)
            assertTrue(reg.reason, reg.ok)
            val rotErr = reg.helperToLeader.rotationDiffDeg(c.truthT)
            val centre = Vec3(c.helperBox.centre.x, c.helperBox.centre.y + 0.1f, c.helperBox.centre.z)
            val transErr = reg.helperToLeader.displacementAt(c.truthT, centre)
            println("registration: fitness %.2f, rot err %.3f deg, trans err %.2f mm".format(reg.fitness, rotErr, transErr * 1000))

            val m = res.measures
            val leaderAlone = ObjectPipeline.run(c.leaderCapture.points, c.leaderCapture.hits, c.leaderBox, c.leaderPlane, c.quality).measures
            val helperAlone = ObjectPipeline.run(c.helperCapture.points, c.helperCapture.hits, c.helperBox, c.helperPlane, c.quality).measures
            println(
                "TANDEM JOB (%d ms total, timings %s, slabs leader %d / helper %d)".format(ms, res.timingsMs, res.slabsLeader, res.slabsHelper),
            )
            println("  merged : L %.1f W %.1f H %.1f mm, hull %.3f L (truth 8.000), err %.1f %%".format(m.footprint.length * 1e3, m.footprint.width * 1e3, m.maxHeight * 1e3, m.hullVolume * 1e3, hullError(m) * 100))
            println("  leader : L %.1f W %.1f H %.1f mm, hull %.3f L, err %.1f %%".format(leaderAlone.footprint.length * 1e3, leaderAlone.footprint.width * 1e3, leaderAlone.maxHeight * 1e3, leaderAlone.hullVolume * 1e3, hullError(leaderAlone) * 100))
            println("  helper : L %.1f W %.1f H %.1f mm, hull %.3f L, err %.1f %%".format(helperAlone.footprint.length * 1e3, helperAlone.footprint.width * 1e3, helperAlone.maxHeight * 1e3, helperAlone.hullVolume * 1e3, hullError(helperAlone) * 100))
            println("  mesh volume %.3f L, %d triangles, bad edges %d".format((res.meshVolume ?: 0.0) * 1e3, res.mesh?.triangleCount ?: 0, res.mesh?.badEdgeCount() ?: -1))

            // Absolute accuracy is the objscan pipeline's: on this 3 mm-noise scene it already reads the full 200 mm length as
            // leaderAlone.footprint.length (about +4 mm) when one phone sees all of it. What the merge must add is (almost) nothing:
            // both footprint sides within 1.5 mm of that single-phone reading, the height within 3 mm of truth, everything within 6 mm.
            assertTrue(Math.abs(m.footprint.length - 0.2f) < 0.006f)
            assertTrue(Math.abs(m.footprint.width - 0.2f) < 0.006f)
            assertTrue("width ${m.footprint.width} vs the pipeline's own full-length reading ${leaderAlone.footprint.length}", Math.abs(m.footprint.width - leaderAlone.footprint.length) < 0.0015f)
            assertTrue(Math.abs(m.footprint.length - leaderAlone.footprint.length) < 0.0015f)
            assertTrue(Math.abs(m.maxHeight - 0.2f) < 0.003f)
            assertTrue("the overlap is a strip of parallel faces: one direction must be reported as following the constraints", reg.underconstrained)
            assertTrue("merged ${hullError(m)} vs leader ${hullError(leaderAlone)}", hullError(m) < hullError(leaderAlone))
            assertTrue("merged ${hullError(m)} vs helper ${hullError(helperAlone)}", hullError(m) < hullError(helperAlone))
            assertTrue(hullError(m) < 0.05)
            assertEquals(4, res.slabsLeader + res.slabsHelper)
            val mesh = res.mesh!!
            assertEquals(0, mesh.badEdgeCount())
            assertTrue(Math.abs(mesh.volume() - 0.008) / 0.008 < 0.06)
            assertEquals("tandem: 2 phones", res.toResultJson(ms).stats.notes.first())
            // the helper sees the end of the job too
            withTimeout(5_000) { while (r.helper.completed.value == null) delay(10) }
            assertFalse(r.helper.completed.value!!.singlePhone)
            assertEquals(TandemState.MERGED, r.helper.state.value)
        }
    }

    @Test fun peerDroppingWhileSendingItsResultLeavesASinglePhoneResult() = runBlocking {
        val c = CubeTandem(11)
        val r = rig(c, TandemOptions(startLeadMs = 50, helperTimeoutMs = 20_000, registration = RegistrationOptions(spacing = 0.003f)))
        r.b.dropOnFile = { it.startsWith("partial") }
        withTimeout(120_000) {
            runToCapture(r, c)
            val res = r.coordinator.process()
            println("peer drop: single=%b, notes=%s".format(res.singlePhone, res.notes))
            assertTrue(res.singlePhone)
            assertTrue(res.notes.any { it.contains("dropped") || it.contains("lost") })
            assertEquals(TandemState.MERGED, r.coordinator.state.value)
            val alone = ObjectPipeline.run(c.leaderCapture.points, c.leaderCapture.hits, c.leaderBox, c.leaderPlane, c.quality).measures
            assertEquals(alone.hullVolume, res.measures.hullVolume, 1e-6f)
            assertEquals("single-phone", res.toResultJson(1).stats.notes.first())
        }
    }

    @Test fun peerDroppingDuringMeshingHandsTheSlabsBackToTheLeader() = runBlocking {
        val c = CubeTandem(13)
        val r = rig(c, TandemOptions(startLeadMs = 50, splitMeshMinCells = 0, slabChunks = 6, registration = RegistrationOptions(spacing = 0.003f)))
        r.b.dropOnFile = { it.startsWith("piece") }
        withTimeout(120_000) {
            runToCapture(r, c)
            val res = r.coordinator.process()
            println("drop during meshing: single=%b, slabs leader %d helper %d, notes=%s".format(res.singlePhone, res.slabsLeader, res.slabsHelper, res.notes))
            assertFalse(res.singlePhone)                    // the helper's cloud was already merged
            assertEquals(6, res.slabsLeader + res.slabsHelper)
            assertTrue(res.notes.any { it.contains("re-queued") })
            val mesh = res.mesh!!
            assertEquals(0, mesh.badEdgeCount())
            assertTrue(Math.abs(mesh.volume() - 0.008) / 0.008 < 0.06)
        }
    }

    @Test fun missingPeerRunsAloneAndSaysSo() = runBlocking {
        val c = CubeTandem(17)
        val r = rig(c, TandemOptions(startLeadMs = 50, helperTimeoutMs = 5_000))
        withTimeout(60_000) {
            runToCapture(r, c)
            r.a.close("test: peer walked away")
            val res = r.coordinator.process()
            assertTrue(res.singlePhone)
            assertTrue(res.notes.any { it.contains("not reachable") })
        }
    }

    @Test fun throttlingHelperStatusReachesThePlanner() = runBlocking {
        val c = CubeTandem(19)
        val r = rig(c, TandemOptions(startLeadMs = 50))
        withTimeout(60_000) {
            r.helper.start()
            r.coordinator.pair()
            val job = TandemJobSpec(JobType.OBJECT_MESH, JobQuality.FINE, 150_000, 120_000, 60_000_000, 400_000)
            val before = r.coordinator.currentPlan(job).leaderShare
            // the helper phone heats up and reports it
            helperNow = PeerCaps("Pixel 9 Pro", Tier.HIGH, benchMs = 640, thermalStatus = 3, thermalHeadroom = 1.1f)
            r.helper.reportStatus()
            withTimeout(5_000) { while (r.coordinator.peer.value?.thermalStatus != 3) delay(10) }
            val after = r.coordinator.currentPlan(job).leaderShare
            println("planner leader share: %.3f -> %.3f after the helper reports thermal status 3".format(before, after))
            assertTrue(after > before + 0.1)
        }
    }

    // ------------------------------------------------------------------------------------------ the peer as a processing backend

    private fun pointJob(): Triple<ProcessingJob, CloudData, Scene> {
        val scene = Fixtures.boxScene(Random(5), 0.2f, 0.2f, 0.2f, 20f, 0.003f, 0.003f)
        val box = Fixtures.userBox(0.2f, 0.2f, 0.2f, 20f, 0.03f)
        val cloud = ObjectVoxelCloud(box, 0.003f)
        cloud.addAll(scene.points)
        val p = cloud.points()
        val data = CloudData(p, cloud.hitsFor(p), IntArray(p.size / 3) { 255 })
        val job = ProcessingJob(
            JobType.OBJECT_MESH, JobQuality.FINE, JobEstimate(pointCount = data.count), cloud = data, workDir = tmp.newFolder(),
            objectBox = box, supportPlane = Fixtures.plane,
        )
        return Triple(job, data, scene)
    }

    @Test fun peerBackendSpeaksThePcLinkContract() = runBlocking {
        val (a, b) = InMemoryPeers.pair(tmp.newFolder())
        val ls = TandemSession(a, scope); val hs = TandemSession(b, scope)
        val backend = TandemBackend(ls, scope, { "Pixel 9 Pro" }, "test")
        val helper = TandemHelper(hs, FakeCapture(CubeTandem(3).leaderCapture), { helperCaps }, { System.nanoTime() }, scope, tmp.newFolder())
        helper.start()
        withTimeout(120_000) {
            assertEquals("Pixel 9 Pro", backend.ping().name)
            val (job, data, _) = pointJob()
            val zip = File(job.workDir, "job.zip")
            JobPackage.writePointJob(zip, JobType.OBJECT_MESH, data, PackageMeta("test", "2026-10-03T00:00:00Z", null, JobQuality.FINE, job.objectBox, job.supportPlane))
            val id = backend.submit(zip, JobType.OBJECT_MESH)
            var st: JobStatus
            do { delay(20); st = backend.status(id) } while (st !is JobStatus.Done && st !is JobStatus.Failed)
            assertEquals(JobStatus.Done, st)
            val out = backend.download(id, File(job.workDir, "res.zip"))
            val res = ResultPackage.read(out)
            val local = DefaultPhoneRunner().run(job)
            println("peer backend result: volume %.3f L (local run %.3f L), backend=%s, files=%s".format(
                res.result.measures.volumeM3!!.recommended * 1e3, local.measures.volumeM3!!.recommended * 1e3, res.result.stats.backend, res.entries,
            ))
            assertEquals("peer", res.result.stats.backend)
            assertEquals(local.measures.volumeM3.recommended, res.result.measures.volumeM3.recommended, 1e-9)
            assertTrue("mesh.ply" in res.entries)
        }
    }

    @Test fun processingServiceRoutesToThePeerThroughThePcContract() = runBlocking {
        val (a, b) = InMemoryPeers.pair(tmp.newFolder())
        val ls = TandemSession(a, scope); val hs = TandemSession(b, scope)
        val backend = TandemBackend(ls, scope)
        TandemHelper(hs, FakeCapture(CubeTandem(3).leaderCapture), { helperCaps }, { System.nanoTime() }, scope, tmp.newFolder()).start()
        val (job, _, _) = pointJob()
        // FINE on a LOW phone is PC-only work: with the peer standing in for the PC backend the service runs it there
        val svc = ProcessingService(
            { RouteSignals(Tier.LOW, pcAvailable = true, userPref = UserPref.AUTO, onWifi = true, batteryLow = false, thermal = 0) },
            backend, DefaultJobPackager("test"), delayFn = { delay(10) },
        )
        withTimeout(120_000) {
            val states = svc.process(job).toList()
            val done = states.last()
            println("service states: " + states.joinToString(" > ") { it::class.simpleName ?: "?" })
            assertTrue(done.toString(), done is ProcessingState.Done)
            done as ProcessingState.Done
            assertEquals(Backend.PC, done.backend)       // the slot Backend.PEER will take (docs/TANDEM.md)
            assertEquals("peer", done.result.stats.backend)
            assertTrue(states.any { it is ProcessingState.Uploading })
        }
    }

    @Test fun backendReportsALostPeerAsANetworkError() = runBlocking {
        val (a, b) = InMemoryPeers.pair(tmp.newFolder())
        val ls = TandemSession(a, scope)
        TandemSession(b, scope)
        val backend = TandemBackend(ls, scope)
        a.close("gone")
        delay(50)
        val err = runCatching { backend.ping() }.exceptionOrNull()
        assertTrue(err is PcLinkException && err.network)
    }
}
