package com.example.arruler.ar

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.example.arruler.texture.CameraConfigChooser
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Session
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberOnGestureListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The full-screen AR view: SceneView 4's `ARSceneView` composable wired to [controller]'s callbacks,
 * with [renderer]'s nodes as its content. Put the 2D overlay on top of it.
 *
 * The session is configured as before (horizontal + vertical planes, [ArSessionController.FOCUS_MODE],
 * plane overlay off) with the camera config of [CameraConfigChooser]: the largest CPU image up to 1920x1080 that
 * still supports AUTOMATIC depth, for the photo texture (a playback dataset keeps its own config).
 * `key(controller.request)` rebuilds the view, and with it the ARCore session, when a playback dataset is requested.
 */
@Composable
fun ArSceneHost(
    controller: ArSessionController,
    renderer: ArRenderer,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
) {
    // While [paused] the view's lifecycle is held at CREATED: ARCore pauses and Filament stops drawing
    // (so a second GL surface can sit on top) but the view stays composed and the anchors survive.
    val lifecycle = rememberGatedLifecycle(paused)
    val live = controller.request.uri == null
    val cameraConfig = remember(live) { if (live) { s: Session -> chooseCameraConfig(s) } else null }
    key(controller.request) {
        ARSceneView(
            lifecycle = lifecycle,
            modifier = modifier.onSizeChanged { controller.onViewSize(it.width, it.height) },
            playbackDatasetUri = controller.request.uri,
            sessionCameraConfig = cameraConfig,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
            focusMode = ArSessionController.FOCUS_MODE,
            sessionConfiguration = controller::configureSession,
            planeRenderer = false,
            onSessionCreated = controller::onSessionCreated,
            onSessionPaused = controller::onSessionPaused,
            onSessionUpdated = controller::onSessionUpdated,
            onPlaybackFailed = controller::onPlaybackFailed,
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, _ -> controller.dispatchTap(event.x, event.y) },
            ),
        ) {
            with(renderer) { Nodes() }
        }
    }
}

private const val TAG = "ArSceneHost"
private val configLogged = AtomicBoolean(false)

/** [CameraConfigChooser.select] plus one log line (once per process) with what it chose, for the device-only check. */
private fun chooseCameraConfig(session: Session): CameraConfig {
    val cfg = CameraConfigChooser.select(session)
    if (configLogged.compareAndSet(false, true)) {
        Log.i(
            TAG,
            "Camera config: CPU image ${cfg.imageSize.width}x${cfg.imageSize.height}, GPU texture " +
                "${cfg.textureSize.width}x${cfg.textureSize.height}, ${cfg.fpsRange.lower}-${cfg.fpsRange.upper} fps, " +
                "depth sensor ${cfg.depthSensorUsage}, camera ${cfg.cameraId}",
        )
    }
    return cfg
}
