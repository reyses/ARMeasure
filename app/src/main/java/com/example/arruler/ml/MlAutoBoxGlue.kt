package com.example.arruler.ml

import com.example.arruler.depth.DepthSample
import com.example.arruler.depth.Intrinsics
import com.example.arruler.objscan.AutoBoxProvider
import com.example.arruler.objscan.FrameContext
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.SupportPlane
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.util.Log

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
    return buildInput(frame, cap, depth.xyz, plane)
}

/**
 * The frame-bound part of a tap (image copy, view-to-image map, camera projector) with no depth points: the provider
 * takes the points from the pre-scan cloud around the tap ([DepthPointSource]), which keeps filling while it waits and
 * holds several views of the object, instead of one frame's depth. Main thread, inside the AR frame.
 */
fun captureMlTap(frame: Frame, detector: MlObjectDetector, plane: SupportPlane, displayRotation: Int, sensorOrientation: Int): MlFrameInput? {
    val cap = detector.capture(frame, displayRotation, sensorOrientation) ?: return null
    return buildInput(frame, cap, FloatArray(0), plane)
}

private fun buildInput(frame: Frame, cap: MlObjectDetector.Capture, worldPoints: FloatArray, plane: SupportPlane): MlFrameInput {
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
    return MlFrameInput(cap, map, worldPoints, CameraProjector(pose, k), plane)
}

/** Tap-to-box provider body. */
class MlAutoBox(private val detector: MlObjectDetector) {
    /** The detection under the tap (see [TapToBox.pick]), or null when ML Kit found nothing near it. */
    suspend fun detectUnder(tapX: Float, tapY: Float, input: MlFrameInput): Detection? {
        val uv = input.viewToImage(tapX, tapY)
        return TapToBox.pick(detector.detect(input.capture), uv[0], uv[1], input.capture.width, input.capture.height)
    }

    /** The aligned 3D box for [det] from [points] (world xyz), or null when too few depth points fall inside it. */
    fun fit(det: Detection, input: MlFrameInput, points: FloatArray): TapBox? = TapToBox.fitBox(points, input.projector, det, input.plane)

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

/** 'Home good · 82 %': the coarse ML Kit class under the tap; null when the model gave no label. */
fun mlLabelText(d: Detection): String? {
    val t = d.label?.takeIf { it.isNotBlank() } ?: return null
    return "$t · ${Math.round(d.confidence * 100)} %"
}

/**
 * THE [AutoBoxProvider]: ML Kit finds the object under the tap in the camera image, and the depth points of the pre-scan
 * cloud inside its 2D box give the aligned 3D box ([TapToBox]). It falls back to [fallback] (the depth-only
 * `DepthFitAutoBox`) when ML Kit finds nothing under the tap, the image was not available, the depth never filled enough
 * for a fit within [timeoutMs], or anything throws. [takeInput] hands over the frame capture made on the main thread
 * inside the AR frame of the tap (one-shot: it returns null once taken); [onLabel] reports the label of the detection
 * under the tap ([mlLabelText]) or null, from the provider's background thread.
 */
class MlAutoBoxProvider(
    private val ml: MlAutoBox,
    private val fallback: AutoBoxProvider,
    private val takeInput: () -> MlFrameInput?,
    private val onLabel: (String?) -> Unit,
    private val timeoutMs: Long = 2500L,
    private val pollMs: Long = 150L,
    private val minVoxels: Int = 150,
) : AutoBoxProvider {

    override suspend fun propose(tapX: Float, tapY: Float, frameContext: FrameContext): ObjectBox? {
        val input = takeInput()
        onLabel(null)
        if (input != null) {
            val box = try {
                tryMl(tapX, tapY, frameContext, input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ML tap-to-box failed, using the depth fit: ${e.message}")
                null
            }
            if (box != null) return box
        }
        return fallback.propose(tapX, tapY, frameContext)
    }

    private suspend fun tryMl(tapX: Float, tapY: Float, ctx: FrameContext, input: MlFrameInput): ObjectBox? {
        val det = ml.detectUnder(tapX, tapY, input) ?: return null
        onLabel(mlLabelText(det))
        var waited = 0L
        var lastTried = 0
        while (true) {
            val n = ctx.depth.voxelCount()
            val last = waited >= timeoutMs
            if (n >= minVoxels && (n * 2 >= lastTried * 3 || last)) {
                lastTried = n
                val pts = ctx.depth.points()
                val tb = withContext(Dispatchers.Default) { ml.fit(det, input, pts) }
                if (tb != null) return tb.box
            }
            if (last) return null
            delay(pollMs)
            waited += pollMs
        }
    }

    private companion object {
        const val TAG = "MlAutoBoxProvider"
    }
}
