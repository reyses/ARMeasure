package com.example.arruler.ar

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.arruler.measure.MeasurePoint
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.ARSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the AR session state fed by [ArSceneHost]'s `ARSceneView` callbacks: per-frame updates,
 * plane hit testing, anchors, tracking state, recording ([recorder]) and dataset playback.
 * Does not draw anything; drawing is [ArRenderer]'s job.
 *
 * SceneView 4.x owns the ARCore session (create / resume / pause / close follow the composition and
 * the lifecycle), so this class never creates or pauses it; it only observes it.
 */
class ArSessionController(context: Context) {

    /** A hit on a detected plane, with its world position in meters. */
    class PlaneHit(val hitResult: HitResult, val point: MeasurePoint)

    /**
     * What the AR view is built from. SceneView reads the playback dataset once, before the first
     * resume, so every new request (even for the same file) is a new instance and rebuilds the
     * `ARSceneView` and its session through `key(request)` in [ArSceneHost].
     */
    internal class PlaybackRequest(val uri: Uri?)

    companion object {
        private const val TAG = "ArSessionController"

        /** Flip to Config.FocusMode.AUTO to go back to autofocus. */
        val FOCUS_MODE: Config.FocusMode = Config.FocusMode.FIXED

        /** Schemes SceneView accepts for a playback dataset (anything else throws at composition). */
        private val PLAYBACK_SCHEMES = setOf("content", "file")
    }

    private val _hasSurface = MutableStateFlow(false)
    /** True while the screen centre points at a detected plane. */
    val hasSurface: StateFlow<Boolean> = _hasSurface.asStateFlow()

    private val _trackingState = MutableStateFlow(TrackingState.STOPPED)
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val _playbackStatus = MutableStateFlow(PlaybackStatus.NONE)
    /** NONE while the live camera is used; OK / FINISHED / IO_ERROR while a dataset plays. */
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    /** The request the current `ARSceneView` was built for; read by [ArSceneHost]. */
    internal var request by mutableStateOf(PlaybackRequest(null))
        private set

    /** Last session and frame seen via the SceneView callbacks (main thread only). */
    private var knownSession: Session? = null
    private var lastFrame: Frame? = null

    /** Size in pixels of the AR surface, the coordinate space of [hitTest]. */
    private var viewWidth = 0
    private var viewHeight = 0

    /**
     * The live ARCore session, or null before it exists or once SceneView has closed it. Calling a
     * closed session is a native use-after-free, so every use goes through here.
     */
    private fun currentSession(): Session? =
        knownSession?.takeUnless { (it as? ARSession)?.isClosed == true }

    val recorder = SessionRecorder(context, ::currentSession)

    private val anchors = mutableListOf<Anchor>()

    /** Called on every AR frame after [hasSurface] and [trackingState] are refreshed. */
    var onFrame: (() -> Unit)? = null

    /** Called with view pixel coordinates when the user taps the AR view. */
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    // ---- ARSceneView callbacks (wired by ArSceneHost) ----

    /**
     * SceneView applies its typed `planeFindingMode` / `focusMode` before this runs; this adds the
     * pieces without a parameter. Depth stays disabled; just learn whether the device supports it.
     */
    internal fun configureSession(session: Session, config: Config) {
        // SceneView 4 defaults to ENVIRONMENTAL_HDR; 2.3.0 left ARCore's default, which we keep.
        config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
        val depthOk = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        Log.i(TAG, "Depth AUTOMATIC supported on this device: $depthOk")
    }

    internal fun onSessionCreated(session: Session) {
        knownSession = session
        lastFrame = null
    }

    internal fun onSessionPaused(@Suppress("UNUSED_PARAMETER") session: Session) {
        recorder.onSessionPaused()
    }

    internal fun onSessionUpdated(session: Session, frame: Frame) {
        knownSession = session
        lastFrame = frame
        recorder.onFrame(session, frame)
        _playbackStatus.value = session.playbackStatus
        _trackingState.value = frame.camera.trackingState
        _hasSurface.value = hitTestCenter() != null
        onFrame?.invoke()
    }

    internal fun onPlaybackFailed(e: Exception) {
        Log.e(TAG, "Playback failed", e)
        _playbackStatus.value = PlaybackStatus.IO_ERROR
    }

    internal fun onViewSize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    internal fun dispatchTap(x: Float, y: Float) {
        onTap?.invoke(x, y)
    }

    // ---- Playback ----

    /**
     * Plays an MP4 dataset instead of the camera by rebuilding the `ARSceneView` with
     * `playbackDatasetUri` (SceneView binds it to the new session before its first resume).
     * Anchors of the old session die with it, so they are released here.
     * Returns false if SceneView would reject the Uri (only content:// and file:// are accepted).
     * An unreadable file is reported later through [playbackStatus] (IO_ERROR).
     */
    fun startPlayback(uri: Uri): Boolean {
        if (uri.scheme !in PLAYBACK_SCHEMES) return false
        recorder.stop()
        releaseAnchors()
        lastFrame = null
        _hasSurface.value = false
        _playbackStatus.value = PlaybackStatus.OK
        request = PlaybackRequest(uri)
        return true
    }

    // ---- Hit testing and anchors ----

    fun hitTestCenter(): PlaneHit? = hitTest(viewWidth / 2f, viewHeight / 2f)

    /** First hit inside a detected plane's polygon at the given view pixel, or null. */
    fun hitTest(x: Float, y: Float): PlaneHit? {
        if (currentSession() == null) return null
        val frame = lastFrame ?: return null
        if (viewWidth == 0 || viewHeight == 0) return null
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
        // After SceneView closed the session its anchors are already gone; detach would be a native call.
        if (currentSession() != null) anchors.forEach { it.detach() }
        anchors.clear()
    }
}
