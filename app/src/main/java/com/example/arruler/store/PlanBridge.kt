package com.example.arruler.store

import com.example.arruler.geometry.Polygon2
import com.example.arruler.geometry.Vec2
import com.example.arruler.plan.FloorPlan
import com.example.arruler.plan.Room

fun SavedRoom.toRoom(): Room = Room(name, Polygon2(outline.map { Vec2(it.x, it.y) }), heightM)

/** The project's rooms as a [FloorPlan]; room index i is project.rooms[i]. */
fun Project.toFloorPlan(): FloorPlan = FloorPlan(rooms.map { it.toRoom() })
