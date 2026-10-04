package com.example.arruler.depth

import com.example.arruler.geometry.Vec3
import com.example.arruler.scan3d.RealScanFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Room scanning on the realistic depth simulator ([RoomScanSimulator], [DepthNoiseSim]): the failure of
 * 2026-10-03 reproduced with the old extractor, and the acceptance numbers of the new pipeline.
 */
class RoomScanRealismTest {

    private fun describe(planes: List<ExtractedPlane>) = planes.joinToString("\n") {
        "  %-7s n=(%.2f,%.2f,%.2f) d=%.2f c=(%.2f,%.2f,%.2f) inl=%d sig=%.3f tilt=%.1f free=%.3f fit=%.3f merged=%d src=%s".format(
            it.kind, it.normal.x, it.normal.y, it.normal.z, it.d, it.centroid.x, it.centroid.y, it.centroid.z, it.inlierCount,
            it.sigma, it.freeTiltDeg, it.freeRms, it.fitRms, it.mergedFrom, it.source,
        )
    }

    private fun kinds(planes: List<ExtractedPlane>) = PlaneKind.entries.joinToString(" ") { k -> "$k=${planes.count { it.kind == k }}" }

    private fun boxCloud(seed: Int): CloudObservations {
        val box = RoomScanSimulator.box()
        return RoomScanSimulator().scan(box, RoomScanSimulator.sweep(box, RoomScanSimulator.boxStations(), 400, seed, dwell = 1.0), seed)
            .observations(ScanLogic.ANALYZE_MIN_HITS)
    }

    private fun aligned(planes: List<ExtractedPlane>, deg: Double, tolDeg: Double) = planes.filter {
        val n = Vec3(it.normal.x, 0f, it.normal.z)
        n.length() > 0.5f && abs(n.normalized().dot(Vec3(cos(deg).toFloat(), 0f, sin(deg).toFloat()))) >= cos(Math.toRadians(tolDeg))
    }

    // ---- the noise model ----

    /** Offsets (m, along each slice's own normal) of the planes within 30 deg of [ref] that [planes] cut wall A into. */
    private fun sliceOffsets(normals: List<Vec3>, ds: List<Float>, ref: Vec3): List<Float> = normals.indices.mapNotNull { i ->
        val n = normals[i]
        if (abs(n.y) >= 0.5f) return@mapNotNull null
        val c = n.dot(ref)
        if (abs(c) < cos(Math.toRadians(30.0)).toFloat()) null else if (c > 0) ds[i] else -ds[i]
    }

    @Test fun noiseModelReproducesTheOwnersSliceSpread() {
        // the owner's scan: the old extractor cut wall A into 7 slices whose offsets spread ~0.28 m
        val real = RealScanFixture.planes()
        val ref = real.first { it.kind == PlaneKind.WALL }.let { Vec3(it.nx, 0f, it.nz).normalized() }
        val realOffsets = sliceOffsets(real.map { Vec3(it.nx, it.ny, it.nz) }, real.map { it.d }, ref)
        assertEquals(7, realOffsets.size)
        val realSpread = realOffsets.max() - realOffsets.min()
        assertEquals(0.28f, realSpread, 0.01f)

        // the simulator, same capture, same old extractor: the same kind of slicing
        val a = Math.toRadians(WALL_A_DEG)
        val simRef = Vec3(-cos(a).toFloat(), 0f, -sin(a).toFloat())
        val spreads = (1..4).map { seed ->
            val (room, path) = fixtureLike(seed)
            val obs = RoomScanSimulator().scan(room, path, seed).observations(ScanLogic.ANALYZE_MIN_HITS)
            val legacy = PlaneExtractor.legacy(random = Random(seed)).extract(obs.xyz)
            val off = sliceOffsets(legacy.map { it.normal }, legacy.map { it.d }, simRef)
            assertTrue("seed $seed: ${off.size} slices", off.size >= 5)
            off.max() - off.min()
        }
        val mean = spreads.average().toFloat()
        assertTrue("simulated slice spread $spreads (mean $mean m) vs the owner's $realSpread m", abs(mean - realSpread) < 0.35f * realSpread)
        assertEquals(0.164f, DepthNoiseModel.DEFAULT.sigma(2.3f), 0.002f)
    }

