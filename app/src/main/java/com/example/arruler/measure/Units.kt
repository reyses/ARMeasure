package com.example.arruler.measure

enum class Units(val symbol: String, private val perMeter: Float) {
    CM("cm", 100f),
    INCH("in", 39.3701f),
    M("m", 1f),
    FT("ft", 3.28084f);

    fun fromMeters(meters: Float): Float = meters * perMeter

    fun toMeters(value: Float): Float = value / perMeter

    /** Next unit in the toggle cycle CM -> INCH -> M -> FT -> CM. */
    fun next(): Units = entries[(ordinal + 1) % entries.size]
}
