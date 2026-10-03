package com.example.arruler.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberOnGestureListener

/**
 * The full-screen AR view: SceneView 4's `ARSceneView` composable wired to [controller]'s callbacks,
 * with [renderer]'s nodes as its content. Put the 2D overlay on top of it.
 *
 * The session is configured as before (horizontal + vertical planes, [ArSessionController.FOCUS_MODE],
 * plane overlay off, ARCore's default camera config). `key(controller.request)` rebuilds the view,
 * and with it the ARCore session, when a playback dataset is requested.
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
    key(controller.request) {
        ARSceneView(
            lifecycle = lifecycle,
            modifier = modifier.onSizeChanged { controller.onViewSize(it.width, it.height) },
            playbackDatasetUri = controller.request.uri,
            sessionCameraConfig = null,
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
