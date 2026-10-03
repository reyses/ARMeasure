package com.example.arruler.ui

import com.example.arruler.measure.Units
import com.example.arruler.plan.PlanLabels
import com.example.arruler.store.Project
import com.example.arruler.store.SavedRoom
import java.util.Locale

/** Area text in the unit setting: m2 for metric units, ft2 for imperial (same as the AR read-out). */
fun areaLabel(m2: Float, units: Units): String =
    String.format(Locale.US, "%.2f %s", units.areaFromSquareMeters(m2), units.areaSymbol)

fun volumeLabel(m3: Float, units: Units): String =
    String.format(Locale.US, "%.2f %s", units.volumeFromCubicMeters(m3), units.volumeSymbol)

fun projectSummary(p: Project, units: Units): String {
    val n = p.rooms.size
    val rooms = if (n == 1) "1 room" else "$n rooms"
    return if (n == 0) rooms else rooms + " · " + areaLabel(p.totalAreaM2, units)
}

/** Multi-line detail text for the selected-room card. */
fun roomDetails(r: SavedRoom, units: Units): List<String> = buildList {
    add("Area  " + areaLabel(r.areaM2, units))
    add("Perimeter  " + PlanLabels.formatLength(r.perimeterM, units))
    r.heightM?.let { add("Height  " + PlanLabels.formatLength(it, units)) }
    r.volumeM3?.let { add("Volume  " + volumeLabel(it, units)) }
}
