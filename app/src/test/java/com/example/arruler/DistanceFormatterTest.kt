package com.example.arruler

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.math.RoundingMode
import java.util.Locale

class DistanceFormatterTest {
    private val formatter = DistanceFormatter()

    @Test
    fun format_standardValues() {
        assertEquals("1.2 cm", formatter.format(1.23f, "cm"))
        assertEquals("10.0 m", formatter.format(10.0f, "m"))
        assertEquals("0.5 in", formatter.format(0.5f, "in"))
    }

    @Test
    fun format_roundingEdgeCases() {
        // Round half up for positive numbers
        assertEquals("1.3 cm", formatter.format(1.25f, "cm"))
        assertEquals("1.2 cm", formatter.format(1.24f, "cm"))

        // Check ties
        assertEquals("1.5 cm", formatter.format(1.45f, "cm"))
    }

    @Test
    fun format_zero() {
        assertEquals("0.0 cm", formatter.format(0.0f, "cm"))
        assertEquals("0.0 cm", formatter.format(0.001f, "cm"))
    }

    @Test
    fun format_largeValues() {
        assertEquals("1000.1 m", formatter.format(1000.12f, "m"))
        assertEquals("9999.9 m", formatter.format(9999.94f, "m"))
    }

    @Test
    fun format_negativeValues() {
        assertEquals("-1.5 cm", formatter.format(-1.5f, "cm"))
        assertEquals("-0.5 cm", formatter.format(-0.5f, "cm"))
        assertEquals("-0.1 cm", formatter.format(-0.1f, "cm"))
    }

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
                // String.format uses HALF_UP by default for most locales, but check explicitly
                // Actually DecimalFormat with HALF_UP should match our manual implementation
                // Our manual implementation does round(value * 10) / 10 which is effectively HALF_UP

                // Let's compare against what we expect from DecimalFormat
                val expected = "${decimalFormat.format(value)} $unit"
                val actual = formatter.format(value, unit)
                assertEquals("Mismatch for value $value unit $unit", expected, actual)
            }
        }
    }
}
