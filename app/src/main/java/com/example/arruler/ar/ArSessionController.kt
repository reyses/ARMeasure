package com.example.arruler.ar

import android.net.Uri
import android.util.Log
import com.example.arruler.measure.MeasurePoint
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.ARSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the [ARSceneView]: per-frame updates, plane hit testing, anchors, tracking state,
 * recording ([recorder]) and dataset playback. Does not draw anything; drawing is [ArRenderer]'s job.
 */
class ArSessionController(private val arView: ARSceneView) {

    /** A hit on a detected plane, with its world position in meters. */
    class PlaneHit(val hitResult: HitResult, val point: MeasurePoint)

    companion object {
        private const val TAG = "ArSessionController"

        /** Flip to Config.FocusMode.AUTO to go back to autofocus. */
        val FOCUS_MODE: Config.FocusMode = Config.FocusMode.FIXED
    }

    private val _hasSurface = MutableStateFlow(false)
    /** True while the screen centre points at a detected plane. */
    val hasSurface: StateFlow<Boolean> = _hasSurface.asStateFlow()

    private val _trackingState = MutableStateFlow(TrackingState.STOPPED)
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val _playbackStatus = MutableStateFlow(PlaybackStatus.NONE)
    /** NONE while the live camera is used; OK / FINISHED / IO_ERROR while a dataset plays. */
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    /** Last session seen via the SceneView callbacks. */
    private var knownSession: Session? = null
    private var pendingPlayback: Uri? = null

    /**
     * The live ARCore session: `ARSceneView.session` (an [ARSession], which extends [Session]),
     * falling back to the one captured from the callbacks.
     */
    private fun currentSession(): Session? =
        runCatching<Session?> { arView.session }.getOrNull() ?: knownSession

    val recorder = SessionRecorder(arView.context, ::currentSession)

    private val anchors = mutableListOf<Anchor>()

    /** Called on every AR frame after [hasSurface] and [trackingState] are refreshed. */
    var onFrame: (() -> Unit)? = null

    /** Called with view pixel coordinates when the user taps the AR view. */
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    init {
        arView.planeRenderer.isEnabled = false
        arView.sessionConfiguration = { session, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.focusMode = FOCUS_MODE
            // Depth stays disabled; just learn whether the device supports it.
            val depthOk = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            Log.i(TAG, "Depth AUTOMATIC supported on this device: $depthOk")
        }
        arView.onSessionCreated = { session ->
            knownSession = session
            pendingPlayback?.let { uri -> pendingPlayback = null; applyPlayback(session, uri) }
        }
        arView.onSessionPaused = { recorder.onSessionPaused() }
        arView.onSessionUpdated = { session, frame ->
            knownSession = session
            recorder.onFrame(session, frame)
            _playbackStatus.value = session.playbackStatus
            _trackingState.value = frame.camera.trackingState
            _hasSurface.value = hitTestCenter() != null
            onFrame?.invoke()
        }
        arView.setOnGestureListener(
            onSingleTapConfirmed = { event, _ -> onTap?.invoke(event.x, event.y) }
        )
    }

    /**
     * Plays an MP4 dataset instead of the camera: pause -> setPlaybackDatasetUri -> resume.
     * If the session does not exist yet the request is applied when it is created.
     * Returns false if ARCore rejected the dataset.
     */
    fun startPlayback(uri: Uri): Boolean {
        recorder.stop()
        val session = currentSession()
        if (session == null) {
            pendingPlayback = uri
            return true
        }
        return applyPlayback(session, uri)
    }

    private fun applyPlayback(session: Session, uri: Uri): Boolean = try {
        val wasResumed = (session as? ARSession)?.isResumed ?: true
        if (wasResumed) session.pause()
        session.setPlaybackDatasetUri(uri)
        if (wasResumed) session.resume()
        _playbackStatus.value = PlaybackStatus.OK
        true
    } catch (e: Exception) {
        Log.e(TAG, "Playback failed for $uri", e)
        _playbackStatus.value = PlaybackStatus.IO_ERROR
        false
    }

    fun hitTestCenter(): PlaneHit? = hitTest(arView.width / 2f, arView.height / 2f)

    /** First hit inside a detected plane's polygon at the given view pixel, or null. */
    fun hitTest(x: Float, y: Float): PlaneHit? {
        val frame = arView.frame ?: return null
        if (arView.width == 0 || arView.height == 0) return null
        for (hit in frame.hitTest(x, y)) {
            val trackable = hit.trackable
            val pose = hit.hitPose
            if (trackable is Plane && trackable.isPoseInPolygon(pose)) {
                return PlaneHit(hit, MeasurePoint(pose.tx(), pose.ty(), pose.tz()))
            }
        }
        return null
    }

    /** Creates an anchor at the hit and returns its world position. */
    fun createAnchor(hit: PlaneHit): MeasurePoint {
        val anchor = hit.hitResult.createAnchor()
        anchors += anchor
        val pose = anchor.pose
        return MeasurePoint(pose.tx(), pose.ty(), pose.tz())
    }

    fun releaseAnchors() {
        anchors.forEach { it.detach() }
        anchors.clear()
    }
}
