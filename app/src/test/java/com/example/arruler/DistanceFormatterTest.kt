package com.example.arruler

import org.junit.Test
import org.junit.Assert.*

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
}
