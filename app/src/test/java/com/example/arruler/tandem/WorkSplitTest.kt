package com.example.arruler.tandem

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.MarchingCubes
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.ObjectMeshBuilder
import com.example.arruler.objscan.SupportPlane
import com.example.arruler.objscan.TriMesh
import com.example.arruler.processing.JobType
import com.example.arruler.processing.ObjectQuality
import com.example.arruler.processing.Tier
import com.example.arruler.texture.BakedMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class WorkSplitTest {
    // Pixel 11 Pro (main), Pixel 9 Pro, Pixel 8 Pro: benchmark times chosen so the first pair has the 1.6x ratio of the brief
    private val p11 = PeerCaps("Pixel 11 Pro", Tier.HIGH, benchMs = 400)
    private val p9 = PeerCaps("Pixel 9 Pro", Tier.HIGH, benchMs = 640)
    private val p8 = PeerCaps("Pixel 8 Pro", Tier.HIGH, benchMs = 700)

    private val bigJob = TandemJobSpec(
        JobType.OBJECT_MESH, ObjectQuality.FINE, leaderPoints = 150_000, helperPoints = 120_000, meshCells = 60_000_000, triangles = 400_000,
        textured = true, helperTriangleShare = 0.45,
    )

    // ------------------------------------------------------------------------------------------ speed model

    @Test fun thermalDerateIsMonotonicAndBounded() {
        val f = (0..6).map { SpeedModel.derate(it, null, 80, false) }
        for (i in 1 until f.size) assertTrue(f[i] <= f[i - 1])
        assertEquals(1.0, f[0], 0.0)
        assertTrue(f.last() >= 0.2 - 1e-9)
        val h = listOf(0.3f, 0.7f, 0.85f, 1.0f, 1.2f, 1.6f).map { SpeedModel.derate(0, it, 80, false) }
        for (i in 1 until h.size) assertTrue(h[i] <= h[i - 1])
        assertEquals(1.0, SpeedModel.derate(0, Float.NaN, 80, false), 0.0)
        assertEquals(0.85, SpeedModel.derate(0, null, 10, false), 1e-9)
        assertEquals(1.0, SpeedModel.derate(0, null, 10, true), 1e-9)
    }

    @Test fun benchmarkSpeedBeatsTierGuess() {
        assertEquals(2.5, p11.speed(), 1e-9)
        assertEquals(1.6, p11.speed() / p9.speed(), 1e-9)
        assertEquals(1.0, PeerCaps("x", Tier.MID).speed(), 0.0)
    }

    // ------------------------------------------------------------------------------------------ leader and proportions

    @Test fun fasterPhoneLeads() {
        assertEquals(0, LeaderChoice.pick(p11, p9))
        assertEquals(1, LeaderChoice.pick(p9, p11))
        assertEquals(0, LeaderChoice.pick(p9, p8.copy(benchMs = 620)))   // within 5 %: the phone in the hand keeps the lead
        assertEquals(1, LeaderChoice.pick(p9, p9.copy(thermalStatus = 0).let { PeerCaps("fast", Tier.HIGH, benchMs = 500) }))
        // a hot fast phone loses the lead to a cool slower one
        val hot = p11.copy(thermalStatus = 4)
        assertEquals(1, LeaderChoice.pick(hot, p9))
    }

    @Test fun splitIsProportionalToMeasuredSpeedAtOnePointSixRatio() {
        val plan = WorkSplit.plan(bigJob, p11, p9, pcAvailable = false)
        assertEquals(PlanBackend.TANDEM, plan.backend)
        assertEquals(2, plan.slabWeights.size)
        val ratio = plan.slabWeights[0] / plan.slabWeights[1]
        println("1.6x pair: slab weights %.3f / %.3f (ratio %.3f), expected %d ms vs solo %d ms (x%.2f)".format(plan.slabWeights[0], plan.slabWeights[1], ratio, plan.expectedMs, plan.soloMs, plan.speedup))
        assertEquals(1.6, ratio, 1e-6)
        assertEquals(1.0, plan.slabWeights.sum(), 1e-9)
        assertEquals(plan.slabWeights[0], plan.leaderShare, 1e-12)
        // the mesh step finishes both halves at about the same time (proportional split)
        val mesh = plan.steps.first { it.name == "mesh" }
        assertTrue("mesh halves ${mesh.leaderMs} vs ${mesh.helperMs}", abs(mesh.leaderMs - mesh.helperMs) < 0.35 * maxOf(mesh.leaderMs, mesh.helperMs))
        assertTrue(plan.expectedMs < plan.soloMs)
    }

    @Test fun similarPhonesLandNearFiftyFifty() {
        val plan = WorkSplit.plan(bigJob, p9, p8, pcAvailable = false)
        println("9 Pro + 8 Pro: leader share %.3f".format(plan.leaderShare))
        assertTrue(plan.leaderShare in 0.48..0.56)
    }

    @Test fun thermalStateShiftsTheSplit() {
        val cool = WorkSplit.plan(bigJob, p9, p8, false).leaderShare
        val helperHot = WorkSplit.plan(bigJob, p9, p8.copy(thermalStatus = 3), false).leaderShare
        val leaderHot = WorkSplit.plan(bigJob, p9.copy(thermalHeadroom = 1.2f), p8, false).leaderShare
        assertTrue(helperHot > cool + 0.1)
        assertTrue(leaderHot < cool - 0.05)
    }

    @Test fun detailedGoesToThePcAndTandemDoesNotReplaceIt() {
        val detailed = bigJob.copy(type = JobType.PHOTOGRAMMETRY, quality = ObjectQuality.DETAILED)
        assertEquals(PlanBackend.PC, WorkSplit.plan(detailed, p11, p9, true).backend)
        val blocked = WorkSplit.plan(detailed, p11, p9, false)
        assertEquals(PlanBackend.BLOCKED, blocked.backend)
        assertTrue(blocked.reason.contains("PC"))
    }

    @Test fun noPeerOrTinyJobStaysOnTheLeader() {
        assertEquals(PlanBackend.LEADER_ONLY, WorkSplit.plan(bigJob, p11, null, false).backend)
        val tiny = TandemJobSpec(JobType.OBJECT_MESH, ObjectQuality.QUICK, leaderPoints = 2_000, helperPoints = 0, meshCells = 50_000, triangles = 5_000)
        val plan = WorkSplit.plan(tiny, p11, p9, false)
        assertEquals(PlanBackend.LEADER_ONLY, plan.backend)
        // a dual capture is always worth merging, however small
        val dual = tiny.copy(helperPoints = 2_000)
        assertEquals(PlanBackend.TANDEM, WorkSplit.plan(dual, p11, p9, false).backend)
    }

    @Test fun roomAnalyzeIsSplitByScan() {
        val room = TandemJobSpec(JobType.SCAN_ANALYZE, null, leaderPoints = 400_000, helperPoints = 300_000)
        val plan = WorkSplit.plan(room, p9, p8, false)
        assertEquals(PlanBackend.TANDEM, plan.backend)
        assertEquals(listOf("analyze", "merge"), plan.steps.map { it.name })
    }

    // ------------------------------------------------------------------------------------------ re-balancing mid job

    @Test fun halvedSpeedMidJobIsRebalancedAndBeatsTheStaticPlan() {
        // 24 equal slabs of 0.5 work units; both phones start at speed 1.0, the helper throttles to 0.5 at t = 2 s
        val costs = DoubleArray(24) { 0.5 }
        val speedAt = { dev: Int, t: Double -> if (dev == 1 && t >= 2.0) 0.5 else 1.0 }
        val static = ChunkScheduler.simulateStatic(costs, speedAt)
        val dynamic = ChunkScheduler.simulateDynamic(costs, speedAt)
        val helperStatic = static.assignment.count { it == 1 }
        val helperDynamic = dynamic.assignment.count { it == 1 }
        println("thermal halving: static %.2f s (helper %d slabs), re-planned %.2f s (helper %d slabs)".format(static.makespanS, helperStatic, dynamic.makespanS, helperDynamic))
        assertEquals(12, helperStatic)
        assertTrue("helper keeps fewer slabs: $helperDynamic", helperDynamic < helperStatic)
        assertTrue("re-planned ${dynamic.makespanS} < static ${static.makespanS}", dynamic.makespanS < static.makespanS - 0.5)
        // and it cannot beat the ideal fluid bound
        val ideal = costs.sum() / (1.0 + 0.5) // after the throttle the pair delivers 1.5 units / s
        assertTrue(dynamic.makespanS >= costs.sum() / 2.0 - 1e-9 && dynamic.makespanS < ideal * 1.4)
    }

    @Test fun unthrottledPairIsSplitEvenlyByTheDynamicScheduler() {
        val costs = DoubleArray(20) { 1.0 }
        val out = ChunkScheduler.simulateDynamic(costs) { _, _ -> 1.0 }
        assertEquals(10, out.assignment.count { it == 0 })
        assertEquals(10.0, out.makespanS, 0.01)
    }

    @Test fun dynamicSchedulerFollowsAOnePointSixRatio() {
        val costs = DoubleArray(26) { 1.0 }
        val out = ChunkScheduler.simulateDynamic(costs) { dev, _ -> if (dev == 0) 1.6 else 1.0 }
        val leader = out.assignment.count { it == 0 }
        println("1.6x ratio over 26 equal slabs: leader %d, helper %d, makespan %.2f s".format(leader, 26 - leader, out.makespanS))
        assertEquals(16, leader)
    }

    @Test fun lastSlabGoesToTheFasterPhoneEvenIfTheSlowOneIsIdle() {
        // helper idle, leader busy for 0.1 s but 4x faster: the helper must not take a 1 unit slab it would need 1 s for
        val busy = doubleArrayOf(0.1, 0.0)
        val speeds = doubleArrayOf(4.0, 1.0)
        assertTrue(!ChunkScheduler.shouldTake(1, 1.0, busy, speeds, 0.0))
        assertTrue(ChunkScheduler.shouldTake(0, 1.0, busy, speeds, 0.0))
    }

    // ------------------------------------------------------------------------------------------ slab marching cubes

    private fun sphereField(n: Int): FloatArray {
        val c = (n - 1) / 2.0
        val r = n * 0.32
        return FloatArray(n * n * n) { idx ->
            val i = idx % n; val j = (idx / n) % n; val k = idx / (n * n)
            val d = sqrt((i - c) * (i - c) + (j - c) * (j - c) + (k - c) * (k - c))
            (0.5 + (r - d) / 1.5).coerceIn(0.0, 1.0).toFloat()
        }
    }

    @Test fun stitchedSlabsAreWatertightAndEqualTheUnsplitMesh() {
        val n = 41
        val f = sphereField(n)
        val box = ObjectBox(Vec3(0f, 0f, 0f), 0f, 1f, 1f, 1f)
        val g = FieldGrid(f, n, n, n, 0.005f, 0f, 0f, 0f, box)
        val whole = MarchingCubes.extract(f, n, n, n, 0.5f, 0.005f)
        assertEquals(0, whole.badEdgeCount())
        for (weights in listOf(listOf(1.0), listOf(1.0, 1.0), listOf(1.6, 1.0), listOf(1.0, 1.0, 1.0), List(7) { 1.0 }, listOf(5.0, 1.0, 1.0, 5.0))) {
            val ranges = SlabMesher.ranges(n, weights)
            assertEquals(0, ranges.first().first)
            assertEquals(n - 2, ranges.last().last)
            for (i in 1 until ranges.size) assertEquals(ranges[i - 1].last + 1, ranges[i].first)   // contiguous, no cube layer twice
            val pieces = ranges.map { r -> SlabMesher.extractIndexSpace(SlabMesher.slabField(g, r.first, r.last + 1), n, n, r.last + 2 - r.first, r.first, 0.5f) }
            if (ranges.size > 1) assertTrue("each slab alone is open at the cut", pieces.any { it.badEdgeCount() > 0 })
            val merged = SlabMesher.stitch(pieces, g)
            assertEquals("bad edges with ${ranges.size} slabs", 0, merged.badEdgeCount())
            assertEquals("winding with ${ranges.size} slabs", 0, merged.inconsistentEdgeCount())
            assertEquals("triangles with ${ranges.size} slabs", whole.triangleCount, merged.triangleCount)
            assertEquals("vertices with ${ranges.size} slabs", whole.vertexCount, merged.vertexCount)
            assertEquals(whole.volume(), merged.volume(), whole.volume() * 1e-5)
        }
        println("slab stitching: 1, 2, 3, 7 and uneven splits of a %d^3 sphere field all give %d triangles, 0 bad edges, 0 flipped edges".format(n, whole.triangleCount))
    }

    @Test fun slabMesherMatchesObjectMeshBuilderOnACube() {
        val base = Vec3(1f, 0.8f, -0.5f)
        val box = ObjectBox(base, 0f, 0.3f, 0.3f, 0.3f)
        val plane = SupportPlane.horizontal(0.8f)
        val pts = SynthClouds.boxSurface(0.2f, 0.2f, 0.2f, 0.005f).let { s -> FloatArray(s.size * 3).also { o -> for ((i, p) in s.withIndex()) { o[i * 3] = base.x + p.x; o[i * 3 + 1] = base.y + p.y; o[i * 3 + 2] = base.z + p.z } } }
        val ref = ObjectMeshBuilder.build(pts, box, plane, 0.005f)
        assertNotNull(ref)
        for (w in listOf(listOf(1.0), listOf(1.0, 1.0), listOf(1.6, 1.0), List(5) { 1.0 })) {
            val m = SlabMesher.mesh(pts, box, plane, 0.005f, w)
            assertNotNull(m)
            assertEquals(ref!!.triangleCount, m!!.triangleCount)
            assertEquals(ref.volume(), m.volume(), ref.volume() * 1e-4)
            assertEquals(ref.badEdgeCount(), m.badEdgeCount())
        }
    }

    @Test fun meshPieceAndFieldSlabWireFormatsRoundTrip() {
        val n = 21
        val f = sphereField(n)
        val g = FieldGrid(f, n, n, n, 0.01f, 0f, 0f, 0f, ObjectBox(Vec3(0f, 0f, 0f), 0f, 1f, 1f, 1f))
        val sub = SlabMesher.slabField(g, 3, 12)
        val payload = SlabMesher.writeFieldSlab(n, n, 10, 3, 0.5f, sub)
        val piece = SlabMesher.readMeshPiece(SlabMesher.processFieldSlab(payload))
        val direct = SlabMesher.extractIndexSpace(sub, n, n, 10, 3, 0.5f)
        assertEquals(direct.triangleCount, piece.triangleCount)
        assertTrue(direct.vertices.contentEquals(piece.vertices))
        assertTrue(direct.indices.contentEquals(piece.indices))
    }

    // ------------------------------------------------------------------------------------------ texture split

    private fun cubeMesh(): TriMesh {
        val base = Vec3(0f, 0f, 0f)
        val box = ObjectBox(base, 0f, 0.3f, 0.3f, 0.3f)
        val pts = SynthClouds.boxSurface(0.2f, 0.2f, 0.2f, 0.005f).let { s -> FloatArray(s.size * 3).also { o -> for ((i, p) in s.withIndex()) { o[i * 3] = p.x; o[i * 3 + 1] = p.y; o[i * 3 + 2] = p.z } } }
        return ObjectMeshBuilder.build(pts, box, SupportPlane.horizontal(0f), 0.005f)!!
    }

    @Test fun triangleOwnershipFollowsTheCameras() {
        val mesh = cubeMesh()
        val leader = floatArrayOf(1.5f, 0.1f, 0f, 1.2f, 0.4f, 0.3f)      // looking at the +x side and the top
        val helper = floatArrayOf(-1.5f, 0.1f, 0f, -1.2f, 0.4f, -0.3f)   // looking at the -x side
        val own = TriangleOwnership.assign(mesh, leader, helper)
        var xPlus = 0; var xPlusLeader = 0; var xMinus = 0; var xMinusHelper = 0
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[t * 3] * 3; val b = mesh.indices[t * 3 + 1] * 3; val c = mesh.indices[t * 3 + 2] * 3
            val v = mesh.vertices
            val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
            val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-12f) continue
            val cx = (v[a] + v[b] + v[c]) / 3f
            if (nx / len > 0.95f && cx > 0.09f) { xPlus++; if (own[t] == 0) xPlusLeader++ }
            if (nx / len < -0.95f && cx < -0.09f) { xMinus++; if (own[t] == 1) xMinusHelper++ }
        }
        assertTrue("$xPlus / $xMinus", xPlus > 100 && xMinus > 100)
        assertEquals(xPlus, xPlusLeader)
        assertEquals(xMinus, xMinusHelper)
        val share = TriangleOwnership.helperShare(own)
        assertTrue("helper share $share", share in 0.15..0.5)
    }

    @Test fun subMeshesPartitionTheMeshExactly() {
        val mesh = cubeMesh()
        val own = IntArray(mesh.triangleCount) { if (it % 3 == 0) 1 else 0 }
        val a = MeshSplit.subMesh(mesh, MeshSplit.idsOf(own, 0))
        val b = MeshSplit.subMesh(mesh, MeshSplit.idsOf(own, 1))
        assertEquals(mesh.triangleCount, a.triangleCount + b.triangleCount)
        assertTrue(a.indices.all { it in 0 until a.vertexCount } && b.indices.all { it in 0 until b.vertexCount })
        assertTrue(a.surfaceArea() + b.surfaceArea() in mesh.surfaceArea() * 0.999..mesh.surfaceArea() * 1.001)
    }

    private fun bakedQuad(size: Int, color: Int, u0: Float, v0: Float): BakedMesh {
        val verts = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val mesh = TriMesh(verts, FloatArray(9) { if (it % 3 == 2) 1f else 0f }, intArrayOf(0, 1, 2))
        val uvs = floatArrayOf(u0, v0, u0 + 0.25f, v0, u0, v0 + 0.25f)
        return BakedMesh(mesh, uvs, IntArray(size * size) { color + (it % size) }, size, intArrayOf(0, 1, 2), AtlasPacker.stats(1, 0, size))
    }

    @Test fun packedAtlasKeepsEveryTexelOfBothHalves() {
        val a = bakedQuad(8, 0xFF110000.toInt(), 0.5f, 0.25f)
        val b = bakedQuad(16, 0xFF002200.toInt(), 0.125f, 0.5f)
        val p = AtlasPacker.pack(a, b)
        assertEquals(24, p.width); assertEquals(16, p.height)
        assertEquals(6, p.mesh.vertexCount); assertEquals(2, p.mesh.triangleCount)
        fun sample(u: Float, v: Float) = p.atlas[(v * p.height).toInt().coerceIn(0, p.height - 1) * p.width + (u * p.width).toInt().coerceIn(0, p.width - 1)]
        fun texel(m: BakedMesh, u: Float, v: Float) = m.atlas[(v * m.atlasSize).toInt() * m.atlasSize + (u * m.atlasSize).toInt()]
        for (i in 0 until 3) {
            assertEquals(texel(a, a.uvs[i * 2], a.uvs[i * 2 + 1]), sample(p.uvs[i * 2], p.uvs[i * 2 + 1]))
            assertEquals(texel(b, b.uvs[i * 2], b.uvs[i * 2 + 1]), sample(p.uvs[(3 + i) * 2], p.uvs[(3 + i) * 2 + 1]))
        }
        assertTrue(p.uvs.all { it in 0f..1f })
    }

    // ------------------------------------------------------------------------------------------ routing

    @Test fun routingPrefersPcForDetailedAndPeerForQuickFineOnLowerTiers() {
        val d = ObjectQuality.DETAILED
        assertEquals(PeerRoute.NONE, TandemRouting.decide(JobType.PHOTOGRAMMETRY, d, Tier.LOW, true, true, true, true))
        assertEquals(PeerRoute.PEER, TandemRouting.decide(JobType.OBJECT_MESH, ObjectQuality.QUICK, Tier.MID, true, false, false, false))
        assertEquals(PeerRoute.PEER, TandemRouting.decide(JobType.OBJECT_MESH, ObjectQuality.FINE, Tier.LOW, true, false, false, false))
        assertEquals(PeerRoute.NONE, TandemRouting.decide(JobType.OBJECT_MESH, ObjectQuality.FINE, Tier.HIGH, true, false, false, false))
        assertEquals(PeerRoute.PEER, TandemRouting.decide(JobType.OBJECT_MESH, ObjectQuality.FINE, Tier.HIGH, true, true, true, true))   // dual capture always merges
        assertEquals(PeerRoute.NONE, TandemRouting.decide(JobType.OBJECT_MESH, ObjectQuality.FINE, Tier.MID, true, false, true, true))     // the router already chose the PC
        assertEquals(PeerRoute.NONE, TandemRouting.decide(JobType.OBJECT_MESH, ObjectQuality.QUICK, Tier.LOW, false, true, false, false))   // not paired
    }
}
