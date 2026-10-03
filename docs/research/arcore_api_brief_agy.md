# ARCore SDK 1.45+ (Kotlin / compileSdk 35) API Brief

---

## 1. ARCore Recording & Playback API

1. **Initiating Session Recording with [`Session.startRecording(RecordingConfig)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#startRecording(com.google.ar.core.RecordingConfig))**:  
   To record an AR session, call `session.startRecording(recordingConfig)`. If called before the initial call to `session.resume()`, recording begins automatically when the session resumes; calling it while the session is running records a partial session. If recording cannot be started (e.g., invalid path, write error, or unsupported configuration), it throws [`RecordingFailedException`](https://developers.google.com/ar/reference/java/com/google/ar/core/exceptions/RecordingFailedException).  
   *Source*: [ARCore Recording and Playback Developer Guide](https://developers.google.com/ar/develop/java/recording-and-playback) / [Session Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#startRecording(com.google.ar.core.RecordingConfig))

2. **Destination Target: [`RecordingConfig.setMp4DatasetUri(Uri)`](https://developers.google.com/ar/reference/java/com/google/ar/core/RecordingConfig#setMp4DatasetUri(android.net.Uri)) vs [`setMp4DatasetFilePath(String)`](https://developers.google.com/ar/reference/java/com/google/ar/core/RecordingConfig#setMp4DatasetFilePath(java.lang.String))**:  
   `RecordingConfig.setMp4DatasetFilePath(String)` is **deprecated**. Developers must use `RecordingConfig.setMp4DatasetUri(Uri)` to adhere to Android 10/11+ Scoped Storage requirements (`compileSdk 35`). The provided `Uri` must point to a seekable file/content resource that supports `lseek` (a valid file descriptor); otherwise, an `IllegalArgumentException` is thrown.  
   *Source*: [RecordingConfig Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/RecordingConfig#setMp4DatasetUri(android.net.Uri))

3. **Auto-Stop Configuration via [`RecordingConfig.setAutoStopOnPause(boolean)`](https://developers.google.com/ar/reference/java/com/google/ar/core/RecordingConfig#setAutoStopOnPause(boolean))**:  
   Passing `true` to `setAutoStopOnPause(boolean)` causes ARCore to automatically finalize and close the MP4 recording when `session.pause()` is executed. If set to `false`, recording is suspended upon pause and resumes when `session.resume()` is called.  
   *Source*: [RecordingConfig Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/RecordingConfig#setAutoStopOnPause(boolean))

4. **Stopping Recording with [`Session.stopRecording()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#stopRecording())**:  
   Calling `session.stopRecording()` stops the current MP4 recording and flushes pending video, IMU, and track data to disk. It throws [`RecordingFailedException`](https://developers.google.com/ar/reference/java/com/google/ar/core/exceptions/RecordingFailedException) if the file cannot be cleanly closed.  
   *Source*: [Session Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#stopRecording())

5. **Querying Recording State with [`Session.getRecordingStatus()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#getRecordingStatus())**:  
   Returns a [`RecordingStatus`](https://developers.google.com/ar/reference/java/com/google/ar/core/RecordingStatus) enum:
   - `RecordingStatus.NONE`: No active recording session.
   - `RecordingStatus.OK`: Session is actively recording.
   - `RecordingStatus.IO_ERROR`: Recording failed due to an unrecoverable disk or file descriptor error (e.g., storage full).  
   *Source*: [RecordingStatus Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/RecordingStatus)

6. **Configuring Dataset Playback with [`Session.setPlaybackDatasetUri(Uri)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#setPlaybackDatasetUri(android.net.Uri)) vs [`setPlaybackDatasetFilePath(String)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#setPlaybackDatasetFilePath(java.lang.String))**:  
   `Session.setPlaybackDatasetFilePath(String)` is **deprecated**. Use `Session.setPlaybackDatasetUri(Uri)`. The `Uri` must reference a seekable MP4 recording dataset.  
   *Source*: [Session Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#setPlaybackDatasetUri(android.net.Uri))

7. **The Mandatory Session Paused Rule for Playback**:  
   `Session.setPlaybackDatasetUri(Uri)` **must strictly be invoked while the session is paused**. If called while the session is running, ARCore throws an `IllegalStateException`. The required lifecycle sequence is:
   ```kotlin
   session.pause()
   session.setPlaybackDatasetUri(mp4Uri)
   session.resume()
   ```  
   *Source*: [Record and Play Back an AR Session Guide](https://developers.google.com/ar/develop/java/recording-and-playback)

8. **Querying Playback State with [`Session.getPlaybackStatus()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#getPlaybackStatus())**:  
   Returns a [`PlaybackStatus`](https://developers.google.com/ar/reference/java/com/google/ar/core/PlaybackStatus) enum:
   - `PlaybackStatus.NONE`: No dataset loaded for playback (live camera is active).
   - `PlaybackStatus.OK`: Playback is actively running.
   - `PlaybackStatus.FINISHED`: The dataset has reached the end of the MP4 recording.
   - `PlaybackStatus.IO_ERROR`: Playback failed due to an I/O or file read error.  
   *Source*: [PlaybackStatus Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/PlaybackStatus)

9. **Custom Data Channels via [`Track`](https://developers.google.com/ar/reference/java/com/google/ar/core/Track) and [`TrackData`](https://developers.google.com/ar/reference/java/com/google/ar/core/TrackData)**:  
   Custom app metadata (e.g., raw sensor data or user measurement events) can be multiplexed as discrete tracks inside the MP4 container:
   - *Setup*: Instantiate a [`Track`](https://developers.google.com/ar/reference/java/com/google/ar/core/Track) using `Track(session).setId(UUID).setMimeType(String)` and register it via `RecordingConfig.addTrack(track)`.
   - *Write*: During recording, call [`Frame.recordTrackData(UUID, ByteBuffer)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#recordTrackData(java.util.UUID,java.nio.ByteBuffer)). This requires `session.getRecordingStatus() == RecordingStatus.OK`.
   - *Read*: During playback, call [`Frame.getUpdatedTrackData(UUID)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#getUpdatedTrackData(java.util.UUID)) to retrieve a `Collection<TrackData>`. Extract payloads with `trackData.getData()` (`ByteBuffer`) and synchronization timings with `trackData.getFrameTimestamp()` (nanoseconds).  
   *Source*: [Add Custom Data While Recording Guide](https://developers.google.com/ar/develop/java/recording-and-playback/add-custom-data)

10. **Supported Android Versions and Device Constraints**:  
    - ARCore requires Android 7.0 (API 24) or later (many features require API 26+).
    - Under Scoped Storage on Android 10+ (API 29) and Android 11+ (API 30–35), writing or reading dataset files in shared media requires `Uri`-based APIs or app-specific private storage (`context.getExternalFilesDir(null)`).
    - **Shared Camera Limitation**: While sessions configured with `SharedCamera` can be recorded, **playback of Shared Camera sessions is not supported**.  
    *Source*: [ARCore Recording and Playback Developer Guide](https://developers.google.com/ar/develop/java/recording-and-playback)

11. **Playback Mechanics and Visual Tracking (`hitTest` & Plane Detection)**:  
    **Yes**, both plane detection ([`Plane`](https://developers.google.com/ar/reference/java/com/google/ar/core/Plane)) and hit testing ([`Frame.hitTest(...)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#hitTest(float,float))) function normally during dataset playback.  
    *Mechanism*: The recorded MP4 file contains the synchronized camera video feed, IMU telemetry, and camera metadata. During playback, ARCore processes these frames through its live Visual-Inertial Odometry (VIO) and computer vision pipeline, extracting feature points, detecting surfaces, estimating environmental lighting, and solving hit-test ray intersections against detected planes identically to a live session.  
    *Source*: [ARCore Recording and Playback Overview](https://developers.google.com/ar/develop/recording-and-playback)

---

## 2. ARCore Depth API

12. **Depth Modes via [`Config.DepthMode`](https://developers.google.com/ar/reference/java/com/google/ar/core/Config.DepthMode)**:  
    - `Config.DepthMode.AUTOMATIC`: Calculates a dense depth map for every pixel using Depth-from-Motion and hardware sensors (if present). Enables `DepthPoint` results in `Frame.hitTest()`.
    - `Config.DepthMode.RAW_DEPTH_ONLY`: Produces sparse, unfiltered depth with corresponding confidence maps. Provides lower latency and raw geometric measurements without smoothing over object boundaries.
    - `Config.DepthMode.DISABLED`: Shuts down depth processing to save battery and compute.  
    *Source*: [Config.DepthMode Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Config.DepthMode)

13. **Depth Mode Compatibility Check via [`Session.isDepthModeSupported(Config.DepthMode)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#isDepthModeSupported(com.google.ar.core.Config.DepthMode))**:  
    Before configuring the session, developers must verify device support using `session.isDepthModeSupported(mode)`. If unsupported, setting the mode will cause `session.configure()` to throw [`UnsupportedConfigurationException`](https://developers.google.com/ar/reference/java/com/google/ar/core/exceptions/UnsupportedConfigurationException).  
    *Source*: [Session Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#isDepthModeSupported(com.google.ar.core.Config.DepthMode))

14. **Acquiring Filtered Depth via [`Frame.acquireDepthImage16Bits()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#acquireDepthImage16Bits())**:  
    Retrieves the smoothed, dense depth image for the current frame (requires `AUTOMATIC` depth mode). The returned object is an [`android.media.Image`](https://developer.android.com/reference/android/media/Image) that must be explicitly closed via `Image.close()` to prevent native memory exhaustion. Throws [`NotYetAvailableException`](https://developers.google.com/ar/reference/java/com/google/ar/core/exceptions/NotYetAvailableException) when depth is warming up. This method replaces the legacy `acquireDepthImage()` (which was limited to 13 bits / 8,191 mm).  
    *Source*: [Frame Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#acquireDepthImage16Bits())

15. **Acquiring Raw Depth via [`Frame.acquireRawDepthImage16Bits()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#acquireRawDepthImage16Bits())**:  
    Retrieves the raw, unfiltered depth map (available in `RAW_DEPTH_ONLY` and `AUTOMATIC` modes). It preserves geometric edges and discontinuities better than the smoothed depth map, making it preferable for spatial meshing and room measurement.  
    *Source*: [Frame Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#acquireRawDepthImage16Bits())

16. **Acquiring Raw Confidence via [`Frame.acquireRawDepthConfidenceImage()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#acquireRawDepthConfidenceImage())**:  
    Returns an `android.media.Image` matching the dimensions of the raw depth image in `ImageFormat.Y8` format. Each pixel is an 8-bit unsigned integer ($0$ to $255$) representing estimation certainty ($0$ = no confidence / invalid, $255$ = 100% confidence). Filtering out raw depth pixels with confidence below $60\text{--}80$ is standard practice to eliminate noisy depth readings.  
    *Source*: [Raw Depth Developer Guide](https://developers.google.com/ar/develop/java/depth/raw-depth)

17. **Image Format, Units, and Range**:  
    - **Format**: `android.graphics.ImageFormat.DEPTH16` (`HardwareBuffer.D_16`), consisting of a single channel of 16-bit unsigned integers stored in little-endian byte order.
    - **Units**: **Millimeters** ($1\text{ unit} = 1\text{ mm}$).
    - **Representable Range**: $0\text{ mm}$ to $65,535\text{ mm}$ ($\approx 65.535\text{ m}$).
    - **Effective/Optimal Range**: Google specifies optimal depth accuracy between **$500\text{ mm}$ ($0.5\text{ m}$) and $15,000\text{ mm}$ ($15\text{ m}$)**, with usable observations up to $25\text{ m}$. Sub-$0.5\text{ m}$ readings suffer from severe camera disparity cutoff.  
    *Source*: [ARCore Depth API Overview](https://developers.google.com/ar/develop/java/depth/overview)

18. **Unprojecting Depth Pixels to World Space ([`Camera.getImageIntrinsics()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Camera#getImageIntrinsics()) vs [`Camera.getTextureIntrinsics()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Camera#getTextureIntrinsics()))**:  
    - **The Method Difference**: `Camera.getTextureIntrinsics()` corresponds to the GPU texture coordinates rendered to the viewport (often cropped or aspect-adjusted). `Camera.getImageIntrinsics()` returns the unrotated physical intrinsics of the camera sensor/CPU image buffer. Because depth maps are aligned with the physical camera sensor, **`Camera.getImageIntrinsics()` must be used**.
    - **Resolution Scaling**: The depth image dimensions ($W_d \times H_d$, e.g., $160 \times 120$ or $256 \times 192$) are lower than the CPU color camera stream ($W_c \times H_c$, e.g., $1920 \times 1080$). Scale intrinsics accordingly:
      $$s_x = \frac{W_d}{W_c}, \quad s_y = \frac{H_d}{H_c}$$
      $$f'_x = f_x \cdot s_x, \quad f'_y = f_y \cdot s_y, \quad c'_x = c_x \cdot s_x, \quad c'_y = c_y \cdot s_y$$
    - **Pinhole Camera Back-Projection to Camera Space ($P_c$)**:
      Given depth buffer pixel $(u, v)$ with distance $d$ in millimeters ($Z_c = \frac{d}{1000.0}\text{ meters}$):
      $$X_c = \frac{u - c'_x}{f'_x} \cdot Z_c$$
      $$Y_c = -\frac{v - c'_y}{f'_y} \cdot Z_c \quad \text{(inverting Y to match ARCore's +Y up convention)}$$
      $$Z_c = -Z_c \quad \text{(optical axis points along -Z)}$$
    - **Transform to World Space**:
      Transform camera coordinates using [`Pose.transformPoint()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Pose#transformPoint(float[])):
      ```kotlin
      val cameraPose = frame.camera.pose
      val worldPoint = FloatArray(3)
      cameraPose.transformPoint(floatArrayOf(Xc, Yc, Zc), 0, worldPoint, 0)
      ```  
    *Source*: [ARCore Camera Intrinsics Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Camera#getImageIntrinsics()) / [Google Depth Lab Repository](https://github.com/googlesamples/arcore-depth-lab)

19. **Depth Availability During Recording Playback**:  
    **Yes**, depth is fully available during playback of an MP4 dataset. When playing back a recording on a depth-capable device with `Config.DepthMode.AUTOMATIC` or `RAW_DEPTH_ONLY` enabled, ARCore runs its depth algorithms against the replayed video and sensor streams. Calls to `acquireDepthImage16Bits()`, `acquireRawDepthImage16Bits()`, and `acquireRawDepthConfidenceImage()` produce depth frames synchronized with the playback timeline.  
    *Source*: [ARCore Depth Lab](https://github.com/googlesamples/arcore-depth-lab) / [Recording and Playback Introduction](https://developers.google.com/ar/develop/recording-and-playback)

---

## 3. ARCore Geospatial & Scene Semantics APIs (Indoor Suitability)

20. **ARCore Geospatial API**:  
    The Geospatial API localizes a device globally in WGS84 coordinates (latitude, longitude, altitude, and heading) by pairing visual inertial odometry with Google's Visual Positioning Service (VPS), GPS, and Google Street View 3D mapping data. **It is irrelevant for indoor room measurement.** VPS requires external street-level visual features matching Google Street View imagery, which do not exist inside private buildings. Furthermore, indoor GPS degradation causes positional accuracy to plunge to dozens of meters, which is entirely unusable for sub-centimeter indoor architectural measurement.  
    *Source*: [ARCore Geospatial API Developer Guide](https://developers.google.com/ar/develop/geospatial)

21. **ARCore Scene Semantics API**:  
    Scene Semantics runs an on-device ML model on every frame to assign semantic labels (accessible via [`Frame.acquireSemanticImage()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#acquireSemanticImage())) to image pixels. **It does not matter for indoor room measurement.** Google explicitly documents that Scene Semantics is designed and trained exclusively for outdoor environments (labels include *sky*, *building*, *tree*, *road*, *sidewalk*, *vehicle*, *person*); indoor inferences are unsupported and unstable. Furthermore, the API outputs a low-resolution 2D semantic label mask without 3D geometric coordinates or planar bounding equations, providing no actionable metric utility compared to plane detection or raw depth.  
    *Source*: [ARCore Scene Semantics Developer Guide](https://developers.google.com/ar/develop/java/scene-semantics)

---

## 4. Google Pixel Hardware Depth Sensors vs Depth-from-Motion

22. **Hardware ToF/LiDAR on Google Pixel Phones**:  
    **No Google Pixel smartphone has ever shipped with a rear hardware imaging Time-of-Flight (ToF) camera or LiDAR sensor.**
    - *Pixel 4 / 4 XL*: Included a front-facing dot projector, IR illumination camera pair, and a 60 GHz radar chip (Project Soli) on the front bezel for Face Unlock and gestures. It had **no rear depth camera**.
    - *Pixel 2 through Pixel 9 / 9 Pro*: Feature rear **Laser Detect Auto Focus (LDAF)**. On Pixel 6 through 9 Pro, this is a multi-zone (up to $8 \times 8$) direct ToF ranging sensor (such as the STMicroelectronics VL53L series). However, LDAF is strictly an autofocus distance assist module for the camera HAL. It is **not** an imaging depth sensor and is completely inaccessible to the ARCore Depth API.
    - *Pixel 8 Pro / 9 Pro*: Feature an infrared contactless temperature sensor (thermopile), which has no optical spatial imaging capability.  
    *Source*: [Google Pixel Technical Specifications](https://support.google.com/pixelphone/answer/7158570) / [ARCore Supported Devices](https://developers.google.com/ar/devices)

23. **What ARCore Depth API Relies On on Pixel Hardware**:  
    On Pixel phones (and other devices without dedicated hardware ToF imagers), ARCore relies entirely on **monocular Depth-from-Motion (multi-view stereo vision)** accelerated by an on-device neural network:
    - As the user pans the device, ARCore captures consecutive RGB frames from disparate physical perspectives.
    - It tracks visual feature point displacements across frames, combining optical flow and camera pose from Visual-Inertial Odometry (VIO) to compute geometric disparity/depth hypotheses via triangulation.
    - A deep convolutional network processes these observations to infer dense depth maps and fill textureless regions.  
    *Source*: [Google Research: Lighting Up the ARCore Depth API](https://blog.google/products/google-ar-vr/depth-api/) / [ARCore Depth API Overview](https://developers.google.com/ar/develop/java/depth/overview)

---

## 5. Known Gotchas & Precision Constraints in Room Measurement

24. **Tracking Drift, VIO Accumulation, and 10 m Baselines**:  
    ARCore relies on Visual-Inertial Odometry (VIO) integrating optical camera feature displacement with IMU accelerometer and gyroscope integrations. Over a $10\text{ m}$ room baseline, double-integrated IMU acceleration biases accumulate unbounded drift. When navigating large rooms, textureless surfaces (smooth drywall, monochrome paint), reflective surfaces (windows, glossy tiles), and repetitive patterns break feature tracking, resulting in `TrackingFailureReason.INSUFFICIENT_FEATURES` or `EXCESSIVE_MOTION`.  
    *Source*: [ARCore Tracking State Guide](https://developers.google.com/ar/develop/java/tracking-state)

25. **The Coordinate Shift Trap (Why Anchors Are Mandatory)**:  
    Measuring distances by recording two static camera poses in world coordinates (`Pose.translation()`) without anchors is an architectural failure. ARCore constantly refines its internal spatial map via bundle adjustment and loop closure. As new geometric constraints are solved, the world coordinate origin shifts dynamically. Measurement endpoints **must be anchored** via [`Trackable.createAnchor(Pose)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Trackable#createAnchor(com.google.ar.core.Pose)) or [`Session.createAnchor(Pose)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#createAnchor(com.google.ar.core.Pose)). Anchors are actively transformed by ARCore's backend to remain fixed to the physical space as map corrections occur.  
    *Source*: [ARCore Working with Anchors Guide](https://developers.google.com/ar/develop/java/anchors)

26. **Typical Empirical Accuracy Numbers (Peer-Reviewed Benchmarks)**:  
    - *Feigl et al. (2020)*, ["Benchmarking Built-In Tracking Systems for Indoor AR Applications on Popular Mobile Devices"](https://doi.org/10.3390/s20195596), *MDPI Sensors*: Evaluated ARCore against sub-millimeter OptiTrack motion capture ground truth. Across standard indoor trajectories, ARCore achieved a mean absolute translational error of **$0.187\text{ m}$ ($18.7\text{ cm}$)**. Over short, static distances under optimal lighting and texture, errors remained in the **$1\text{ to }3\text{ cm}$** range.
    - *Hübner et al. (2020)*, ["Localization Limitations of ARCore, ARKit, and HoloLens in Dynamic Large-scale Industry Environments"](https://doi.org/10.5220/0009169603890400), *SCITEPRESS*: Quantified cumulative scale drift, reporting scaling errors of up to **$14.4\text{ cm/m}$** and absolute drift of up to **$17\text{ m}$ over a $120\text{ m}$ path** in industrial spaces.  
    *Takeaway for Room Measurement*: Over a $10\text{ m}$ indoor room, users should expect a minimum of $\mathbf{5\text{ to }20\text{ cm}}$ of drift error unless re-localized or calibrated against known reference markers.

27. **Plane Finding Configuration via [`Config.setPlaneFindingMode(Config.PlaneFindingMode)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Config#setPlaneFindingMode(com.google.ar.core.Config.PlaneFindingMode))**:  
    - Modes: `DISABLED`, `HORIZONTAL`, `VERTICAL`, and `HORIZONTAL_AND_VERTICAL`.
    - **Gotcha**: Many ARCore sample applications default to `HORIZONTAL`. For room measurement (measuring walls, door frames, or ceiling bounds), developers **must explicitly configure `Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL`**.
    - If left on `HORIZONTAL`, vertical walls will never be instantiated as [`Plane`](https://developers.google.com/ar/reference/java/com/google/ar/core/Plane) objects, causing wall hit tests to fail or fall back to noisy feature points. Vertical plane detection also incurs higher compute latency and requires sweeping translational motion across the wall surface.  
    *Source*: [Config.PlaneFindingMode Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Config.PlaneFindingMode)

28. **Focus Mode Distortions via [`Config.setFocusMode(Config.FocusMode)`](https://developers.google.com/ar/reference/java/com/google/ar/core/Config#setFocusMode(com.google.ar.core.Config.FocusMode))**:  
    - Modes: `Config.FocusMode.FIXED` vs `Config.FocusMode.AUTO`.
    - **The Lens Breathing Gotcha**: In `AUTO` focus mode, the camera's voice coil motor physically shifts optical elements to achieve focus. This mechanical actuation causes **lens breathing**, which dynamically alters the camera's effective focal length ($f_x, f_y$) and principal point ($c_x, c_y$).
    - ARCore's visual SLAM pipeline assumes calibrated, static pinhole camera intrinsics. Dynamic focal length variations in `AUTO` focus mode corrupt metric scale calculation, causing artificial scale expansion/contraction, coordinate jitter, and drift.
    - **Rule**: High-precision room measurement applications must explicitly enforce `Config.FocusMode.FIXED` via `config.setFocusMode(Config.FocusMode.FIXED)` prior to session configuration.  
    *Source*: [Config.FocusMode Reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Config.FocusMode)

---

## 6. Facts That Could Not Be Verified

1. **Exact Hardware Identification of the Multi-Zone LDAF Sensor in the Pixel 9 Pro**:  
   While confirmed to be a multi-zone STMicroelectronics direct-ToF distance ranging sensor (laser autofocus) rather than an imaging depth camera, Google does not publicly release the component part number (e.g., whether it is an STM VL53L5CX or a proprietary variant) in developer documentation.
2. **Deterministic Depth Image Latency During Playback Across Varied Host SoCs**:  
   Google documents that `NotYetAvailableException` can be thrown by `Frame.acquireDepthImage16Bits()` during playback until frames warm up, but provides no guaranteed millisecond latency specification for when the first depth buffer becomes available during dataset playback across different mobile chipsets.
