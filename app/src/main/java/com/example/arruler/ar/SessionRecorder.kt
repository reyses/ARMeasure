package com.example.arruler.ar

import android.content.Context
import android.net.Uri
import com.google.ar.core.Frame
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.Track
import io.github.sceneview.ar.arcore.ARSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Records the live ARCore session to an MP4 in app-private storage
 * (getExternalFilesDir("recordings")), including a custom track of measurement events.
 * [sessionProvider] returns the live ARCore session or null.
 */
class SessionRecorder(
    private val context: Context,
    private val sessionProvider: () -> Session?,
    private val clock: () -> Long = System::currentTimeMillis,
) : Recordable {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** Events queued from the UI thread, written on the AR frame thread. */
    private val pending = ConcurrentLinkedQueue<TrackEvent>()

    fun start() {
        if (!RecordingTransitions.canStart(_state.value)) return
        val session = sessionProvider() ?: return fail("AR session not ready")
        val dir = context.getExternalFilesDir(RecordingFiles.DIR)
            ?: return fail("External files dir unavailable")
        try {
            dir.mkdirs()
            val now = clock()
            val file = File(dir, RecordingFiles.fileName(now))
            val track = Track(session)
                .setId(TrackEvents.TRACK_ID)
                .setMimeType(TrackEvents.MIME_TYPE)
            val config = RecordingConfig(session)
                .setMp4DatasetUri(Uri.fromFile(file))
                .setAutoStopOnPause(true)
                .addTrack(track)
            pending.clear()
            session.startRecording(config)
            _state.value = RecordingTransitions.started(_state.value, now, file)
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    override val isRecording: Boolean get() = _state.value is RecordingState.Recording

    override fun stop() {
        if (_state.value !is RecordingState.Recording) return
        try {
            val session = sessionProvider()
            // A paused session already finalised the file (setAutoStopOnPause); stopRecording is only for a running one.
            if (session != null && (session as? ARSession)?.isResumed != false) session.stopRecording()
            pending.clear()
            _state.value = RecordingTransitions.stopped(_state.value)
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    fun toggle() = if (_state.value is RecordingState.Recording) stop() else start()

    /** Queues an event; it is written to the track on the next AR frame while recording. */
    fun log(event: TrackEvent) {
        if (_state.value is RecordingState.Recording) pending.add(event)
    }

    /** Call once per AR frame: flushes queued events and checks the session's recording status. */
    fun onFrame(session: Session, frame: Frame) {
        if (_state.value !is RecordingState.Recording) {
            pending.clear()
            return
        }
        val status = session.recordingStatus
        if (status == RecordingStatus.OK) {
            while (true) {
                val e = pending.poll() ?: break
                try {
                    val bytes = TrackEvents.encode(e)
                    val buf = ByteBuffer.allocateDirect(bytes.size)
                    buf.put(bytes).flip()
                    frame.recordTrackData(TrackEvents.TRACK_ID, buf)
                } catch (ex: Exception) {
                    // A dropped event must never break the frame loop.
                }
            }
        }
        val cur = _state.value as? RecordingState.Recording ?: return
        // Grace period: the status may read NONE for a moment right after startRecording.
        val grace = clock() - cur.startedAtMs < 1500
        _state.value = RecordingTransitions.sessionStatus(
            cur,
            ioError = status == RecordingStatus.IO_ERROR,
            active = status == RecordingStatus.OK || grace,
        )
    }

    /** setAutoStopOnPause(true) finalises the file when the session pauses. */
    fun onSessionPaused() {
        pending.clear()
        _state.value = RecordingTransitions.stopped(_state.value)
    }

    private fun fail(msg: String) {
        _state.value = RecordingTransitions.failed(msg)
    }
}
