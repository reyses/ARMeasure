package com.example.arruler.ml

import com.example.arruler.depth.DepthSample
import com.example.arruler.depth.Intrinsics
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.SupportPlane
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame

/**
 * THE ADAPTER FILE. Everything that has to change when `objscan/AutoBox.kt` (`AutoBoxProvider`) lands is here:
 * [MlAutoBox.propose] is the provider body, [captureMlFrame] builds its frame context on the frame thread.
 * See docs/ML.md for the 5-line AutoBoxProvider wrapper.
 */

/** Everything one tap needs, gathered on the frame callback thread (the ARCore Frame is not valid afterwards). */
class MlFrameInput(
    val capture: MlObjectDetector.Capture,
    /** View pixel -> sensor IMAGE_PIXELS (Frame.transformCoordinates2d(VIEW, IMAGE_PIXELS)); returns (u, v). */
    val viewToImage: (Float, Float) -> FloatArray,
    /** World depth points of this frame (DepthSample.xyz). */
    val worldPoints: FloatArray,
    val projector: PointProjector,
    val plane: SupportPlane,
)

/**
 * Collects the frame-bound inputs; null when the CPU image, depth or tracking is not available yet.
 * [depth] = `DepthFrameSampler.sample(frame)` of the same frame; [displayRotation] = Display.getRotation();
 * [sensorOrientation] = [MlObjectDetector.sensorOrientation] of `session.cameraConfig.cameraId` (cache it).
 */
fun captureMlFrame(
    frame: Frame, detector: MlObjectDetector, depth: DepthSample?, plane: SupportPlane,
    displayRotation: Int, sensorOrientation: Int,
): MlFrameInput? {
    if (depth == null || depth.count < 40) return null
    val cap = detector.capture(frame, displayRotation, sensorOrientation) ?: return null
    val camera = frame.camera
    val ci = camera.imageIntrinsics
    val dims = ci.imageDimensions
    val k = Intrinsics(ci.focalLength[0], ci.focalLength[1], ci.principalPoint[0], ci.principalPoint[1], dims[0], dims[1])
    val pose = FloatArray(16)
    camera.pose.toMatrix(pose, 0)
    // snapshot of the view->image mapping for this frame: transformCoordinates2d needs the live Frame, so map the
    // corner/centre samples now and interpolate affinely afterwards (the VIEW -> IMAGE map is affine for a fixed frame)
    val src = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
    val dst = FloatArray(6)
    frame.transformCoordinates2d(Coordinates2d.VIEW, src, Coordinates2d.IMAGE_PIXELS, dst)
    val ox = dst[0]; val oy = dst[1]
    val ax = dst[2] - ox; val ay = dst[3] - oy      // image delta per view pixel in x
    val bx = dst[4] - ox; val by = dst[5] - oy      // ... per view pixel in y
    val map = { vx: Float, vy: Float -> floatArrayOf(ox + ax * vx + bx * vy, oy + ay * vx + by * vy) }
    return MlFrameInput(cap, map, depth.xyz, CameraProjector(pose, k), plane)
}

/** Tap-to-box provider body. */
class MlAutoBox(private val detector: MlObjectDetector) {
    /** ML Kit detection + depth fit for a tap at view pixel ([tapX], [tapY]); null when nothing is detected near it or the fit fails. */
    suspend fun propose(tapX: Float, tapY: Float, input: MlFrameInput): TapBox? {
        val uv = input.viewToImage(tapX, tapY)
        val dets = detector.detect(input.capture)
        val picked = TapToBox.pick(dets, uv[0], uv[1], input.capture.width, input.capture.height) ?: return null
        return TapToBox.fitBox(input.worldPoints, input.projector, picked, input.plane)
    }

    /** Convenience for `AutoBoxProvider.propose`, which returns only the box. */
    suspend fun proposeBox(tapX: Float, tapY: Float, input: MlFrameInput): ObjectBox? = propose(tapX, tapY, input)?.box
}
