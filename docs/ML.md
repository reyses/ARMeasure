# ML in ARMeasure: tap-to-box proposals and primitive shape recognition

Package `com.example.arruler.ml`. Everything below runs on the phone. No image, point or measurement leaves the device
because of this feature.

## What runs where

| Piece | File | What it does | Runs |
|---|---|---|---|
| 2D proposals | `ObjectDetector.kt` (`MlObjectDetector`) | ML Kit **Subject Segmentation** (Google Play services build) on the ARCore CPU image; returns subject boxes in sensor IMAGE_PIXELS | on device, Play services module |
| Orientation / YUV | `ImageOrientation.kt` | display + sensor rotation math, YUV_420_888 to NV21 | plain Kotlin |
| Tap selection + 3D box | `TapToBox.kt` | tap to detection, depth points inside the 2D box and above the plane, objscan isolation + oriented footprint, aligned `ObjectBox` with 1 cm padding | plain Kotlin |
| Primitive fits | `PrimitiveFit.kt` | least-squares BOX / CYLINDER / SPHERE / CONE with parameters, formula volume, RMS score | plain Kotlin |
| The classifier | `ShapeClassifier.kt` | softmax regression, 10 features, 44 hard-coded weights | plain Kotlin, microseconds |
| Combination | `ShapeRecognizer.kt` | label from the classifier, parameters from the fit of that label, UNKNOWN gate, result-card text | plain Kotlin |
| Adapter | `MlAutoBoxGlue.kt` | the ONLY file that touches the future `AutoBoxProvider` | Android |

### Library choice and size (measured, phone APK, `-PphoneOnly` debug, clean builds)

| Variant | APK bytes | delta |
|---|---|---|
| Baseline (this branch, no ML Kit) | 27,986,247 | 0 |
| `com.google.mlkit:object-detection:17.0.2` (bundled) | 36,197,277 | **+8,211,030** (libmlkitcommonpipeline.so 4.7 MB compressed + two tflite models 3.0 MB + dex) |
| `com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1` (**shipped**) | 28,098,767 | **+112,520** |

The bundled Object Detection would put the phone APK at 34.5 MiB, over the 30 MiB budget. Google publishes no Play-services
build of Object Detection (checked on Google Maven: only barcode, document scanner, face, image labeling, language id, smart reply,
subject segmentation, text recognition), so the unbundled route is Subject Segmentation. Consequences:

* it is **beta** (16.0.0-beta1 is the newest release; there is no stable one),
* it returns salient subjects with a box, **no class label** (`Detection.label` is null; the shape label comes from our classifier),
* Play services downloads the module once (a few MB, not counted in the APK). Call `MlObjectDetector.warmUp()` when the object screen opens, or add to
  the manifest `<application>` so the download happens at install time:
  `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="subject_segment" />`.
  This is the only network use; inference itself is offline.

If the 8.2 MB is acceptable later, swap `MlObjectDetector.detect()` for the bundled detector (labels + confidence, classification on):

```kotlin
// implementation 'com.google.mlkit:object-detection:17.0.2'
private val detector = ObjectDetection.getClient(ObjectDetectorOptions.Builder()
    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE).enableMultipleObjects().enableClassification().build())
// in detect(): detector.process(input) -> List<DetectedObject>; o.boundingBox is in the upright image, map it with
// ImageOrientation.uprightBoxToImage(...) exactly as the subject boxes are; label = o.labels.maxByOrNull { it.confidence }
```

### Dependency edits (exact)

`gradle/libs.versions.toml`:
```
[versions]   + mlkitSubjectSegmentation = "16.0.0-beta1"
[libraries]  + play-services-mlkit-subject-segmentation = { group = "com.google.android.gms", name = "play-services-mlkit-subject-segmentation", version.ref = "mlkitSubjectSegmentation" }
```
`app/build.gradle` (dependencies, after the code scanner):
```
implementation libs.play.services.mlkit.subject.segmentation
```

## Privacy

All images are processed in memory by the on-device model. Nothing from this feature is uploaded: the camera frame is copied to an NV21 array,
handed to ML Kit, and dropped. ML Kit may send anonymous usage statistics through its own telemetry unless the app disables it; this repo does not enable
or read it. The only network traffic caused by the library is Play services fetching the segmentation module.

## Camera geometry (the math in `ImageOrientation`)

ARCore's CPU image (`Frame.acquireCameraImage()`, YUV_420_888) is in sensor orientation, not display orientation. ML Kit takes the clockwise
rotation that makes it upright for the display and returns boxes in that upright image:

