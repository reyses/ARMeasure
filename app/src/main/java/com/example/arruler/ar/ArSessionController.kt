package com.example.arruler.ar

import com.example.arruler.measure.MeasurePoint
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the [ARSceneView]: per-frame updates, plane hit testing, anchors and tracking state.
 * Does not draw anything; drawing is [ArRenderer]'s job.
 */
class ArSessionController(private val arView: ARSceneView) {

    /** A hit on a detected plane, with its world position in meters. */
    class PlaneHit(val hitResult: HitResult, val point: MeasurePoint)

    private val _hasSurface = MutableStateFlow(false)
    /** True while the screen centre points at a detected plane. */
    val hasSurface: StateFlow<Boolean> = _hasSurface.asStateFlow()

    private val _trackingState = MutableStateFlow(TrackingState.STOPPED)
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val anchors = mutableListOf<Anchor>()

    /** Called on every AR frame after [hasSurface] and [trackingState] are refreshed. */
    var onFrame: (() -> Unit)? = null

    /** Called with view pixel coordinates when the user taps the AR view. */
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    init {
        arView.planeRenderer.isEnabled = false
        arView.onSessionUpdated = { _, frame ->
            _trackingState.value = frame.camera.trackingState
            _hasSurface.value = hitTestCenter() != null
            onFrame?.invoke()
        }
        arView.setOnGestureListener(
            onSingleTapConfirmed = { event, _ -> onTap?.invoke(event.x, event.y) }
        )
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
