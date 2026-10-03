package com.example.arruler.measure.shapes

import com.example.arruler.measure.Units
import java.util.Locale

/** Text helpers for the SHAPES result card (kept out of Units.kt on purpose). */
object ShapeFormat {
    private fun num(v: Float): String = String.format(Locale.US, "%.2f", v)

    fun length(units: Units, meters: Float): String = num(units.fromMeters(meters)) + " " + units.symbol
    fun area(units: Units, m2: Float): String = num(units.areaFromSquareMeters(m2)) + " " + units.areaSymbol
    fun volume(units: Units, m3: Float): String = num(units.volumeFromCubicMeters(m3)) + " " + units.volumeSymbol

    fun dim(units: Units, d: Dim): String = when (d.kind) {
        DimKind.LENGTH -> length(units, d.value)
        DimKind.AREA -> area(units, d.value)
    }

    /** Lines for the card: "Name: value" per dimension, then volume (with range for PILE) and surface area. */
    fun lines(units: Units, r: ShapeResult): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (d in r.dims) out += d.name to dim(units, d)
        val lo = r.volumeLow; val hi = r.volumeHigh
        out += if (lo != null && hi != null) {
            "Volume (est.)" to volume(units, r.volume) + "  [" + num(units.volumeFromCubicMeters(lo)) + " - " +
                num(units.volumeFromCubicMeters(hi)) + "]"
        } else "Volume" to volume(units, r.volume)
        r.surfaceArea?.let { out += "Surface area" to area(units, it) }
        return out
    }
}


