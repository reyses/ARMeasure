package com.example.arruler.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingPauseGateTest {

    /** Mirrors SessionRecorder's state machine with the pure transitions, without ARCore. */
    private class FakeRecorder : Recordable {
        var state: RecordingState = RecordingState.Idle
        var stopCalls = 0
        var sessionStopCalls = 0
        var sessionResumed = true

        fun start() { state = RecordingTransitions.started(state, 1_000L, File("room.mp4")) }

        override val isRecording: Boolean get() = state is RecordingState.Recording

        override fun stop() {
            stopCalls++
            if (state !is RecordingState.Recording) return
            if (sessionResumed) sessionStopCalls++
            state = RecordingTransitions.stopped(state)
        }

        /** What ARSession.pause() triggers (setAutoStopOnPause finalises the file). */
        fun onSessionPaused() { sessionResumed = false; state = RecordingTransitions.stopped(state) }
        fun onSessionResumed() { sessionResumed = true }
    }

    @Test fun pausingTheArViewStopsARunningRecordingFirst() {
        val r = FakeRecorder().apply { start() }
        val gate = RecordingPauseGate(r)
        assertFalse(gate.onGate(paused = false))
        assertEquals(0, r.stopCalls)
        assertTrue(gate.onGate(paused = true)) // View 3D opens
        assertEquals(1, r.sessionStopCalls) // stopped while the session still ran
        assertEquals(RecordingState.Idle, r.state)
        r.onSessionPaused() // then the gate pauses ARCore: nothing left to auto-stop
        assertEquals(RecordingState.Idle, r.state)
        assertEquals(1, r.sessionStopCalls)
    }

    @Test fun resumeNeverRestartsAndRepeatedGatesAreNoOps() {
        val r = FakeRecorder().apply { start() }
        val gate = RecordingPauseGate(r)
        assertTrue(gate.onGate(true))
        assertFalse(gate.onGate(true)) // recomposition while still paused
        r.onSessionPaused()
        r.onSessionResumed()
        assertFalse(gate.onGate(false)) // Back from the viewer
        assertEquals(RecordingState.Idle, r.state)
        assertEquals(1, r.stopCalls)
    }

    @Test fun nothingHappensWithoutARecording() {
        val r = FakeRecorder()
        val gate = RecordingPauseGate(r)
        assertFalse(gate.onGate(true))
        assertFalse(gate.onGate(false))
        assertEquals(0, r.stopCalls)
    }

    @Test fun anErrorStateIsNotARecording() {
        val r = FakeRecorder().apply { state = RecordingTransitions.failed("disk full") }
        assertFalse(RecordingPauseGate(r).onGate(true))
        assertEquals(0, r.stopCalls)
    }

    @Test fun autoStopOnPauseWithoutTheGateEndsIdleAndStaysIdleAfterResume() {
        val r = FakeRecorder().apply { start() }
        r.onSessionPaused()
        assertEquals(RecordingState.Idle, r.state)
        // on resume the session reports no recording: the status check keeps Idle (no phantom Recording)
        assertEquals(RecordingState.Idle, RecordingTransitions.sessionStatus(r.state, ioError = false, active = false))
        r.stop() // stop when not recording is a no-op
        assertEquals(0, r.sessionStopCalls)
    }
}
