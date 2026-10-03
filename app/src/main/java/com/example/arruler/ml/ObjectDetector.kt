package com.example.arruler.ml

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 2D object proposals for tap-to-box: ML Kit SUBJECT SEGMENTATION through Google Play services (the unbundled
 * variant: the model lives in Play services and is shared by apps, the APK grows by ~0.4 MB instead of the ~8.2 MB of
 * the bundled `com.google.mlkit:object-detection:17.0.2`; there is no Play-services build of Object Detection).
 * Inference is on-device; the only network use is Play services downloading the module once (see docs/ML.md).
 * It returns every salient foreground subject with its 2D bounding box but no class label ([Detection.label] is null;
 * the shape label comes from [ShapeClassifier]).
 *
 * Use [capture] on the frame callback thread (it copies the ARCore CPU image to NV21 and closes the Image in a
 * finally block), then [detect] on any coroutine. Call [warmUp] once when the object screen opens so the module is
 * downloaded / initialised before the first tap. Call [close] when the screen goes away.
 *
 * Orientation: see [ImageOrientation]. The returned [Detection] boxes are mapped back to the sensor image
 * (IMAGE_PIXELS), the same space as `Frame.transformCoordinates2d(VIEW -> IMAGE_PIXELS)` and Camera.getImageIntrinsics().
 */
class MlObjectDetector : AutoCloseable {
    private val segmenter: SubjectSegmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build())
            .build()
    )

    /** Completes when the Play-services module is ready (downloads it on first use); throws if that fails. */
    suspend fun warmUp() {
        suspendCancellableCoroutine { cont ->
            segmenter.initTask
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }
    /** NV21 copy of one CPU camera image; [rotationDegrees] = clockwise turn to upright (see [ImageOrientation]). */
    class Capture(val nv21: ByteArray, val width: Int, val height: Int, val rotationDegrees: Int)

    /**
     * Copies `frame.acquireCameraImage()` (YUV_420_888) into NV21; the Image is closed before returning.
     * [displayRotation] = Display.getRotation() (Surface.ROTATION_*), [sensorOrientation] = [MlObjectDetector.sensorOrientation].
     * Null when the image is not available yet.
     */
    fun capture(frame: Frame, displayRotation: Int, sensorOrientation: Int): Capture? {
        var image: Image? = null
        try {
            image = try { frame.acquireCameraImage() } catch (_: NotYetAvailableException) { return null }
            if (image.format != ImageFormat.YUV_420_888) return null
            val p = image.planes
            val nv21 = ImageOrientation.toNv21(
                image.width, image.height, p[0].buffer, p[0].rowStride, p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
            )
            val rot = ImageOrientation.rotationDegrees(sensorOrientation, ImageOrientation.surfaceRotationDegrees(displayRotation))
            return Capture(nv21, image.width, image.height, rot)
        } finally {
            image?.close()
        }
    }

    /** Subject boxes in sensor IMAGE_PIXELS; empty when nothing salient was found. */
    suspend fun detect(c: Capture): List<Detection> {
        val input = InputImage.fromByteArray(c.nv21, c.width, c.height, c.rotationDegrees, InputImage.IMAGE_FORMAT_NV21)
        val result = suspendCancellableCoroutine { cont ->
            segmenter.process(input)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return result.subjects.map { s ->
            val r = ImageOrientation.uprightBoxToImage(
                s.startX.toFloat(), s.startY.toFloat(), (s.startX + s.width).toFloat(), (s.startY + s.height).toFloat(),
                c.width, c.height, c.rotationDegrees,
            )
            Detection(r[0], r[1], r[2], r[3], null, 1f)
        }
    }

    override fun close() = segmenter.close()
    companion object {
        /** CameraCharacteristics.SENSOR_ORIENTATION of [cameraId] (use `session.cameraConfig.cameraId`); 90 if unknown. */
        fun sensorOrientation(context: Context, cameraId: String): Int = try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        } catch (_: Exception) {
            90
        }
    }
}