```
rotation = (sensorOrientation - displayRotationDegrees + 360) % 360     (back camera)
```
`sensorOrientation` = `CameraCharacteristics.SENSOR_ORIENTATION` of `session.cameraConfig.cameraId` (90 on nearly every phone; `MlObjectDetector.sensorOrientation`),
`displayRotationDegrees` = `Display.getRotation()` as 0/90/180/270. Portrait: 90. Landscape (ROTATION_90): 0. ROTATION_270: 180.
Boxes are mapped back to sensor pixels by `uprightToImage` (e.g. rot 90: `(x, y) -> (y, H - x)`), so they live in the same space as the tap after
`Frame.transformCoordinates2d(Coordinates2d.VIEW, ..., Coordinates2d.IMAGE_PIXELS, ...)` and as `Camera.getImageIntrinsics()`
(enum names verified with javap on core-1.56.0: `VIEW`, `IMAGE_PIXELS`). Depth points are projected into that same image with the sensor-aligned camera pose and
the image intrinsics (`CameraProjector`). The image is copied to NV21 and the `Image` closed in a `finally` before any asynchronous work.

## Tap to box

1. `TapToBox.pick`: the detection whose box contains the tap (smallest if nested), else the nearest within 15 % of the image diagonal, else null.
2. `TapToBox.fitBox`: keep depth points that project inside the 2D box and are more than 12 mm above the plane; median camera range of the points in the middle
   40 % of the box is the object's range; keep points within 35 cm of it, then only the depth cluster around it (sorted ranges split at gaps over 4 cm: the
   silhouette edge to the wall behind). `ObjectIsolation` (outliers, largest component), `ObjectMeasures` (min-area oriented rectangle, height).
   Result: `ObjectBox` with yaw = -(footprint angle), long side as width, 1 cm padding on every side and on top, base on the plane. `TapBox.shape` carries the recognition.

## Shape recognition

### PrimitiveFit (pure least squares)

Points are (x, z, h), h = height above the horizontal support plane. The underside is never charged to any model.

* BOX: min-area rectangle of the footprint hull for the orientation; each side then placed at a flat-face estimate (2 mm histogram mode near the 0.5 %/99.5 %
  quantile, mean within 5 mm: the plain quantile sits about 2 sigma outside a noisy face); height the same way. Distance to the five faces.
* CYLINDER: vertical axis. Kasa algebraic circle fit on the wall points (top 15 mm excluded), Gauss-Newton on the radial residual. Distance in the meridian plane to the wall and the top disc.
* SPHERE: linear least squares on x^2+y^2+z^2+Dx+Ey+Fz+G=0, Gauss-Newton on |p-c|-r.
* CONE: vertical axis, radius a + b h, Gauss-Newton on (cx, cz, a, b) from the Kasa circle of the lowest wall points; apex at -a/b if within 12 % of the top, else a frustum.
  The distance to a surface of revolution is the exact 2D distance to its profile, so cylinder and cone share one routine.
* score = RMS distance / size + 0.002 per size parameter (box 3, cylinder 2, sphere 1, cone 3), size = max(footprint length, height). Unitless; lower is better.
* `PrimitiveFit.decide`: softmax(-score / 0.01) gives the fit-only label and confidence (temperature = half a noise floor of a 20 cm object, 4 mm / 200 mm = 0.02).
* UNKNOWN when (RMS of the best fit - 5 mm) / size > 0.04. The 5 mm is the typical denoised ARCore residual, subtracted so small objects are not rejected for noise alone.
  0.04 was set on the tests: correct primitives at 2-6 mm noise stay under about 0.02 excess, three-sphere blobs sit between 0.026 and 0.077.

### ShapeClassifier (the bit of ML)

Multinomial logistic regression over 10 features, 4 classes, 44 numbers (mean/std standardisation included). Features (all dimensionless):
normalised RMS of the BOX, CYLINDER, SPHERE and CONE fits; footprint circularity 4 pi A / P^2 (circle 1, square 0.785); short/long footprint side;
ln(height / footprint length); cone-fit radius slope d(radius)/d(height); top-slab / bottom-slab hull-area ratio (top 20 % of height over lowest 25 %); |sphere centre height - radius| / size.

Training (`app/src/test/.../ml/ShapeTrainer.kt`, `ShapeSynth.kt`; run by removing `@Ignore` from `ShapeClassifierTest.trainer`, which prints a block to paste between
`BEGIN TRAINED` / `END TRAINED` in `ShapeClassifier.kt`): 1,500 synthetic shapes per class, sizes 5-60 cm (each dimension), per-axis Gaussian noise 2-6 mm,
random yaw and position, underside never sampled, points under 8 mm dropped like the isolation margin, 35 % of samples with a missing azimuth wedge of 30-120 degrees (unseen side).
Adam, 4,000 epochs, lr 0.03, L2 1e-4, features standardised on the training set.

