package com.example.arruler.depth

import com.example.arruler.store.RoomCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanControllerTest {

    @Test fun samplesEveryThirdFrameOnly() {
        val sampled = (1L..9L).filter { ScanLogic.shouldSample(it, 3, scanning = true, inFlight = false) }
        assertEquals(listOf(3L, 6L, 9L), sampled)
    }

    @Test fun neverSamplesWhilePausedOrInFlight() {
        assertFalse(ScanLogic.shouldSample(3, 3, scanning = false, inFlight = false))
        assertFalse(ScanLogic.shouldSample(3, 3, scanning = true, inFlight = true))
    }

    @Test fun previewRefreshesAtMostTwicePerSecond() {
        assertFalse(ScanLogic.shouldRefreshPreview(1_400, 1_000, 500, inFlight = false))
        assertTrue(ScanLogic.shouldRefreshPreview(1_500, 1_000, 500, inFlight = false))
        assertFalse(ScanLogic.shouldRefreshPreview(2_000, 1_000, 500, inFlight = true))
    }

    @Test fun thinKeepsAtMostMaxEvenlySpaced() {
        val packed = FloatArray(3000) { it.toFloat() } // 1000 points, x = 3i
        val out = ScanLogic.thin(packed, 100)
        assertEquals(100, out.size)
        assertEquals(0f, out.first().x, 0f)
        assertTrue(out.last().x > 2900f)
        assertEquals(5, ScanLogic.thin(FloatArray(15), 100).size)
        assertTrue(ScanLogic.thin(FloatArray(0), 100).isEmpty())
    }

    @Test fun missingPiecesNamesWhatIsAbsent() {
        val planes = com.example.arruler.depth.PlaneExtractor(random = kotlin.random.Random(42))
            .extract(SyntheticRoom.box(4f, 5f, 2.5f))
        assertTrue(ScanLogic.missingPieces(planes).isEmpty())
        val noCeil = ScanLogic.missingPieces(planes.filter { it.kind != PlaneKind.CEILING })
        assertEquals(1, noCeil.size); assertTrue(noCeil[0].contains("ceiling"))
        val noFloor = ScanLogic.missingPieces(planes.filter { it.kind != PlaneKind.FLOOR })
        assertTrue(noFloor[0].contains("floor"))
        val twoWalls = planes.filter { it.kind != PlaneKind.WALL } + planes.filter { it.kind == PlaneKind.WALL }.take(2)
        val sides = ScanLogic.missingPieces(twoWalls)
        assertTrue(sides.toString(), sides.isNotEmpty() && sides.all { it.startsWith("the wall ") && it.endsWith("where you started") })
        assertEquals(3, ScanLogic.missingPieces(emptyList()).size)
    }

    @Test fun analyzeReturnsRoomOrIncomplete() {
        val room = ScanLogic.analyze(SyntheticRoom.box(4f, 5f, 2.5f)) as ScanAnalysis.Room
        assertEquals(20f, room.areaM2, 0.1f)
        assertEquals(18f, room.perimeterM, 0.1f)
        assertEquals(2.5f, room.heightM, 0.02f)
        assertEquals(50f, room.volumeM3, 0.5f)
        assertEquals(4, room.model.wallCount)
        val inc = ScanLogic.analyze(FloatArray(0)) as ScanAnalysis.Incomplete
        assertEquals(3, inc.missing.size)
    }

    @Test fun roomModelConvertsToSaveInput() {
        val room = (ScanLogic.analyze(SyntheticRoom.box(4f, 5f, 2.5f)) as ScanAnalysis.Room).model
        val pts = room.floorPolygon3d()
        assertEquals(room.outline.points.size, pts.size)
        assertTrue(pts.all { it.y == room.floorY })
        val captured = RoomCapture.capture(pts, null)
        assertEquals(20f, captured.areaM2(snap = false), 0.1f)
        val saved = captured.toSavedRoom("r1", "Scan", snap = false, heightM = room.height, capturedAt = 0L)
        assertEquals(20f, saved.areaM2, 0.1f)
        assertEquals(18f, saved.perimeterM, 0.1f)
        assertEquals(50f, saved.volumeM3!!, 0.5f)
    }
}
