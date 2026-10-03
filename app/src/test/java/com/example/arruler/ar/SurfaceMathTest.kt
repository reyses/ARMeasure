package com.example.arruler.ar

import com.example.arruler.measure.MeasurePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceMathTest {

    @Test
    fun alphaGrowsWithAreaAndIsClamped() {
        assertEquals(0.15f, SurfaceMath.alphaFromArea(0f), 1e-6f)
        assertEquals(0.15f, SurfaceMath.alphaFromArea(-3f), 1e-6f)
        assertEquals(0.30f, SurfaceMath.alphaFromArea(3f), 1e-6f)
        assertEquals(0.45f, SurfaceMath.alphaFromArea(6f), 1e-6f)
        assertEquals(0.45f, SurfaceMath.alphaFromArea(50f), 1e-6f)
        assertTrue(SurfaceMath.alphaFromArea(1f) < SurfaceMath.alphaFromArea(2f))
    }

    @Test
    fun alphaBucketsRoundTrip() {
        for (b in 0 until SurfaceMath.ALPHA_BUCKETS) {
            assertEquals(b, SurfaceMath.alphaBucket(SurfaceMath.bucketAlpha(b)))
        }
        assertEquals(0, SurfaceMath.alphaBucket(0.10f))
        assertEquals(SurfaceMath.ALPHA_BUCKETS - 1, SurfaceMath.alphaBucket(0.9f))
    }

    private fun wall(): List<MeasurePoint> = listOf( // 3 m x 2 m wall in the x-y plane at z = 1, CCW seen from +z
        MeasurePoint(0f, 0f, 1f), MeasurePoint(3f, 0f, 1f), MeasurePoint(3f, 2f, 1f), MeasurePoint(0f, 2f, 1f),
    )

    @Test
    fun areaOfAWall() {
        assertEquals(6f, SurfaceMath.area(wall()), 1e-5f)
        assertEquals(6f, SurfaceMath.area(wall().reversed()), 1e-5f)
    }

    @Test
    fun frameIsCounterClockwiseAndKeepsArea() {
        for (poly in listOf(wall(), wall().reversed(), floor(), ceiling())) {
            val f = SurfaceMath.frameOf(poly)
            assertNotNull(f)
            assertEquals(SurfaceMath.area(poly), SurfaceMath.signedArea2d(f!!.local), 1e-4f)
        }
    }

    private fun floor() = listOf(
        MeasurePoint(0f, 0f, 0f), MeasurePoint(2f, 0f, 0f), MeasurePoint(2f, 0f, 3f), MeasurePoint(0f, 0f, 3f),
    )

    private fun ceiling() = listOf(
        MeasurePoint(0f, 2.5f, 0f), MeasurePoint(0f, 2.5f, 3f), MeasurePoint(2f, 2.5f, 3f), MeasurePoint(2f, 2.5f, 0f),
    )

    @Test
    fun frameCentroidAndLocalVerticesReconstructTheWorldPolygon() {
        val poly = wall()
        val f = SurfaceMath.frameOf(poly)!!
        assertEquals(1.5f, f.centroid.x, 1e-5f)
        assertEquals(1f, f.centroid.y, 1e-5f)
        assertEquals(1f, f.centroid.z, 1e-5f)
        poly.forEachIndexed { i, p ->
            val (lx, ly) = f.local[i]
            val w = SurfaceMath.rotate(f.rotation, lx, ly, 0f)
            assertEquals(p.x, f.centroid.x + w[0], 1e-4f)
            assertEquals(p.y, f.centroid.y + w[1], 1e-4f)
            assertEquals(p.z, f.centroid.z + w[2], 1e-4f)
        }
    }

    @Test
    fun rotationTakesZOntoTheNormal() {
        val dirs = listOf(
            Triple(0f, 0f, 1f), Triple(0f, 1f, 0f), Triple(0f, -1f, 0f), Triple(1f, 0f, 0f),
            Triple(0f, 0f, -1f), Triple(0.6f, 0.0f, -0.8f), Triple(0.577350f, 0.577350f, 0.577350f),
        )
        for ((dx, dy, dz) in dirs) {
            val r = SurfaceMath.rotate(SurfaceMath.rotationFromZTo(dx, dy, dz), 0f, 0f, 1f)
            assertEquals(dx, r[0], 1e-4f)
            assertEquals(dy, r[1], 1e-4f)
            assertEquals(dz, r[2], 1e-4f)
        }
    }

    @Test
    fun degenerateInputGivesNoFrame() {
        assertNull(SurfaceMath.frameOf(emptyList()))
        assertNull(SurfaceMath.frameOf(wall().take(2)))
        val line = listOf(MeasurePoint(0f, 0f, 0f), MeasurePoint(1f, 0f, 0f), MeasurePoint(2f, 0f, 0f))
        assertNull(SurfaceMath.frameOf(line))
    }

    @Test
    fun fanTrianglesCoverTheConvexPolygon() {
        assertTrue(SurfaceMath.fanTriangles(2).isEmpty())
        val tris = SurfaceMath.fanTriangles(5)
        assertEquals(5, tris.size)
        tris.forEach { assertEquals(5, it[0]) }
        assertEquals(listOf(0, 1), tris[0].drop(1))
        assertEquals(listOf(4, 0), tris[4].drop(1))
        // summed fan triangle area (centroid, v_i, v_i+1) equals the polygon area
        val f = SurfaceMath.frameOf(wall())!!
        val pts = f.local + (0f to 0f)
        var area = 0f
        for (t in SurfaceMath.fanTriangles(4)) {
            val (ax, ay) = pts[t[0]]; val (bx, by) = pts[t[1]]; val (cx, cy) = pts[t[2]]
            area += 0.5f * ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay))
        }
        assertEquals(6f, area, 1e-4f)
    }

    @Test
    fun confidenceColourRampsRedToGreen() {
        assertEquals(0, SurfaceMath.confidenceColor(255, 0))
        val low = SurfaceMath.confidenceColor(0, 1500)
        val mid = SurfaceMath.confidenceColor(128, 1500)
        val high = SurfaceMath.confidenceColor(255, 1500)
        fun r(c: Int) = (c shr 16) and 0xFF
        fun g(c: Int) = (c shr 8) and 0xFF
        fun a(c: Int) = (c ushr 24) and 0xFF
        assertEquals(255, r(low)); assertEquals(0, g(low))
        assertTrue(r(mid) >= 250); assertTrue(g(mid) >= 250)
        assertEquals(0, r(high)); assertEquals(255, g(high))
        assertEquals(102, a(low)) // about 40 % alpha
        assertTrue(r(low) > r(high) && g(high) > g(low))
    }
}
