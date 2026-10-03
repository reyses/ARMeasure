package com.example.arruler.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class RecordingModelTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun fileNameUsesTimestampPattern() {
        val c = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.OCTOBER, 3, 14, 5, 9) }
        assertEquals("room_20261003_140509.mp4", RecordingFiles.fileName(c.timeInMillis, utc))
    }

    @Test fun playbackExtraNormalisation() {
        assertNull(RecordingFiles.normalizePlaybackUri(null))
        assertNull(RecordingFiles.normalizePlaybackUri("  "))
        assertEquals("file:///sdcard/a.mp4", RecordingFiles.normalizePlaybackUri("/sdcard/a.mp4"))
        assertEquals("content://x/y", RecordingFiles.normalizePlaybackUri("content://x/y"))
    }

    @Test fun elapsedFormat() {
        assertEquals("00:00", formatElapsed(-5))
        assertEquals("00:59", formatElapsed(59_999))
        assertEquals("01:05", formatElapsed(65_000))
        assertEquals("75:00", formatElapsed(75 * 60_000L))
    }

    @Test fun pointEventEncodesAsOneJsonLine() {
        val s = String(TrackEvents.encode(TrackEvent.PointPlaced(1, 0.5f, -1.25f, 2f, 1700000000123L)), Charsets.UTF_8)
        assertEquals("{\"type\":\"point\",\"i\":1,\"x\":0.5,\"y\":-1.25,\"z\":2.0,\"ts\":1700000000123}\n", s)
    }

    @Test fun nonFiniteCoordinatesBecomeNull() {
        val s = String(TrackEvents.encode(TrackEvent.PointPlaced(0, Float.NaN, Float.POSITIVE_INFINITY, 0f, 1L)))
        assertTrue(s.contains("\"x\":null,\"y\":null,\"z\":0.0"))
        assertEquals(1, s.count { it == '\n' })
    }

    @Test fun stateTransitions() {
        val f = File("a.mp4")
        var s: RecordingState = RecordingState.Idle
        s = RecordingTransitions.started(s, 10L, f)
        assertEquals(RecordingState.Recording(10L, f), s)
        // second start is ignored
        assertEquals(s, RecordingTransitions.started(s, 99L, File("b.mp4")))
        assertEquals(s, RecordingTransitions.sessionStatus(s, ioError = false, active = true))
        assertEquals(RecordingState.Idle, RecordingTransitions.sessionStatus(s, ioError = false, active = false))
        assertTrue(RecordingTransitions.sessionStatus(s, ioError = true, active = false) is RecordingState.Error)
        assertEquals(RecordingState.Idle, RecordingTransitions.stopped(s))
        val err = RecordingTransitions.failed("boom")
        assertEquals(err, RecordingTransitions.stopped(err))
        assertTrue(RecordingTransitions.canStart(err))
        assertEquals(RecordingState.Idle, RecordingTransitions.stopped(RecordingState.Idle))
    }
}
