package com.example.arruler.depth

import com.example.arruler.geometry.Vec2
import com.example.arruler.geometry.Polygon2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PlaneExtractorTest {

    @Test fun boxRoomYieldsSixPlanesWithCorrectClasses() {
        val pts = SyntheticRoom.box(4f, 5f, 2.5f)
        val planes = PlaneExtractor(random = Random(42)).extract(pts)
        assertEquals(planes.joinToString { "${it.kind}:${it.inlierCount}" }, 6, planes.size)
        assertEquals(1, planes.count { it.kind == PlaneKind.FLOOR })
        assertEquals(1, planes.count { it.kind == PlaneKind.CEILING })
        assertEquals(4, planes.count { it.kind == PlaneKind.WALL })
        val floor = planes.first { it.kind == PlaneKind.FLOOR }
        val ceil = planes.first { it.kind == PlaneKind.CEILING }
        assertTrue(floor.normal.y > 0.99f && ceil.normal.y > 0.99f)
        val gap = ceil.distanceTo(floor.centroid)   // signed distance of the floor centroid from the ceiling plane
        assertEquals(2.5f, abs(gap), 0.01f)
        // floor hull is about the 4 x 5 footprint
        assertEquals(20f, Polygon2(floor.outline).area(), 0.5f)
        // wall normals are horizontal and point inward (toward the room centre)
        for (w in planes.filter { it.kind == PlaneKind.WALL }) {
            assertTrue(abs(w.normal.y) < 0.02f)
            assertTrue(w.distanceTo(com.example.arruler.geometry.Vec3(2f, 1.25f, 2.5f)) > 1.9f)
        }
    }

    @Test fun tableAboveFloorIsOther() {
        val room = SyntheticRoom.box(4f, 5f, 2.5f)
        val rnd = Random(3)
        val table = ArrayList<Float>()
        for (i in 0 until 4000) { table += 1f + rnd.nextFloat() * 1f; table += 0.75f + (rnd.nextFloat() - .5f) * 0.004f; table += 1f + rnd.nextFloat() * 0.8f }
        val planes = PlaneExtractor(random = Random(5)).extract(room + table.toFloatArray())
        val t = planes.filter { it.kind == PlaneKind.OTHER }
        assertEquals(1, t.size)
        assertEquals(0.75f, t[0].centroid.y, 0.01f)
        assertEquals(1, planes.count { it.kind == PlaneKind.FLOOR })
    }

    @Test fun convexHullOfSquareWithInteriorPoints() {
        val pts = listOf(Vec2(0f, 0f), Vec2(2f, 0f), Vec2(2f, 2f), Vec2(0f, 2f), Vec2(1f, 1f), Vec2(1f, 0f), Vec2(0.5f, 1.5f))
        val h = ConvexHull.of(pts)
        assertEquals(4, h.size)
        assertEquals(4f, Polygon2(h).signedArea(), 1e-5f)   // counter-clockwise
    }

    @Test fun tooFewPointsGiveNoPlanes() {
        assertTrue(PlaneExtractor().extract(FloatArray(30)).isEmpty())
    }
}
