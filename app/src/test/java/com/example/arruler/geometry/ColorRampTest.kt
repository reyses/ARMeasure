package com.example.arruler.geometry

import com.example.arruler.ar.SurfaceMath
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorRampTest {
    @Test fun anchors() {
        assertEquals(0xFF0000, ColorRamp.rgb(0f))
        assertEquals(0xFFFF00, ColorRamp.rgb(0.5f))
        assertEquals(0x00FF00, ColorRamp.rgb(1f))
        assertEquals(0xFF0000, ColorRamp.rgb(-3f))
        assertEquals(0x00FF00, ColorRamp.rgb(7f))
    }

    @Test fun depthHeatmapUsesTheSameRamp() {
        for (c in listOf(0, 1, 64, 127, 128, 200, 255)) {
            val expected = ColorRamp.argb(c / 255f, 102)
            assertEquals("confidence $c", expected, SurfaceMath.confidenceColor(c, 1500))
        }
    }
}