    // ---- the failure, reproduced ----

    @Test fun oldExtractorSlicesTheBoxAndLosesTheRoom() {
        for (seed in 1..3) {
            val obs = boxCloud(seed)
            val legacy = PlaneExtractor.legacy(random = Random(seed)).extract(obs.xyz)
            val horizontal = legacy.count { it.kind == PlaneKind.FLOOR || it.kind == PlaneKind.CEILING }
            assertTrue("seed $seed: floor/ceiling slices\n${describe(legacy)}", horizontal >= 3)
            assertTrue("seed $seed: walls\n${describe(legacy)}", legacy.count { it.kind == PlaneKind.WALL } < 4 || legacy.size >= 8)
            assertNull("seed $seed: old extractor made a room\n${describe(legacy)}", RoomFromPlanes.build(legacy))
            val now = PlaneExtractor(random = Random(seed)).extract(obs)
            println("box seed $seed old: ${kinds(legacy)} -> new: ${kinds(now)}")
        }
    }

    @Test fun oldExtractorSlicesTheOwnersWallAndNewOneMergesIt() {
        for (seed in 1..2) {
            val (room, path) = fixtureLike(seed)
            val obs = RoomScanSimulator().scan(room, path, seed).observations(ScanLogic.ANALYZE_MIN_HITS)
            val a = Math.toRadians(WALL_A_DEG); val b = Math.toRadians(WALL_B_DEG)

            val legacy = PlaneExtractor.legacy(random = Random(seed)).extract(obs.xyz)
            assertTrue("seed $seed: legacy slices of wall A\n${describe(legacy)}", aligned(legacy, a, 20.0).size >= 4)
            assertTrue("seed $seed: legacy floor\n${describe(legacy)}", legacy.none { it.kind == PlaneKind.FLOOR })

            val planes = PlaneExtractor(random = Random(seed)).extract(obs)
            val wallA = aligned(planes, a, 15.0).filter { it.kind == PlaneKind.WALL }
            val wallB = aligned(planes, b, 15.0).filter { it.kind == PlaneKind.WALL }
            assertEquals("seed $seed: wall A\n${describe(planes)}", 1, wallA.size)
            assertEquals("seed $seed: wall B\n${describe(planes)}", 1, wallB.size)
            println("fixture-like seed $seed: old ${aligned(legacy, a, 20.0).size} wall-A slices, ${kinds(legacy)} -> new ${kinds(planes)}; wall A d=%.3f m (true 2.300), free tilt %.1f deg, rms free %.3f / constrained %.3f m".format(
                abs(wallA[0].d), wallA[0].freeTiltDeg, wallA[0].freeRms, wallA[0].fitRms))
            assertTrue(aligned(planes, a, 20.0).none { it !== wallA[0] && it.kind != PlaneKind.WALL && abs(it.normal.y) < 0.5f })
            // one wall at the true offset, exactly vertical
            assertEquals(2.3f, abs(wallA[0].d), 0.05f)
            assertEquals(2.17f, abs(wallB[0].d), 0.08f)
            assertEquals(0f, wallA[0].normal.y, 0f)
            assertTrue(wallA[0].gravityAligned)
        }
    }

    // ---- acceptance ----

