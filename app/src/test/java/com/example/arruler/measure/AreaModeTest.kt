package com.example.arruler.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaModeTest {
    // 3 x 4 m rectangle on the floor (y = 0).
    private val rect = listOf(
        MeasurePoint(0f, 0f, 0f), MeasurePoint(3f, 0f, 0f),
        MeasurePoint(3f, 0f, 4f), MeasurePoint(0f, 0f, 4f),
    )

    private fun areaSession(n: Int): MeasurementSession {
        val s = MeasurementSession()
        s.setMode(MeasureMode.AREA)
        s.start(rect[0])
        for (i in 1 until n) s.addPoint(rect[i % rect.size])
        return s
    }

    @Test fun rectangleAreaAndPerimeter() {
        val a = Measurement.Area(rect)
        assertEquals(12f, a.area(), 1e-3f)
        assertEquals(14f, a.perimeter(), 1e-3f)
        assertEquals(listOf(3f, 4f, 3f, 4f), a.wallLengths())
        assertEquals(14f, a.lengthMeters, 1e-3f)
    }

    @Test fun squareFeetConversion() {
        assertEquals(129.1668f, Units.m2ToFt2(12f), 1e-3f)
        assertEquals(12f, Units.ft2ToM2(Units.m2ToFt2(12f)), 1e-4f)
        assertEquals(35.3147f, Units.m3ToFt3(1f), 1e-4f)
        assertEquals(1059.441f, Units.m3ToFt3(30f), 1e-2f)
        assertEquals(129.1668f, Units.FT.areaFromSquareMeters(12f), 1e-3f)
        assertEquals(12f, Units.CM.areaFromSquareMeters(12f), 0f)
        assertEquals(30f, Units.M.volumeFromCubicMeters(30f), 0f)
    }

    @Test fun volumeOfRoom() {
        val v = Measurement.Volume(Measurement.Area(rect), 2.5f)
        assertEquals(30f, v.volume(), 1e-3f)
    }

    @Test fun heightIsDistanceFromFloorPlane() {
        val a = Measurement.Area(rect)
        val up = MeasurePoint(1f, 2.5f, 1f)
        val down = MeasurePoint(1f, -2.5f, 1f)
        // sign follows the outline winding, but above and below are opposite and |h| is 2.5 m
        assertEquals(-a.signedHeightOf(down), a.signedHeightOf(up), 1e-4f)
        assertEquals(2.5f, Math.abs(a.signedHeightOf(up)), 1e-4f)
        assertEquals(2.5f, a.heightOf(up), 1e-4f)
        assertEquals(2.5f, a.heightOf(down), 1e-4f)
        assertEquals(1e-4f, a.heightOf(MeasurePoint(5f, 0f, 5f)), 1e-3f)
    }

    @Test fun closeNeedsThreePoints() {
        val s = areaSession(2)
        s.closePolygon()
        assertFalse(s.state.value.closed)
        assertEquals(Phase.MEASURING, s.state.value.phase)
    }

    @Test fun areaModeAcceptsUnlimitedPointsThenCloses() {
        val s = areaSession(4)
        s.addPoint(MeasurePoint(1f, 0f, 2f))
        assertEquals(5, s.state.value.points.size)
        assertEquals(Phase.MEASURING, s.state.value.phase)
        s.closePolygon()
        assertTrue(s.state.value.closed)
        assertEquals(Phase.FINISHED, s.state.value.phase)
        assertTrue(s.state.value.measurement is Measurement.Area)
        // adding after close is ignored
        s.addPoint(MeasurePoint(9f, 0f, 9f))
        assertEquals(5, s.state.value.points.size)
    }

    @Test fun distanceModeStillStopsAtTwo() {
        val s = MeasurementSession()
        s.start(rect[0])
        s.addPoint(rect[1])
        assertEquals(Phase.FINISHED, s.state.value.phase)
    }

    @Test fun undoRemovesLastPointAndReopens() {
        val s = areaSession(4)
        s.undo()
        assertEquals(3, s.state.value.points.size)
        s.closePolygon()
        assertTrue(s.state.value.closed)
        s.undo() // re-open, points kept
        assertFalse(s.state.value.closed)
        assertEquals(3, s.state.value.points.size)
        assertEquals(Phase.MEASURING, s.state.value.phase)
        s.undo(); s.undo(); s.undo()
        assertEquals(Phase.IDLE, s.state.value.phase)
        assertTrue(s.state.value.points.isEmpty())
    }

    @Test fun heightStepProducesVolume() {
        val s = areaSession(4)
        s.startHeight() // not closed: ignored
        assertFalse(s.state.value.heightActive)
        s.closePolygon()
        s.startHeight()
        assertTrue(s.state.value.heightActive)
        s.setHeightLive(MeasurePoint(1f, 1f, 1f))
        assertEquals(1f, s.state.value.volumeMeasurement!!.height, 1e-4f)
        s.commitHeight(MeasurePoint(1f, 2.5f, 1f))
        val st = s.state.value
        assertFalse(st.heightActive)
        assertEquals(30f, st.volumeMeasurement!!.volume(), 1e-3f)
        assertEquals(12f, st.areaMeasurement!!.area(), 1e-3f)
        s.undo() // drops the height
        assertNull(st.copy(heightPoint = null).volumeMeasurement)
        assertNull(s.state.value.heightPoint)
        assertTrue(s.state.value.closed)
    }

    @Test fun setModeResetsButKeepsUnit() {
        val s = areaSession(3)
        s.setUnit(Units.FT)
        s.setMode(MeasureMode.DISTANCE)
        assertTrue(s.state.value.points.isEmpty())
        assertEquals(Units.FT, s.state.value.unit)
        assertNotNull(s.state.value)
    }
}
