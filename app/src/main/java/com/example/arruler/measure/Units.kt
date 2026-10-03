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

    /** Imperial units (in, ft) show areas/volumes in ft2/ft3; metric ones in m2/m3. */
    val imperial: Boolean get() = this == INCH || this == FT

    val areaSymbol: String get() = if (imperial) "ft²" else "m²"
    val volumeSymbol: String get() = if (imperial) "ft³" else "m³"

    fun areaFromSquareMeters(m2: Float): Float = if (imperial) m2ToFt2(m2) else m2
    fun volumeFromCubicMeters(m3: Float): Float = if (imperial) m3ToFt3(m3) else m3

    companion object {
        const val FT2_PER_M2 = 10.7639f
        const val FT3_PER_M3 = 35.3147f

        fun m2ToFt2(m2: Float): Float = m2 * FT2_PER_M2
        fun ft2ToM2(ft2: Float): Float = ft2 / FT2_PER_M2
        fun m3ToFt3(m3: Float): Float = m3 * FT3_PER_M3
        fun ft3ToM3(ft3: Float): Float = ft3 / FT3_PER_M3
    }
}
