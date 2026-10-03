package com.example.arruler.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HitRankingTest {

    private fun c(q: HitQuality, d: Float, k: SurfaceKind = SurfaceKind.WALL) = HitCandidate(q, d, k)

    @Test
    fun emptyListHasNoBest() {
        assertEquals(-1, HitRanking.best(emptyList()))
    }

    @Test
    fun bestQualityWinsOverNearerWorseHit() {
        val list = listOf(c(HitQuality.POINT, 0.5f), c(HitQuality.DEPTH, 1.0f), c(HitQuality.PLANE, 4.0f))
        assertEquals(2, HitRanking.best(list))
    }

    @Test
    fun qualityOrderIsPlaneExtendedDepthPoint() {
        val list = listOf(
            c(HitQuality.POINT, 1f), c(HitQuality.DEPTH, 1f), c(HitQuality.PLANE_EXTENDED, 1f), c(HitQuality.PLANE, 1f),
        )
        assertEquals(3, HitRanking.best(list))
        assertEquals(2, HitRanking.best(list.take(3)))
        assertEquals(1, HitRanking.best(list.take(2)))
    }

    @Test
    fun tiesGoToTheNearest() {
        val list = listOf(c(HitQuality.DEPTH, 2.0f), c(HitQuality.DEPTH, 0.8f), c(HitQuality.DEPTH, 1.4f))
        assertEquals(1, HitRanking.best(list))
    }

    @Test
    fun firstOfEqualCandidatesWins() {
        val list = listOf(c(HitQuality.PLANE, 1f), c(HitQuality.PLANE, 1f))
        assertEquals(0, HitRanking.best(list))
    }

    @Test
    fun kindFromNormal() {
        assertEquals(SurfaceKind.FLOOR, HitRanking.kindFromNormalY(1f))
        assertEquals(SurfaceKind.FLOOR, HitRanking.kindFromNormalY(0.8f))
        assertEquals(SurfaceKind.CEILING, HitRanking.kindFromNormalY(-1f))
        assertEquals(SurfaceKind.CEILING, HitRanking.kindFromNormalY(-0.8f))
        assertEquals(SurfaceKind.WALL, HitRanking.kindFromNormalY(0f))
        assertEquals(SurfaceKind.WALL, HitRanking.kindFromNormalY(0.3f))
        assertEquals(SurfaceKind.WALL, HitRanking.kindFromNormalY(-0.3f))
        assertEquals(SurfaceKind.OTHER, HitRanking.kindFromNormalY(0.5f))
        assertEquals(SurfaceKind.OTHER, HitRanking.kindFromNormalY(-0.6f))
    }

    @Test
    fun planeQualityRules() {
        assertEquals(HitQuality.PLANE, HitRanking.planeQuality(true, 0f, true))
        assertEquals(HitQuality.PLANE, HitRanking.planeQuality(true, 0f, false))
        assertEquals(HitQuality.PLANE_EXTENDED, HitRanking.planeQuality(false, 1.0f, true))
        assertEquals(HitQuality.PLANE_EXTENDED, HitRanking.planeQuality(false, 1.5f, true))
        assertNull(HitRanking.planeQuality(false, 1.6f, true))
        assertNull(HitRanking.planeQuality(false, 0.5f, false))
    }

    @Test
    fun lowConfidenceIsDepthAndPointOnly() {
        assertFalse(HitRanking.isLowConfidence(HitQuality.PLANE))
        assertFalse(HitRanking.isLowConfidence(HitQuality.PLANE_EXTENDED))
        assertTrue(HitRanking.isLowConfidence(HitQuality.DEPTH))
        assertTrue(HitRanking.isLowConfidence(HitQuality.POINT))
    }

    @Test
    fun polygonDistanceInsideAndOutside() {
        // 2 m x 1 m rectangle, x in 0..2, z in 0..1
        val rect = floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 0f, 1f)
        assertEquals(0f, HitRanking.polygonDistance(rect, 1f, 0.5f), 1e-6f)
        assertEquals(1f, HitRanking.polygonDistance(rect, 3f, 0.5f), 1e-5f)
        assertEquals(0.5f, HitRanking.polygonDistance(rect, 1f, 1.5f), 1e-5f)
        // corner: sqrt(2) away diagonally from (2,1)
        assertEquals(Math.sqrt(2.0).toFloat(), HitRanking.polygonDistance(rect, 3f, 2f), 1e-5f)
    }

    @Test
    fun labels() {
        assertEquals("Wall · depth", HitRanking.label(HitInfo(HitQuality.DEPTH, SurfaceKind.WALL)))
        assertEquals("Floor · plane", HitRanking.label(HitInfo(HitQuality.PLANE, SurfaceKind.FLOOR)))
        assertEquals("Ceiling · points", HitRanking.label(HitInfo(HitQuality.POINT, SurfaceKind.CEILING)))
    }
}
