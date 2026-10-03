package com.example.arruler.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {
    @Test fun metersToEachUnitUsesExactFactors() {
        assertEquals(100f, Units.CM.fromMeters(1f), 1e-4f)
        assertEquals(39.3701f, Units.INCH.fromMeters(1f), 1e-4f)
        assertEquals(1f, Units.M.fromMeters(1f), 0f)
        assertEquals(3.28084f, Units.FT.fromMeters(1f), 1e-5f)
    }

    @Test fun roundTripIsIdentity() {
        for (u in Units.entries) assertEquals(2.5f, u.toMeters(u.fromMeters(2.5f)), 1e-5f)
    }

    @Test fun toggleCyclesCmInchMFt() {
        assertEquals(Units.INCH, Units.CM.next())
        assertEquals(Units.M, Units.INCH.next())
        assertEquals(Units.FT, Units.M.next())
        assertEquals(Units.CM, Units.FT.next())
    }
}

class MeasurementTest {
    @Test fun distanceLengthIsEuclidean() {
        val d = Measurement.Distance(MeasurePoint(0f, 0f, 0f), MeasurePoint(1f, 2f, 2f))
        assertEquals(3f, d.lengthMeters, 1e-6f)
    }

    @Test fun polylineLengthSumsSegments() {
        val p = Measurement.Polyline(
            listOf(MeasurePoint(0f, 0f, 0f), MeasurePoint(3f, 0f, 0f), MeasurePoint(3f, 4f, 0f))
        )
        assertEquals(7f, p.lengthMeters, 1e-6f)
    }
}

class MeasurementSessionTest {
    private val a = MeasurePoint(0f, 0f, 0f)
    private val b = MeasurePoint(0.3f, 0.4f, 0f)

    @Test fun startsIdleAndEmpty() {
        val s = MeasurementSession().state.value
        assertEquals(Phase.IDLE, s.phase)
        assertTrue(s.points.isEmpty())
        assertEquals(0f, s.lengthMeters, 0f)
    }

    @Test fun addPointsMovesThroughMeasuringToFinished() {
        val session = MeasurementSession()
        session.addPoint(a)
        assertEquals(Phase.MEASURING, session.state.value.phase)
        session.addPoint(b)
        val s = session.state.value
        assertEquals(Phase.FINISHED, s.phase)
        assertEquals(listOf(a, b), s.points)
        assertEquals(0.5f, s.lengthMeters, 1e-6f)
    }

    @Test fun liveEndPointGivesDistanceWhileMeasuring() {
        val session = MeasurementSession()
        session.start(a)
        session.setLive(b)
        assertEquals(0.5f, session.state.value.lengthMeters, 1e-6f)
    }

    @Test fun undoRemovesLastPoint() {
        val session = MeasurementSession()
        session.addPoint(a)
        session.addPoint(b)
        session.undo()
        assertEquals(listOf(a), session.state.value.points)
        assertEquals(Phase.MEASURING, session.state.value.phase)
        session.undo()
        assertEquals(Phase.IDLE, session.state.value.phase)
        session.undo() // no-op on empty
        assertTrue(session.state.value.points.isEmpty())
    }

    @Test fun clearResetsButKeepsUnit() {
        val session = MeasurementSession()
        session.setUnit(Units.FT)
        session.addPoint(a)
        session.addPoint(b)
        session.clear()
        val s = session.state.value
        assertTrue(s.points.isEmpty())
        assertNull(s.live)
        assertEquals(Phase.IDLE, s.phase)
        assertEquals(Units.FT, s.unit)
    }

    @Test fun polylineSessionKeepsMeasuringPastTwoPoints() {
        val session = MeasurementSession(maxPoints = Int.MAX_VALUE)
        session.addPoint(a)
        session.addPoint(b)
        session.addPoint(MeasurePoint(0.3f, 0.4f, 1f))
        assertEquals(Phase.MEASURING, session.state.value.phase)
        assertEquals(1.5f, session.state.value.lengthMeters, 1e-6f)
    }
}
