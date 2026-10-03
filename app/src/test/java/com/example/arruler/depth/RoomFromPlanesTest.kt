package com.example.arruler.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoomFromPlanesTest {

    @Test fun boxRoomAreaAndHeight() {
        val planes = PlaneExtractor(random = Random(42)).extract(SyntheticRoom.box(4f, 5f, 2.5f))
        val room = RoomFromPlanes.build(planes)
        assertNotNull(room)
        room!!
        assertEquals(4, room.wallCount)
        assertEquals(20f, room.outline.area(), 0.1f)
        assertEquals(2.5f, room.height, 0.02f)
        assertEquals(0f, room.floorY, 0.01f)
        assertTrue("counter-clockwise", room.outline.signedArea() > 0f)
        assertEquals(4, room.outline.points.size)
    }

    @Test fun lShapedRoomHasSixWalls() {
        val l = listOf(0f to 0f, 4f to 0f, 4f to 2f, 2f to 2f, 2f to 4f, 0f to 4f)
        val pts = SyntheticRoom.sample(l, 2.5f)
        val planes = PlaneExtractor(maxPlanes = 10, random = Random(11)).extract(pts)
        assertEquals(planes.joinToString { "${it.kind}:${it.inlierCount}" }, 6, planes.count { it.kind == PlaneKind.WALL })
        val room = RoomFromPlanes.build(planes)!!
        assertEquals(6, room.outline.points.size)
        assertEquals(12f, room.outline.area(), 0.1f)
        assertEquals(2.5f, room.height, 0.02f)
    }

    @Test fun missingCeilingOrWallsGivesNull() {
        val planes = PlaneExtractor(random = Random(42)).extract(SyntheticRoom.box(4f, 5f, 2.5f))
        assertEquals(null, RoomFromPlanes.build(planes.filter { it.kind != PlaneKind.CEILING }))
        assertEquals(null, RoomFromPlanes.build(planes.filter { it.kind != PlaneKind.WALL }))
    }

    @Test fun roomIsRotationAndOffsetInvariant() {
        // same 4x5 room, yawed 30 degrees and moved
        val base = SyntheticRoom.box(4f, 5f, 2.5f)
        val m = DepthMath.yawMatrix(Math.toRadians(30.0).toFloat(), 3f, 0f, -2f)
        val pts = FloatArray(base.size)
        for (i in 0 until base.size / 3) DepthMath.transformPoint(m, base[i * 3], base[i * 3 + 1], base[i * 3 + 2], pts, i * 3)
        val room = RoomFromPlanes.build(PlaneExtractor(random = Random(9)).extract(pts))!!
        assertEquals(20f, room.outline.area(), 0.1f)
        assertEquals(2.5f, room.height, 0.02f)
    }
}
