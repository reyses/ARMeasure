package com.example.arruler.ui

import com.example.arruler.measure.Units
import com.example.arruler.nav.Screen
import com.example.arruler.scan3d.ObjectSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectFormatTest {
    private val s = ObjectSummary(0.5f, 0.4f, 0.9f, 0.08f, 0.1f, 0.09f)

    @Test fun cardTextInCurrentUnits() {
        assertEquals("50.0 x 40.0 x 90.0 cm", ObjectFormat.dims(Units.CM, s))
        assertEquals("0.5 x 0.4 x 0.9 m", ObjectFormat.dims(Units.M, s))
        assertEquals("19.7 x 15.7 x 35.4 in", ObjectFormat.dims(Units.INCH, s))
        assertEquals("0.0900 m³", ObjectFormat.volume(Units.CM, 0.09f))
        assertTrue(ObjectFormat.volume(Units.FT, 0.09f).endsWith("ft³"))
        assertEquals("50.0 x 40.0 cm", ObjectFormat.footprint(Units.CM, s))
        assertEquals("90.0 cm", ObjectFormat.height(Units.CM, s))
    }

    @Test fun volumeRangeCollapsesWhenEqual() {
        assertEquals("0.0800 to 0.100 m³", ObjectFormat.volumeRange(Units.CM, s))
        assertNull(ObjectFormat.volumeRange(Units.CM, s.copy(volumeLowM3 = 0.09f, volumeHighM3 = 0.09f)))
    }

    @Test fun defaultObjectNamesCountFromTheExistingOnes() {
        assertEquals("Object 1", NameDefaults.next("Object", emptyList()))
        assertEquals("Object 3", NameDefaults.next("Object", listOf("Object 1", "Chair")))
        assertEquals("Object 3", NameDefaults.next("Object", listOf("Object 1", "Object 2")))
        // a gap or a rename must not produce a duplicate
        assertEquals("Object 4", NameDefaults.next("Object", listOf("Object 3", "chair", "Object 2")))
        assertEquals("Object 3", NameDefaults.next("Object", listOf("object 2", "Chair")))
    }

    @Test fun theScreenStaysOnOnlyWhereItMatters() {
        assertTrue(KeepAwake.shouldKeepOn(Screen.Measure, false))
        assertFalse(KeepAwake.shouldKeepOn(Screen.Projects, false))
        assertFalse(KeepAwake.shouldKeepOn(Screen.Settings, false))
        assertFalse(KeepAwake.shouldKeepOn(Screen.Plan("p"), false))
        assertFalse(KeepAwake.shouldKeepOn(Screen.Objects, false))
        assertFalse(KeepAwake.shouldKeepOn(Screen.ObjectDetail("o"), false))
        assertTrue(KeepAwake.shouldKeepOn(Screen.Projects, true))   // a PC job's progress card
    }
}
