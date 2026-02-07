package com.example.arruler

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.math.RoundingMode
import java.util.Locale

class DistanceFormatterTest {

    @Test
    fun testFormattingConsistency() {
        val testValues = listOf(
            0.0f,
            0.1f,
            0.5f,
            0.9f,
            1.0f,
            1.25f, // Important for rounding check (should become 1.3)
            1.35f, // Important for rounding check (should become 1.4)
            123.456f,
            999.99f
        )

        val units = listOf("cm", "in", "m", "ft")

        val decimalFormat = DecimalFormat("0.0", DecimalFormatSymbols(Locale.US))
        decimalFormat.roundingMode = RoundingMode.HALF_UP

        for (value in testValues) {
            for (unit in units) {
                val expected = String.format(Locale.US, "%.1f %s", value, unit)
                val actual = "${decimalFormat.format(value)} $unit"
                assertEquals("Mismatch for value $value unit $unit", expected, actual)
            }
        }
    }
}
