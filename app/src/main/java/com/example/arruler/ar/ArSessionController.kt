package com.example.arruler.ar

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.arruler.depth.DepthFrameSampler
import com.example.arruler.measure.MeasurePoint
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.ARSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the AR session state fed by [ArSceneHost]'s `ARSceneView` callbacks: per-frame updates,
 * surface hit testing, anchors, tracking state, recording ([recorder]) and dataset playback.
 * Does not draw anything; drawing is [ArRenderer]'s job.
 *
 * SceneView 4.x owns the ARCore session (create / resume / pause / close follow the composition and
 * the lifecycle), so this class never creates or pauses it; it only observes it.
 */
class ArSessionController(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** A hit on a surface: world position in meters, how trustworthy it is and what the surface is. */
    class SurfaceHit(
        val hitResult: HitResult,
        val point: MeasurePoint,
        val quality: HitQuality,
        val kind: SurfaceKind,
    )

    /** The latest raw-depth confidence as an ARGB bitmap plus where its 4 corners land in the view. */
    class DepthHeat(
        val bitmap: Bitmap,
        /** View-pixel x,y of the bitmap's top-left, top-right, bottom-left, bottom-right corners. */
        val corners: FloatArray,
    )

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

        /** Planes are re-read at most this often (2 Hz). */
        private const val SURFACES_INTERVAL_MS = 500L

        /** The depth confidence heatmap is rebuilt at most this often (4 Hz). */
        private const val HEAT_INTERVAL_MS = 250L
    }

    private val _hasSurface = MutableStateFlow(false)
    /** True while the screen centre points at any usable surface (plane, depth or feature point). */
    val hasSurface: StateFlow<Boolean> = _hasSurface.asStateFlow()

    private val _centerHit = MutableStateFlow<HitInfo?>(null)
    /** Quality and kind of the surface under the screen centre, null when there is none. */
    val centerHit: StateFlow<HitInfo?> = _centerHit.asStateFlow()

    private val _depthHeat = MutableStateFlow<DepthHeat?>(null)
    /** Latest depth confidence heatmap while [setDepthHeatEnabled] is on, else null. */
    val depthHeat: StateFlow<DepthHeat?> = _depthHeat.asStateFlow()

    private val _depthSupported = MutableStateFlow(false)
    /** True once a session exists and supports AUTOMATIC depth (depth hits, SCAN mode, confidence overlay). */
    val depthSupported: StateFlow<Boolean> = _depthSupported.asStateFlow()

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

    /**
     * The Frame of the latest session update. Only valid on the main thread inside [onFrame] (ARCore
     * invalidates Frames and their Images on the next update); never cache it.
     */
    val latestFrame: Frame? get() = lastFrame

    /**
     * Depth mode requested for every session configuration (read by [configureSession]). AUTOMATIC
     * for the whole app: Frame.hitTest then returns DepthPoint hits and the raw depth + confidence
     * images stay available for SCAN and the confidence overlay (no mid-session reconfigure).
     */
    var depthMode: Config.DepthMode = Config.DepthMode.AUTOMATIC

    /** Called on every AR frame after [hasSurface] and [trackingState] are refreshed. */
    var onFrame: (() -> Unit)? = null

    /** Called with view pixel coordinates when the user taps the AR view. */
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    /** Receives the plane patches (at most 2 Hz) while [setSurfacesEnabled] is on; an empty list when switched off. */
    var onSurfaces: ((List<SurfacePatch>) -> Unit)? = null

    private var surfacesOn = false
    private var lastSurfacesMs = 0L
    private val planeIds = HashMap<Plane, Int>()
    private var nextPlaneId = 0

    private var heatOn = false
    private var lastHeatMs = 0L
    @Volatile private var heatInFlight = false
    private val heatSampler = DepthFrameSampler()

    private var cachedFrame: Frame? = null
    private var cachedCenter: SurfaceHit? = null

    // ---- ARSceneView callbacks (wired by ArSceneHost) ----

    /**
     * SceneView applies its typed `planeFindingMode` / `focusMode` before this runs; this adds the
     * pieces without a parameter. Depth is switched on here (AUTOMATIC) when the device supports it.
     */
    internal fun configureSession(session: Session, config: Config) {
        // SceneView 4 defaults to ENVIRONMENTAL_HDR; 2.3.0 left ARCore's default, which we keep.
        config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
        val depthOk = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        Log.i(TAG, "Depth AUTOMATIC supported on this device: $depthOk")
        if (depthMode != Config.DepthMode.DISABLED && session.isDepthModeSupported(depthMode)) {
            config.depthMode = depthMode
        }
    }

    internal fun onSessionCreated(session: Session) {
        knownSession = session
        lastFrame = null
        _depthSupported.value = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
    }

    /**
     * Makes sure the live session runs with AUTOMATIC depth (it normally already does, from
     * [configureSession]). Depth is never switched off again: hit testing needs it in every mode,
     * so `on = false` is a no-op. Returns false if depth is unsupported or there is no live session.
     */
    fun setDepthEnabled(on: Boolean): Boolean {
        val s = currentSession() ?: return false
        if (!s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) return false
        if (!on) return true
        depthMode = Config.DepthMode.AUTOMATIC
        return try {
            val cfg = s.config
            if (cfg.depthMode != Config.DepthMode.AUTOMATIC) {
                cfg.depthMode = Config.DepthMode.AUTOMATIC
                s.configure(cfg)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "configure(depthMode=AUTOMATIC) failed", e)
            false
        }
    }

    fun setSurfacesEnabled(on: Boolean) {
        if (surfacesOn == on) return
        surfacesOn = on
        lastSurfacesMs = 0L
        if (!on) onSurfaces?.invoke(emptyList())
    }

    fun setDepthHeatEnabled(on: Boolean) {
        if (heatOn == on) return
        heatOn = on
        if (!on) _depthHeat.value = null
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
        val center = hitTestCenter()
        _hasSurface.value = center != null
        _centerHit.value = center?.let { HitInfo(it.quality, it.kind) }
        val now = SystemClock.elapsedRealtime()
        if (surfacesOn && now - lastSurfacesMs >= SURFACES_INTERVAL_MS) {
            lastSurfacesMs = now
            onSurfaces?.invoke(buildPatches(session))
        }
        if (heatOn && !heatInFlight && now - lastHeatMs >= HEAT_INTERVAL_MS) {
            lastHeatMs = now
            captureHeat(frame)
        }
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
        cachedFrame = null
        cachedCenter = null
        _hasSurface.value = false
        _centerHit.value = null
        planeIds.clear()
        _playbackStatus.value = PlaybackStatus.OK
        request = PlaybackRequest(uri)
        return true
    }

    // ---- Hit testing and anchors ----

    /** The centre hit of the latest frame; computed once per frame and shared by its callers. */
    fun hitTestCenter(): SurfaceHit? {
        val frame = lastFrame
        if (frame != null && frame === cachedFrame) return cachedCenter
        val hit = hitTest(viewWidth / 2f, viewHeight / 2f)
        cachedFrame = frame
        cachedCenter = hit
        return hit
    }

    /**
     * The best hit at the given view pixel, or null. Ranked over ALL results of ARCore's hit test:
     * plane inside its polygon, then plane extended (tracking, within 1.5 m of the polygon), then
     * depth point, then oriented feature point; ties go to the nearest ([HitRanking]).
     */
    fun hitTest(x: Float, y: Float): SurfaceHit? {
        if (currentSession() == null) return null
        val frame = lastFrame ?: return null
        if (viewWidth == 0 || viewHeight == 0) return null
        val results = ArrayList<HitResult>()
        val candidates = ArrayList<HitCandidate>()
        for (hit in frame.hitTest(x, y)) {
            val c = classify(hit) ?: continue
            results += hit
            candidates += c
        }
        val i = HitRanking.best(candidates)
        if (i < 0) return null
        val hit = results[i]
        val pose = hit.hitPose
        return SurfaceHit(hit, MeasurePoint(pose.tx(), pose.ty(), pose.tz()), candidates[i].quality, candidates[i].kind)
    }

    private fun classify(hit: HitResult): HitCandidate? {
        val trackable = hit.trackable
        val pose = hit.hitPose
        return when {
            trackable is Plane -> {
                val inPolygon = trackable.isPoseInPolygon(pose)
                val dist = if (inPolygon) {
                    0f
                } else {
                    val local = trackable.centerPose.inverse().transformPoint(floatArrayOf(pose.tx(), pose.ty(), pose.tz()))
                    HitRanking.polygonDistance(polygonXz(trackable), local[0], local[2])
                }
                val q = HitRanking.planeQuality(
                    inPolygon, dist, trackable.trackingState == TrackingState.TRACKING,
                ) ?: return null
                HitCandidate(q, hit.distance, planeKind(trackable))
            }
            trackable is DepthPoint -> HitCandidate(HitQuality.DEPTH, hit.distance, kindOfPose(pose))
            trackable is Point && trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL ->
                HitCandidate(HitQuality.POINT, hit.distance, kindOfPose(pose))
            else -> null
        }
    }

    private fun planeKind(plane: Plane): SurfaceKind = when (plane.type) {
        Plane.Type.HORIZONTAL_UPWARD_FACING -> SurfaceKind.FLOOR
        Plane.Type.HORIZONTAL_DOWNWARD_FACING -> SurfaceKind.CEILING
        Plane.Type.VERTICAL -> SurfaceKind.WALL
    }

    /** The pose's Y axis is the surface normal for depth and oriented feature points. */
    private fun kindOfPose(pose: Pose): SurfaceKind =
        HitRanking.kindFromNormalY(pose.getTransformedAxis(1, 1f)[1])

    /** The plane polygon as packed x,z pairs in the centre pose's frame. */
    private fun polygonXz(plane: Plane): FloatArray {
        val buf = plane.polygon
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return out
    }

    // ---- SURFACES overlay ----

    /** Every tracking, non-subsumed plane as a world-space polygon. */
    private fun buildPatches(session: Session): List<SurfacePatch> {
        val live = HashSet<Plane>()
        val out = ArrayList<SurfacePatch>()
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val xz = polygonXz(plane)
            if (xz.size < 6) continue
            val center = plane.centerPose
            val poly = List(xz.size / 2) { i ->
                val w = center.transformPoint(floatArrayOf(xz[2 * i], 0f, xz[2 * i + 1]))
                MeasurePoint(w[0], w[1], w[2])
            }
            live += plane
            val id = planeIds.getOrPut(plane) { nextPlaneId++ }
            out += SurfacePatch(id, planeKind(plane), poly, SurfaceMath.alphaFromArea(SurfaceMath.area(poly)))
        }
        planeIds.keys.retainAll(live)
        return out
    }

    // ---- depth confidence overlay ----

    /**
     * Main thread: copies the raw depth + confidence out of [frame] and maps the image corners to
     * view pixels (IMAGE_NORMALIZED to VIEW); the bitmap is painted on [scope]'s background thread.
     */
    private fun captureHeat(frame: Frame) {
        val raw = heatSampler.acquire(frame) ?: return
        val corners = FloatArray(8)
        try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_NORMALIZED, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
                Coordinates2d.VIEW, corners,
            )
        } catch (e: Exception) {
            Log.w(TAG, "transformCoordinates2d failed", e)
            return
        }
        heatInFlight = true
        scope.launch {
            try {
                val px = IntArray(raw.w * raw.h) { i ->
                    SurfaceMath.confidenceColor(raw.conf[i].toInt() and 0xFF, raw.depthMm[i].toInt() and 0xFFFF)
                }
                val bmp = Bitmap.createBitmap(px, raw.w, raw.h, Bitmap.Config.ARGB_8888)
                if (heatOn) _depthHeat.value = DepthHeat(bmp, corners)
            } finally {
                heatInFlight = false
            }
        }
    }

    /** Creates an anchor at the hit and returns its world position. */
    fun createAnchor(hit: SurfaceHit): MeasurePoint {
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
