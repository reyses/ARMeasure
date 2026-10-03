package com.example.arruler.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MlLabelTest {
    private fun det(label: String?, c: Float) = Detection(0f, 0f, 10f, 10f, label, c)

    @Test fun labelReadsClassAndPercent() {
        assertEquals("Home good · 82 %", mlLabelText(det("Home good", 0.82f)))
        assertEquals("Food · 5 %", mlLabelText(det("Food", 0.05f)))
        assertEquals("Place · 100 %", mlLabelText(det("Place", 1f)))
    }

    @Test fun noLabelNoLine() {
        assertNull(mlLabelText(det(null, 0f)))
        assertNull(mlLabelText(det("  ", 0.9f)))
    }
}
