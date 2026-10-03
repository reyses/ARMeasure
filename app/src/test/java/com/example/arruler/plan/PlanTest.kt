package com.example.arruler.plan

import com.example.arruler.geometry.Polygon2
import com.example.arruler.geometry.Vec2
import com.example.arruler.measure.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {
    private fun rect(w: Float, h: Float) =
        Polygon2(listOf(Vec2(0f, 0f), Vec2(w, 0f), Vec2(w, h), Vec2(0f, h)))

    private val room34 = FloorPlan(listOf(Room("Office", rect(3f, 4f))))
    private val lRoom = FloorPlan(
        listOf(
            Room(
                "L",
                Polygon2(listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4f, 2f), Vec2(2f, 2f), Vec2(2f, 4f), Vec2(0f, 4f)))
            )
        )
    )

    @Test
    fun fitCentersAndFlipsY() {
        val t = fit(room34, 300f, 300f, 20f)
        assertEquals(65f, t.scale, 1e-4f)
        val sw = t.toPx(Vec2(0f, 0f))
        val ne = t.toPx(Vec2(3f, 4f))
        assertEquals(52.5f, sw.x, 1e-3f); assertEquals(280f, sw.y, 1e-3f)
        assertEquals(247.5f, ne.x, 1e-3f); assertEquals(20f, ne.y, 1e-3f)
        val back = t.toMeters(ne)
        assertEquals(3f, back.x, 1e-4f); assertEquals(4f, back.y, 1e-4f)
    }

    @Test
    fun areaAndBounds() {
        assertEquals(12f, room34.totalArea(), 1e-4f)
        assertEquals(4f, room34.bounds().height, 1e-6f)
    }

    @Test
    fun niceScaleBarPicksOneMeterForFourMeterView() {
        val plan = FloorPlan(listOf(Room("w", rect(4f, 1f))))
        val t = fit(plan, 300f, 300f, 20f) // 65 px/m
        val bar = niceScaleBar(t, 100f)
        assertEquals(1f, bar.meters, 0f)
        assertEquals(65f, bar.px, 1e-3f)
    }

    @Test
    fun svgHasWallLabelsAndPolygon() {
        val svg = toSvg(room34, Units.M)
        val walls = Regex("<text class=\"wall\"[^>]*>([^<]*)</text>").findAll(svg).map { it.groupValues[1] }.toList()
        assertEquals(4, walls.size)
        assertEquals(2, walls.count { it == "3.00 m" })
        assertEquals(2, walls.count { it == "4.00 m" })
        val poly = Regex("<polygon points=\"([^\"]*)\"").find(svg)!!.groupValues[1]
        assertEquals(4, poly.trim().split(" ").size)
        assertTrue(svg.contains("Office"))
    }

    @Test
    fun lRoomHasSixOutsideWallLabels() {
        val labels = PlanLabels.compute(lRoom, Units.M).filter { it.kind == LabelKind.WALL }
        assertEquals(6, labels.size)
        val poly = lRoom.rooms[0].outline
        labels.forEach { assertFalse("label ${it.text} at ${it.pos}", poly.containsPoint(it.pos)) }
        labels.forEach { assertTrue(it.angleDeg > -90f && it.angleDeg <= 90f) }
    }

    @Test
    fun roomLabelInsideConcaveRoom() {
        val l = PlanLabels.compute(lRoom, Units.M).first { it.kind == LabelKind.ROOM_NAME }
        assertTrue(lRoom.rooms[0].outline.containsPoint(Vec2(l.pos.x, l.pos.y - 0.15f)))
    }

    @Test
    fun dxfLineCountMatchesWalls() {
        val dxf = toDxf(lRoom)
        assertEquals(6, dxf.lines().count { it == "LINE" })
        assertEquals(4, toDxf(room34).lines().count { it == "LINE" })
        assertTrue(dxf.trimEnd().endsWith("EOF"))
    }
}
