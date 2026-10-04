package com.example.arruler.ar

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** State of the ARCore session recorder. */
sealed interface RecordingState {
    data object Idle : RecordingState
    data class Recording(val startedAtMs: Long, val file: File) : RecordingState
    data class Error(val message: String) : RecordingState
}

/** Pure state transitions, so they can be unit tested without ARCore. */
object RecordingTransitions {
    fun canStart(s: RecordingState): Boolean = s !is RecordingState.Recording

    fun started(s: RecordingState, startedAtMs: Long, file: File): RecordingState =
        if (canStart(s)) RecordingState.Recording(startedAtMs, file) else s

    /** Stop is a no-op unless recording; an error stays visible until the next start. */
    fun stopped(s: RecordingState): RecordingState =
        if (s is RecordingState.Recording) RecordingState.Idle else s

    fun failed(message: String): RecordingState = RecordingState.Error(message)

    /** The ARCore session reported an I/O error or an auto stop while we think we record. */
    fun sessionStatus(s: RecordingState, ioError: Boolean, active: Boolean): RecordingState = when {
        s !is RecordingState.Recording -> s
        ioError -> RecordingState.Error("Recording I/O error (storage full?)")
        !active -> RecordingState.Idle
        else -> s
    }
}

object RecordingFiles {
    const val DIR = "recordings"

    /** room_<yyyyMMdd_HHmmss>.mp4 for the given wall-clock time. */
    fun fileName(timeMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
        f.timeZone = tz
        return "room_${f.format(Date(timeMs))}.mp4"
    }

    /**
     * Normalises a playback location from an adb extra: a bare absolute path becomes file://,
     * anything with a scheme is returned as is. Blank input gives null.
     */
    fun normalizePlaybackUri(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return if (s.startsWith("/")) "file://$s" else s
    }
}

/** mm:ss for elapsed recording time (minutes keep growing past 59). */
fun formatElapsed(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1000L
    return "%02d:%02d".format(Locale.ROOT, total / 60, total % 60)
}

/** A measurement event written to the custom ARCore track as one UTF-8 JSON line. */
sealed interface TrackEvent {
    val timestampMs: Long

    /** A measurement point placed at world position (x, y, z) in meters. */
    data class PointPlaced(
        val index: Int,
        val x: Float,
        val y: Float,
        val z: Float,
        override val timestampMs: Long,
    ) : TrackEvent
}

object TrackEvents {
    /** Track id inside the MP4. Fixed so a replay can look it up. */
    val TRACK_ID: UUID = UUID.fromString("5f0c2a4e-8d3b-4c1a-9e57-0a1b2c3d4e5f")
    const val MIME_TYPE = "application/x-arruler-events+jsonl"

    /** One JSON object terminated by '\n'. Non-finite floats become null. */
    fun encode(e: TrackEvent): ByteArray = when (e) {
        is TrackEvent.PointPlaced ->
            ("{\"type\":\"point\",\"i\":${e.index},\"x\":${num(e.x)},\"y\":${num(e.y)}," +
                "\"z\":${num(e.z)},\"ts\":${e.timestampMs}}\n").toByteArray(Charsets.UTF_8)
    }

    private fun num(v: Float): String = if (v.isNaN() || v.isInfinite()) "null" else v.toString()
}

/** What [RecordingPauseGate] needs from the recorder ([SessionRecorder]; a fake in tests). */
interface Recordable {
    val isRecording: Boolean
    fun stop()
}

/**
 * Stops a running recording cleanly BEFORE the AR view is held paused (3D viewer, object detail, diagnostics, QR scanner).
 * ARCore would otherwise auto-stop it inside Session.pause() (setAutoStopOnPause) on a session that is going away, a path
 * nobody had exercised. Recording is never restarted on resume: the user starts a new one from the RECORD pill.
 */
class RecordingPauseGate(private val recorder: Recordable) {
    /** Call with the gate's state before it is applied; true when a recording was just stopped (announce it). */
    fun onGate(paused: Boolean): Boolean {
        if (!paused || !recorder.isRecording) return false
        recorder.stop()
        return true
    }
}