    @Test fun boxSweepGivesFourWallsFloorAndCeiling() {
        val room = RoomScanSimulator.box()
        val seeds = 1..10
        var ok = 0
        for (seed in seeds) {
            val obs = boxCloud(seed)
            val planes = PlaneExtractor(random = Random(seed)).extract(obs)
            val exact = planes.count { it.kind == PlaneKind.WALL } == 4 &&
                planes.count { it.kind == PlaneKind.FLOOR } == 1 && planes.count { it.kind == PlaneKind.CEILING } == 1
            if (!exact) { println("seed $seed:\n${describe(planes)}"); continue }
            ok++
            val depthOnly = RoomFromPlanes.build(planes)!!
            assertEquals("seed $seed area", room.area, depthOnly.outline.area(), 0.05f * room.area)
            assertEquals("seed $seed height (depth only)", room.height, depthOnly.height, 0.10f)

            val fused = RoomFromPlanes.build(PlaneExtractor(random = Random(seed)).extract(obs, RoomScanSimulator.arFloorAndCeiling(room, Random(seed))))!!
            assertEquals("seed $seed area (ARCore)", room.area, fused.outline.area(), 0.05f * room.area)
            assertEquals("seed $seed height (ARCore)", room.height, fused.height, 0.05f)
            println("box seed %d: area %+.2f %%, height %+.1f cm depth only, %+.1f cm with ARCore (area %+.2f %%)".format(
                seed, 100 * (depthOnly.outline.area() / room.area - 1), 100 * (depthOnly.height - room.height), 100 * (fused.height - room.height), 100 * (fused.outline.area() / room.area - 1)))
        }
        assertTrue("4 walls + floor + ceiling in $ok of ${seeds.count()} seeds", ok >= 9)
    }

    @Test fun lShapedRoomGivesSixWalls() {
        val room = RoomScanSimulator.lShape()
        var ok = 0
        for (seed in 1..5) {
            val path = RoomScanSimulator.sweep(room, RoomScanSimulator.lStations(), 500, seed, dwell = 1.5)
            val obs = RoomScanSimulator().scan(room, path, seed).observations(ScanLogic.ANALYZE_MIN_HITS)
            val planes = PlaneExtractor(maxPlanes = 10, random = Random(seed)).extract(obs)
            val built = RoomFromPlanes.build(planes)
            if (planes.count { it.kind == PlaneKind.WALL } != 6 || built == null) { println("L seed $seed:\n${describe(planes)}"); continue }
            ok++
            assertEquals(6, built.outline.points.size)
            assertEquals(room.area, built.outline.area(), 0.05f * room.area)
            assertEquals(room.height, built.height, 0.10f)
            println("L seed %d: area %+.2f %%, height %+.1f cm".format(seed, 100 * (built.outline.area() / room.area - 1), 100 * (built.height - room.height)))
        }
        assertTrue("6 walls in $ok of 5 seeds", ok >= 4)
    }

    @Test fun gravityPriorRemovesTheTiltAndReportsBothResiduals() {
        val (room, path) = fixtureLike(3)
        val obs = RoomScanSimulator().scan(room, path, 3).observations(ScanLogic.ANALYZE_MIN_HITS)
        val a = Math.toRadians(WALL_A_DEG)
        val free = aligned(PlaneExtractor(gravityPrior = false, random = Random(3)).extract(obs), a, 15.0).maxBy { it.inlierCount }
        val snapped = aligned(PlaneExtractor(random = Random(3)).extract(obs), a, 15.0).single { it.kind == PlaneKind.WALL }
        assertTrue("free fit is tilted: n.y = ${free.normal.y}", abs(free.normal.y) > 1e-4f)
        assertEquals(0f, snapped.normal.y, 0f)
        assertTrue(snapped.freeTiltDeg in 0f..15f)
        // constraining costs almost nothing in residual (the tilt was noise, not the wall)
        assertTrue("free ${snapped.freeRms} vs constrained ${snapped.fitRms}", snapped.fitRms <= snapped.freeRms + 0.01f)
    }

    // ---- ARCore planes and fallbacks ----

    @Test fun arcoreFloorStandsInWhenDepthMissedTheFloor() {
        val box = RoomScanSimulator.box()
        val full = boxCloud(2)
        // drop everything below 0.6 m above the floor: the user never pointed the phone down
        val keep = (0 until full.size).filter { full.xyz[it * 3 + 1] > box.floorY + 0.6f }
        val obs = CloudObservations(
            FloatArray(keep.size * 3) { full.xyz[keep[it / 3] * 3 + it % 3] }, IntArray(keep.size) { full.hits!![keep[it]] }, FloatArray(keep.size) { full.range!![keep[it]] },
        )
        val depth = PlaneExtractor(random = Random(2)).extract(obs)
        assertTrue(describe(depth), depth.none { it.kind == PlaneKind.FLOOR })
        val missing = ScanLogic.missingPieces(depth)
        assertTrue(missing.toString(), missing.any { it.startsWith("the floor") })

        val ar = RoomScanSimulator.arFloorAndCeiling(box, Random(2)).take(1)
        val fused = PlaneExtractor(random = Random(2)).extract(obs, ar)
        val floor = fused.single { it.kind == PlaneKind.FLOOR }
        assertEquals(PlaneSource.ARCORE, floor.source)
        val room = RoomFromPlanes.build(fused)!!
        assertEquals(box.height, room.height, 0.10f)
        assertEquals(box.area, room.outline.area(), 0.05f * box.area)
    }

