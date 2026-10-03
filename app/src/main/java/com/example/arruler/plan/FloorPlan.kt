package com.example.arruler.plan

import com.example.arruler.geometry.Box2
import com.example.arruler.geometry.Polygon2

/** A room outline in plan coordinates (meters; x east, y north). */
data class Room(val name: String, val outline: Polygon2, val heightM: Float? = null)

/** A set of rooms sharing one plan coordinate system. */
data class FloorPlan(val rooms: List<Room>) {

    /** Bounding box of all room outlines; a zero box at the origin when there are no vertices. */
    fun bounds(): Box2 {
        val pts = rooms.flatMap { it.outline.points }
        if (pts.isEmpty()) return Box2(0f, 0f, 0f, 0f)
        return Box2(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    /** Sum of the room areas in square meters. */
    fun totalArea(): Float =
        rooms.sumOf { if (it.outline.points.size >= 3) it.outline.area().toDouble() else 0.0 }.toFloat()
}
