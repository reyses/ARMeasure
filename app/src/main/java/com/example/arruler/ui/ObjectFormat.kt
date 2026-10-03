package com.example.arruler.ui

import com.example.arruler.measure.Units
import com.example.arruler.nav.Screen
import com.example.arruler.processing.ObjectCardText
import com.example.arruler.scan3d.ObjectSummary
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Text of the saved-object cards and detail page, in the user's units (pure, JVM-tested). */
object ObjectFormat {
    private fun n1(v: Float) = String.format(Locale.US, "%.1f", v)

    /** 'L x W x H cm': footprint length, width, then height. */
    fun dims(units: Units, s: ObjectSummary): String =
        "${n1(units.fromMeters(s.lengthM))} x ${n1(units.fromMeters(s.widthM))} x ${n1(units.fromMeters(s.heightM))} ${units.symbol}"

    fun footprint(units: Units, s: ObjectSummary): String =
        "${n1(units.fromMeters(s.lengthM))} x ${n1(units.fromMeters(s.widthM))} ${units.symbol}"

    fun height(units: Units, s: ObjectSummary): String = "${n1(units.fromMeters(s.heightM))} ${units.symbol}"

    fun volume(units: Units, m3: Float): String =
        ObjectCardText.volumeNumber(units.volumeFromCubicMeters(m3)) + " " + units.volumeSymbol

    /** '0.0078 to 0.0083 m³', or null when the range collapses to one number. */
    fun volumeRange(units: Units, s: ObjectSummary): String? {
        val lo = ObjectCardText.volumeNumber(units.volumeFromCubicMeters(s.volumeLowM3))
        val hi = ObjectCardText.volumeNumber(units.volumeFromCubicMeters(s.volumeHighM3))
        return if (lo == hi) null else "$lo to $hi ${units.volumeSymbol}"
    }

    fun date(ms: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val f = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.US)
        f.timeZone = tz
        return f.format(Date(ms))
    }
}

/** Default names of new saves: 'Object 3' is the first 'Object n' not taken yet, counting from the number of existing ones. */
object NameDefaults {
    fun next(prefix: String, existing: List<String>): String {
        var n = existing.size + 1
        while (existing.any { it.equals("$prefix $n", ignoreCase = true) }) n++
        return "$prefix $n"
    }
}

/** When the screen must stay on: the Measure (AR) screen, and while a processing job's progress card shows. */
object KeepAwake {
    fun shouldKeepOn(screen: Screen, jobRunning: Boolean): Boolean = screen == Screen.Measure || jobRunning
}