    @Test fun arcorePlanesAnchorFloorAndWallsAndAddAMissedFloorUnderATable() {
        val room = SyntheticRoom.box(4f, 5f, 2.5f)
        // ARCore's floor 2 cm above the depth floor, and a wall plane 1 cm off the +x wall
        val floorPrior = ArPlaneObservation(
            ArPlaneType.HORIZONTAL_UP, Vec3(3f, 0.02f, 4f), Vec3(0f, 1f, 0f),
            listOf(Vec3(2.5f, 0.02f, 3.5f), Vec3(3.5f, 0.02f, 3.5f), Vec3(3.5f, 0.02f, 4.5f), Vec3(2.5f, 0.02f, 4.5f)),
        )
        val wallPrior = ArPlaneObservation(
            ArPlaneType.VERTICAL, Vec3(4.01f, 1.2f, 2.5f), Vec3(-1f, 0f, 0f),
            listOf(Vec3(4.01f, 0.5f, 2f), Vec3(4.01f, 0.5f, 3f), Vec3(4.01f, 1.5f, 3f), Vec3(4.01f, 1.5f, 2f)),
        )
        val fused = PlaneExtractor(random = Random(42)).extract(CloudObservations(room), listOf(floorPrior, wallPrior))
        val floor = fused.single { it.kind == PlaneKind.FLOOR }
        assertEquals(PlaneSource.FUSED, floor.source)
        assertEquals(0.02f, floor.centroid.y, 1e-4f)
        val wall = fused.single { it.kind == PlaneKind.WALL && it.normal.x < -0.9f }
        assertEquals(PlaneSource.FUSED, wall.source)
        assertEquals(4.01f, -wall.d, 1e-3f)
        assertEquals(2.48f, RoomFromPlanes.build(fused)!!.height, 0.01f)

        // depth missed the floor but saw a table 0.75 m up: the table stays OTHER and ARCore's floor is used
        val table = ArrayList<Float>()
        var x = 0.5f
        while (x < 2.5f) { var z = 0.5f; while (z < 2f) { table += listOf(x, 0.75f, z); z += 0.03f }; x += 0.03f }
        val noFloor = room.filterFloorOut() + table.toFloatArray()
        val depth = PlaneExtractor(random = Random(42)).extract(noFloor)
        assertTrue(describe(depth), depth.none { it.kind == PlaneKind.FLOOR })
        assertTrue(depth.any { it.kind == PlaneKind.OTHER && abs(it.centroid.y - 0.75f) < 0.02f })
        val withAr = PlaneExtractor(random = Random(42)).extract(CloudObservations(noFloor), listOf(floorPrior))
        val f2 = withAr.single { it.kind == PlaneKind.FLOOR }
        assertEquals(PlaneSource.ARCORE, f2.source)
        assertEquals(0.02f, f2.d, 1e-4f)
        assertTrue(withAr.any { it.kind == PlaneKind.OTHER && abs(it.centroid.y - 0.75f) < 0.02f })
    }

    @Test fun floorFallbackFitsAThinlySweptFloor() {
        val room = SyntheticRoom.box(4f, 5f, 2.5f).filterFloorOut()
        val rnd = Random(9)
        val floor = FloatArray(200 * 3) { i -> when (i % 3) { 0 -> 0.2f + rnd.nextFloat() * 3.6f; 1 -> (rnd.nextFloat() - 0.5f) * 0.02f; else -> 0.2f + rnd.nextFloat() * 4.6f } }
        val planes = PlaneExtractor(random = Random(9)).extract(room + floor)
        val f = planes.single { it.kind == PlaneKind.FLOOR }
        assertEquals(PlaneSource.FALLBACK, f.source)
        assertEquals(0f, f.centroid.y, 0.02f)
        assertTrue(PlaneExtractor(floorFallback = false, random = Random(9)).extract(room + floor).none { it.kind == PlaneKind.FLOOR })
    }

