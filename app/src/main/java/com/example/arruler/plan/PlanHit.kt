package com.example.arruler.plan

import com.example.arruler.geometry.Vec2

/** Index of the room containing [p] (plan meters); the smallest one when rooms overlap, else null. */
fun FloorPlan.hitRoom(p: Vec2): Int? =
    rooms.withIndex()
        .filter { it.value.outline.points.size >= 3 && it.value.outline.containsPoint(p) }
        .minByOrNull { it.value.outline.area() }
        ?.index