The final label is the classifier's; the parameters and formula volume are the fit of that label. UNKNOWN replaces both when the gate trips.

### Accuracy (synthetic, never seen at training: different seeds)

Held-out 1,000 shapes (250 per class, seed 777, the unit test): **99.9 %**.

| true \ predicted | BOX | CYLINDER | SPHERE | CONE |
|---|---|---|---|---|
| BOX | 249 | 1 | 0 | 0 |
| CYLINDER | 0 | 250 | 0 | 0 |
| SPHERE | 0 | 0 | 250 | 0 |
| CONE | 0 | 0 | 0 | 250 |

Trainer's own held-out 2,000 (seed 99): 99.9 % (2 boxes read as cylinders). Pure PrimitiveFit score softmax with no learning: 98.75 % on the same 2,000, so the learned
part buys about 1.2 points. End to end with the UNKNOWN gate on 400 fresh shapes: 399 correct, 0 false UNKNOWN. Three-sphere blobs: 27 of 30 UNKNOWN (3 read as the nearest primitive).
Recovery at 4 mm noise on 200 mm objects (6 seeds each): dimensions within 6 mm and formula volume within 6 % (unit tests).

Caveat: this is accuracy on SYNTHETIC clean primitives. Real depth has structured error (holes, multipath, plane tilt), real objects are rounded boxes, mugs with handles, bottles. Expect lower real numbers; the UNKNOWN gate is the safety valve. A device pass is needed to measure them.

## Glue

### AutoBoxProvider

`objscan/AutoBox.kt` is being defined elsewhere (`suspend fun propose(tapX: Float, tapY: Float, frameContext: ...): ObjectBox?`). Adapt in one place:

```kotlin
class MlAutoBoxProvider(private val ml: MlAutoBox) : AutoBoxProvider {
    override suspend fun propose(tapX: Float, tapY: Float, frameContext: FrameContext): ObjectBox? =
        ml.proposeBox(tapX, tapY, frameContext.toMlFrameInput())      // FrameContext is whatever AutoBox.kt defines
}
```
`toMlFrameInput()` is `captureMlFrame(frame, detector, depthSampler.sample(frame), plane, display.rotation, sensorOrientation)` run on the frame callback thread when the
tap arrives (the `Frame` is invalid afterwards, which is why `MlFrameInput` copies everything). Cache `MlObjectDetector.sensorOrientation(context, session.cameraConfig.cameraId)`.
If the context type already carries the world depth points, the plane and the pose/intrinsics, build `MlFrameInput` directly (capture, `viewToImage`, `worldPoints`, `projector = CameraProjector(poseMatrix, intrinsics)`, `plane`).
Need the full result rather than just the box (for the shape line): call `ml.propose(...)` which returns `TapBox` (`box`, `shape`, `measures`).

### Where the shape goes in the result card

`MainActivity.ObjectResultCard` builds `ObjectCardText.lines(units, r.outcome)`. Run `ShapeRecognizer.recognize(ObjectScanOutput.isolated, plane)` next to
`ObjectPipeline.run` (the isolated points are already there) and carry the `ShapeRecognition` in `ObjectResultState`; then prepend one line:

```kotlin
r.shape?.let { lines += "Shape" to it.describe() }
// e.g. Looks like a cylinder (93 %) — r 9.8 cm, h 20.1 cm, formula volume 0.00605 m³
// UNKNOWN:  No simple shape fits this object
```
`describe()` prints centimetres and cubic metres; for the user's units swap `cm()` for `ShapeFormat.length(units, ...)` and `ShapeFormat.volume(units, ...)`.

## Device-only checks (not covered by the JVM tests)

* Play-services module download on first run, behaviour offline before it completes (`warmUp()` throws; `detect()` fails).
* That Subject Segmentation boxes follow the object under the tap on real scenes; that `enableConfidenceMask()` is not needed for boxes (it is on to be safe, costs a little time).
* The rotation formula on a real phone in all four display rotations (JVM tests cover the algebra, not the sensor orientation of a given device).
* NV21 conversion speed at the CPU image size and the end-to-end tap latency.
* Single-frame depth density for `TapToBox.fitBox` (it takes one frame; for a thin object accumulate several frames into one world array first).
* Real-object recognition accuracy and the UNKNOWN threshold (0.04) on real scans.