    @Test fun partialRoomNamesTheMissingSidesInPlainWords() {
        // the owner's capture: two walls seen -> a partial result, not null
        val (room, path) = fixtureLike(1)
        val planes = PlaneExtractor(random = Random(1)).extract(RoomScanSimulator().scan(room, path, 1).observations(ScanLogic.ANALYZE_MIN_HITS))
        val a = RoomFromPlanes.assemble(planes)
        assertNull(a.room)
        assertTrue(a.wallCount >= 2)
        assertTrue(a.openSides.isNotEmpty())
        val analysis = ScanLogic.assess(planes) as ScanAnalysis.Incomplete
        assertTrue(analysis.partial != null)
        assertTrue(analysis.missing.toString(), analysis.missing.any { it.contains("where you started") })

        // a clean box minus the wall at +x: exactly that side is open, named "to the right"
        val box = PlaneExtractor(random = Random(42)).extract(SyntheticRoom.box(4f, 5f, 2.5f))
        val noRight = box.filterNot { it.kind == PlaneKind.WALL && it.normal.x < -0.9f }
        val p = RoomFromPlanes.assemble(noRight)
        assertEquals(listOf(RoomSide.RIGHT), p.openSides)
        assertEquals(listOf("the wall to the right of where you started"), ScanLogic.missingPieces(noRight))
        assertTrue(p.floorY != null && p.ceilingY != null && p.outline == null)
    }

    companion object {
        const val WALL_A_DEG = -25.0
        const val WALL_B_DEG = -115.0

        private fun FloatArray.filter3(pred: (FloatArray) -> Boolean): FloatArray {
            val out = ArrayList<Float>()
            for (i in 0 until size / 3) {
                val v = floatArrayOf(this[i * 3], this[i * 3 + 1], this[i * 3 + 2])
                if (pred(v)) out += v.toList()
            }
            return out.toFloatArray()
        }


        /** A synthetic box room's points without the floor (y ~ 0). */
        private fun FloatArray.filterFloorOut() = filter3 { it[1] > 0.05f }

        /**
         * The owner's 2026-10-03 capture: standing near the session origin, mostly facing wall A 2.3 m away
         * (normal yaw -25 deg), a glance at wall B 2.17 m away (yaw -115 deg); pitch -15..+29 deg, so the floor
         * and ceiling are barely seen.
         */
        fun fixtureLike(seed: Int): Pair<SimRoom, List<CamPose>> {
            val a = Math.toRadians(WALL_A_DEG); val b = Math.toRadians(WALL_B_DEG)
            val e1 = doubleArrayOf(cos(a), sin(a)); val e2 = doubleArrayOf(cos(b), sin(b))
            fun pt(s1: Double, s2: Double) = (s1 * e1[0] + s2 * e2[0]).toFloat() to (s1 * e1[1] + s2 * e2[1]).toFloat()
            val room = SimRoom(listOf(pt(-1.7, -2.83), pt(2.3, -2.83), pt(2.3, 2.17), pt(-1.7, 2.17)), -1.3f, 1.2f)
            val rnd = Random(seed * 7 + 1)
            val n = 300
            val path = List(n) { f ->
                val dir = if (f < n * 0.8) a + Math.toRadians(30.0) * sin(2 * PI * f / 60.0)
                else a + (b - a) * ((f - n * 0.8) / (n * 0.2)) * 0.85
                CamPose(
                    0.15 * sin(2 * PI * f / 150.0) + RoomScanSimulator.gauss(rnd) * 0.02, RoomScanSimulator.gauss(rnd) * 0.02, 0.1 * cos(2 * PI * f / 110.0),
                    CamPose.yawToward(cos(dir), sin(dir)), Math.toRadians(7.0 + 22.0 * sin(2 * PI * f / 45.0)), RoomScanSimulator.gauss(rnd) * 0.03,
                )
            }
            return room to path
        }
    }
}
