# Technical Research Brief: ARCore Room Measurement, DJI Mini 3 Pro Integration, and Photogrammetry Pipeline

---

## PART A — DJI Mini 3 Pro Integration

### 1. DJI Mobile SDK v5 (MSDK v5) Aircraft & Controller Compatibility

* **Aircraft Support Status:**  
  The DJI Mini 3 Pro is **officially supported** by DJI Mobile SDK v5 for Android ([DJI MSDK v5 Supported Products](https://developer.dji.com/mobile-sdk/)).
* **Version Introduced:**  
  Support was introduced in **MSDK v5.3.0** in **April 2023** ([DJI Mobile SDK v5 Release Notes](https://developer.dji.com/mobile-sdk/)).
* **Latest Version & Release Date:**  
  The latest stable release is **MSDK v5.18.0**, released on **May 22, 2026** ([DJI Developer Android MSDK Release Announcement](https://developer.dji.com/doc/mobile-sdk-android/en/release-notes/)).
* **Remote Controller Compatibility (DJI RC vs. RC-N1):**
  * **DJI RC (Built-in Screen, Model RM330):** **NOT SUPPORTED.** The standard DJI RC runs a locked-down, 32-bit Android operating system (Cortex-A53 / `armeabi-v7a`) without Google Play Services or sideloading capabilities. It does not allow third-party APK installation and does not provide USB host accessory routing to an external phone ([Dronelink DJI RC Hardware FAQ](https://support.dronelink.com/hc/en-us/articles/5188825859731-DJI-RC-Remote-Controller-Compatibility)). The phone is completely out of the loop.
  * **DJI RC-N1 (Phone Mount Remote):** **SUPPORTED.** The host phone connects via USB-C running your Android app containing MSDK v5 ([DJI MSDK Supported Hardware List](https://developer.dji.com/mobile-sdk/)).
  * **DJI RC Pro (High-End Screen Remote, Model RM510):** **SUPPORTED.** Sideloads 64-bit Android APKs directly ([DJI MSDK v5 Aircraft Tutorial](https://developer.dji.com/doc/mobile-sdk-tutorial/en/)).

---

### 2. Capabilities Exposed by MSDK v5 for Mini 3 Pro

| Capability | Supported? | Implementation & API Reference |
| :--- | :---: | :--- |
| **Live Video Stream (H.264/H.265 Raw Stream & Decoders)** | **YES** | Exposed through `MediaDataCenter.getInstance().getVideoStreamManager()`. Raw video frames are delivered via `VideoChannelType` listeners. DJI provides hardware/software decoding wrappers via `IVideoDecoder` and `SurfaceView`/`TextureView` renderers ([DJI MSDK Video Stream Documentation](https://developer.dji.com/doc/mobile-sdk-tutorial/en/)). |
| **Camera Control (Photo, Interval, Gimbal Pitch)** | **YES** | Managed via `KeyManager` and `CameraKey`: shoot photo (`CameraKey.KeyStartShootPhoto`), interval shooting (`CameraKey.KeyCameraShootPhotoMode`), and gimbal pitch rotation via `GimbalKey.KeyRotateByAngle` or `GimbalManager` ([DJI MSDK KeyManager Reference](https://developer.dji.com/doc/mobile-sdk-tutorial/en/)). |
| **Flight Telemetry (GPS, Altitude, Attitude, Velocity)** | **YES** | Polled or subscribed via `FlightControllerKey`: `KeyAircraftLocation` (lat/lon), `KeyAltitude` (barometric/relative), `KeyAttitude` (pitch, roll, yaw), and `KeyVelocity` (vx, vy, vz in m/s) ([DJI MSDK FlightController Keys](https://developer.dji.com/doc/mobile-sdk-tutorial/en/)). |
| **Photo Metadata (XMP: GimbalPitch/Yaw, RelativeAltitude, Intrinsics)** | **YES** | Embedded directly into JPEG/DNG EXIF and XMP tags by aircraft firmware: `drone-dji:GimbalPitchDegree`, `drone-dji:GimbalYawDegree`, `drone-dji:RelativeAltitude`, `drone-dji:AbsoluteAltitude`, and `drone-dji:DewarpData` (lens distortion parameters) ([ExifTool DJI XMP Tag Specification](https://exiftool.org/TagNames/DJI.html)). |
| **Media Download from Aircraft SD Card** | **YES** | Handled via `MediaDataCenter.getInstance().getMediaManager()`. Exposes file list queries, thumbnail caching, and full original payload download over Wi-Fi / OcuSync pipeline ([DJI MSDK MediaManager Guide](https://developer.dji.com/doc/mobile-sdk-tutorial/en/)). |
| **Waypoint / Mission Support (WaylineMission / KMZ)** | **NO** | **Explicitly NO.** The Mini 3 Pro firmware does not support native onboard KMZ/Wayline mission execution (`WaylineExecutingManager` / `WPMZManager` returns unsupported / error code `-10` on consumer drones). Flight automation on Mini 3 Pro requires continuous streaming of **Virtual Stick** commands at 10–20 Hz via `VirtualStickManager` ([DJI MSDK GitHub Issue #809 / Wayline Discussion](https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues)). |

---

### 3. Developer Requirements, Gradle/AGP Constraints, and SDK Overhead

* **Developer Account & App Key Registration:**  
  You must register as a developer at [DJI Developer Portal](https://developer.dji.com/), create an Application Key, and bind it to your application's exact `applicationId` (package name). The key must be specified in `AndroidManifest.xml` (`com.dji.sdk.API_KEY`). On initial launch, the SDK performs an online licensing check against DJI servers ([DJI Developer App Registration](https://developer.dji.com/user/apps/)).
* **Android SDK Ceilings & Floors:**
  * **`minSdk`:** `24` (Android 7.0) minimum ([DJI MSDK v5 Android Setup](https://developer.dji.com/doc/mobile-sdk-tutorial/en/)).
  * **`targetSdk`:** Targeted and validated up to `API 34/35` in MSDK v5.18.0 ([DJI Mobile SDK v5 Release Notes](https://developer.dji.com/mobile-sdk/)).
  * **`compileSdk 37` Collision:** Your app's `compileSdk 37` causes build errors with older MSDK releases. In MSDK v5.18.0, 16 KB memory page support was introduced, but running D8/R8 on AGP 8.7+ with `compileSdk 37` triggers bytecode verification warnings (`VerifyError` on `SDKManager`). You must configure R8 `dontwarn dji.**` and disable R8 full mode (`android.enableR8.fullMode=false`) in `gradle.properties` ([DJI MSDK GitHub Build Issues](https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues)).
* **ABI Architecture:**  
  Strictly **`arm64-v8a` only** ([DJI MSDK Architecture Notes](https://developer.dji.com/doc/mobile-sdk-tutorial/en/)). 32-bit (`armeabi-v7a`) is unsupported in modern MSDK v5 releases, and x86/x86_64 emulators cannot load the proprietary native `.so` binaries.
* **SDK Size Impact:**  
  The core dependency `com.dji:dji-sdk-v5-aircraft:5.18.0` bundles extensive native libraries (`libdjisdk.so`, FFmpeg decoders, CSpectrum, etc.). It adds **~65 MB to ~85 MB** uncompressed to your final APK when filtered strictly to `arm64-v8a` ([Maven Central dji-sdk-v5-aircraft Artifact Repository](https://mvnrepository.com/artifact/com.dji/dji-sdk-v5-aircraft)).

---

### 4. Legal and Safety Constraints (US FAA Regulations)

* **FAA Part 107 vs. Recreational (49 U.S.C. § 44809 / TRUST):**
  * If the app is used solely for recreational enjoyment, testing, or personal education, it falls under the Exception for Limited Recreational Operations (requiring passing the FAA TRUST test) ([FAA Recreational Flyer Guidelines](https://www.faa.gov/uas/recreational_flyers)).
  * If the room-measurement app or photogrammetry output is sold, used for commercial real estate inspection, construction estimating, or in furtherance of any business, operations must strictly follow **FAA Part 107**, requiring a certified Remote Pilot License, commercial registration, and airspace authorization via LAANC ([FAA Part 107 Regulations](https://www.faa.gov/uas/commercial_operators)).
* **Remote ID for Mini 3 Pro at 249 g:**
  * **Standard Intelligent Flight Battery (< 249 g):** Recreational flights with total takeoff weight under 250 g are exempt from registration and Remote ID broadcast requirements. DJI firmware **does not broadcast Remote ID** when the standard battery is detected ([FAA Remote ID Rule Summary](https://www.faa.gov/uas/getting_started/remote_id)).  
    *Exception:* If flown under **Part 107**, registration is mandatory regardless of weight, requiring an external Remote ID broadcast module because the internal broadcast is deactivated with the standard battery.
  * **Intelligent Flight Battery Plus (> 249 g):** Takeoff weight increases to ~290 g. Exceeding 250 g automatically triggers FAA registration requirements and causes the drone’s firmware to **automatically activate built-in Remote ID broadcast** ([FAA Remote ID Declaration of Compliance Database](https://uasdoc.faa.gov/listDocs)).
* **DJI GEO Zones & In-App Unlocking:**  
  MSDK v5 includes the `FlyZoneManager` API. MSDK apps can query local GEO zones (Authorization, Warning, Restricted) and apply custom unlock certificates (`FlyZoneManager.getInstance().unlockFlyZone(...)` and `loadCustomUnlockZones()`) provided the user's DJI account holds approved unlock licenses ([DJI FlyZoneManager Documentation](https://developer.dji.com/doc/mobile-sdk-tutorial/en/)).

---

### 5. Automation Alternatives & Third-Party App Compatibility (2026)

* **DJI Fly Waypoints:**  
  **Not available.** DJI never added native Waypoint Mission planning to the Mini 3 Pro in the DJI Fly app (it was restricted to Hyperlapse waypoints). Native flight waypoints in DJI Fly were reserved for the Mini 4 Pro, Air 3, and Mavic 3 series ([DJI Fly Product Specifications](https://www.dji.com/mini-3-pro)).
* **Dronelink:**  
  **SUPPORTS Mini 3 Pro.** Operates via MSDK v5 using the DJI RC-N1 paired with a 64-bit Android phone, or the DJI RC Pro. It executes waypoint and photogrammetry grid missions by synthesizing Virtual Stick velocity/attitude commands in real time ([Dronelink Mini 3 Pro Support Documentation](https://support.dronelink.com/hc/en-us/articles/5188825859731)).
* **Litchi:**  
  The legacy Litchi app does not support Mini 3 Pro. However, **Litchi Pilot** (beta, Android-only) supports the Mini 3 Pro via MSDK v5 on RC-N1 / RC Pro ([Litchi Pilot Beta Program](https://flylitchi.com/)).
* **DroneDeploy:**  
  **DOES NOT SUPPORT** automated flight on the Mini 3 Pro. DroneDeploy only supports the Mini 4 Pro for autonomous flight; Mini 3 Pro users must manually fly and upload images for post-flight processing ([DroneDeploy Supported Drones Matrix](https://support.dronedeploy.com/hc/en-us/articles/1500004860641-Supported-Drones)).
* **Pix4Dcapture:**  
  **DISCONTINUED.** PIX4Dcapture Pro has been retired and removed from app stores; PIX4D now relies on third-party flight controllers or hardware-agnostic image imports ([Pix4Dcapture Support Deprecation Notice](https://support.pix4d.com/)).

---

## PART B — Photogrammetry Pipeline (Phone Capture + PC Reconstruction)

### 6. COLMAP: Version, Windows CUDA, Prior Poses, and Speedup

* **Current Stable Version:**  
  **COLMAP 4.2.1**, released on **September 29, 2026** ([COLMAP Official Changelog](https://colmap.github.io/changelog.html)).
* **Windows CUDA Requirements:**  
  Requires an NVIDIA GPU with CUDA Compute Capability $\ge 3.5$ (Compute Capability 6.0+ recommended: Pascal, Turing, Ampere, Ada Lovelace, Blackwell). Official Windows releases bundle CUDA 11.8 or CUDA 12.x runtimes. CUDA is mandatory for dense multi-view stereo (`colmap patch_match_stereo`) and GPU-accelerated feature extraction/matching ([COLMAP Installation Guide](https://colmap.github.io/install.html)).
* **Feeding Known ARCore Poses (`point_triangulator`):**  
  Instead of running incremental Structure-from-Motion (`colmap mapper`), construct a manual sparse model folder containing three files ([COLMAP Format Documentation](https://colmap.github.io/format.html)):
  1. `cameras.txt`:
     ```text
     # CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]
     1 PINHOLE 1920 1080 1520.5 1520.5 960.0 540.0
     ```
  2. `images.txt`:
     Each image has two lines. The second line (feature point correspondences) is left blank:
     ```text
     # IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME
     1 0.999 0.001 -0.002 0.005 0.12 -0.34 1.25 1 image_0001.jpg

     2 0.998 0.002 -0.003 0.008 0.18 -0.32 1.30 1 image_0002.jpg

     ```
  3. `points3D.txt`: Leave completely empty (0 bytes).
  
  **Coordinate System Conversion:**  
  ARCore uses OpenGL camera coordinates (+X right, +Y up, -Z camera forward) and provides Camera-to-World poses $T_{wc} = [R_{wc} \mid t_{wc}]$.  
  COLMAP requires World-to-Camera poses $T_{cw} = [R_{cw} \mid t_{cw}]$ with computer vision coordinates (+X right, +Y down, +Z forward):
  $$C = \begin{bmatrix} 1 & 0 & 0 \\ 0 & -1 & 0 \\ 0 & 0 & -1 \end{bmatrix}, \quad R_{cw} = C \cdot R_{wc}^T, \quad t_{cw} = -R_{cw} \cdot t_{wc}$$
  Quaternions in `images.txt` use Hamilton notation with scalar first: $[q_w, q_x, q_y, q_z]$.
  
  **Triangulation Command:**
  ```cmd
  colmap point_triangulator ^
      --database_path database.db ^
      --image_path images ^
      --input_path manual_sparse ^
      --output_path triangulated_sparse
  ```
* **Expected Speed Gain:**  
  Bypassing incremental SfM (`colmap mapper`) eliminates iterative non-linear bundle adjustment, resectioning, and outlier filtering. For a 200-image dataset, incremental SfM typically takes **15 to 45 minutes** on a consumer PC; `point_triangulator` executes in **10 to 45 seconds**—an **order-of-magnitude (15x–40x) speedup** ([COLMAP Tutorial](https://colmap.github.io/tutorial.html)).

---

### 7. OpenMVS: Current Version & Complete Command Sequence on Windows

* **Current Stable Version:**  
  **OpenMVS v2.4.0**, released on **January 20, 2026** ([OpenMVS GitHub Releases](https://github.com/cdcseacave/openMVS/releases)).
* **Windows Execution Pipeline (COLMAP $\rightarrow$ OpenMVS $\rightarrow$ Textured Mesh):**

```cmd
:: Step 1: Undistort COLMAP sparse reconstruction for OpenMVS
colmap image_undistorter ^
    --image_path %PROJECT_DIR%\images ^
    --input_path %PROJECT_DIR%\triangulated_sparse ^
    --output_path %PROJECT_DIR%\dense ^
    --output_type COLMAP

:: Step 2: Convert COLMAP model to OpenMVS project format (.mvs)
InterfaceCOLMAP.exe ^
    -i %PROJECT_DIR%\dense ^
    -o %PROJECT_DIR%\dense\scene.mvs ^
    --image-folder %PROJECT_DIR%\dense\images

:: Step 3: Dense Point Cloud Reconstruction (PatchMatch MVS)
DensifyPointCloud.exe ^
    -i %PROJECT_DIR%\dense\scene.mvs ^
    -o %PROJECT_DIR%\dense\scene_dense.mvs ^
    --resolution-level 1

:: Step 4: Reconstruct 3D Surface Mesh (Delaunay Tetrahedralization)
ReconstructMesh.exe ^
    -i %PROJECT_DIR%\dense\scene_dense.mvs ^
    -o %PROJECT_DIR%\dense\scene_mesh.mvs

:: Step 5: Refine Mesh Surface Details
RefineMesh.exe ^
    -i %PROJECT_DIR%\dense\scene_mesh.mvs ^
    -o %PROJECT_DIR%\dense\scene_mesh_refine.mvs ^
    --resolution-level 1

:: Step 6: Texture the Refined Mesh to export OBJ + PNG texture map
TextureMesh.exe ^
    -i %PROJECT_DIR%\dense\scene_mesh_refine.mvs ^
    -o %PROJECT_DIR%\dense\model_textured.obj ^
    --export-type obj
```
*(Reference: [OpenMVS Documentation Pipeline](https://github.com/cdcseacave/openMVS/wiki/Usage)).*

---

### 8. Meshroom (AliceVision): Current Version & Prior Poses

* **Current Stable Version:**  
  **Meshroom 2025.1.0**, released on **August 18, 2025** ([AliceVision Meshroom Releases](https://github.com/alicevision/meshroom/releases)).
* **Accepting Prior Camera Poses:**  
  **Yes.** Meshroom supports prior camera poses via its modular node graph:
  1. Use the **`ImportKnownPoses`** node or inject poses directly into an AliceVision `.sfm` JSON file (`sfmData`).
  2. Connect the output of `ImportKnownPoses` to the `StructureFromMotion` node.
  3. In the `StructureFromMotion` node parameters, set **`lockSceneProperties` = True** and **`forceLockAllIntrinsics` = True** to prevent bundle adjustment from modifying fixed ARCore coordinates ([AliceVision Meshroom Manual: Using Known Camera Positions](https://meshroom-manual.readthedocs.io/en/latest/)).

---

### 9. 3D Gaussian Splatting / NeRF Options (Windows 2026), Metric Mesh Export, and Indoor Accuracy

* **Viable Single Consumer NVIDIA GPU Options on Windows:**
  * **Nerfstudio (`splatfacto` / `nerfacto`):** Fully functional on Windows (native or via WSL2) on RTX 3060/4070/4090 GPUs (requires 8–16 GB VRAM) ([Nerfstudio Documentation](https://docs.nerf.studio/)).
  * **gsplat:** High-performance, memory-efficient CUDA rasterization library maintaining Python bindings on Windows ([gsplat GitHub Repository](https://github.com/nerfstudio-project/gsplat)).
  * **PostShot (Jawset):** Native Windows desktop GUI application leveraging CUDA to train 3DGS on consumer RTX cards in minutes ([Jawset PostShot](https://www.jawset.com/)).
* **Can They Export a Metric Mesh for Measurement?**
  * Standard 3DGS produces unorganized 3D ellipsoids (`.ply`), not polygonal surface geometry.
  * **Mesh Extraction Methods:**
    1. **SuGaR (Surface-Aligned Gaussian Splatting):** Regularizes Gaussians to flat surface patches, allowing direct Poisson mesh extraction with UV coordinates ([SuGaR: Surface-Aligned Gaussian Splatting](https://github.com/Anttwo/SuGaR)).
    2. **2D Gaussian Splatting (2DGS):** Collapses 3D ellipsoids into oriented 2D surfels, enabling ray-traced depth/normal rendering and TSDF fusion / Poisson meshing ([2D Gaussian Splatting for Geometrically Accurate Radiance Fields](https://surfsplat.github.io/)).
    3. **Nerfstudio Mesh Export:** `ns-export poisson` or `ns-export tsdf` (requires normal estimation enabled during training).
  * **Metric Scale:** If the input poses (from ARCore or metric-calibrated COLMAP) are defined in meters, the resulting splat coordinate space is strictly metric. Distance measurements taken on the exported mesh represent true real-world dimensions.
* **Accuracy vs. MVS for Indoor Rooms:**
  * **Textureless Surfaces (White walls, ceilings, uniform doors):** Traditional MVS (PatchMatch/OpenMVS) fails catastrophically due to lack of distinct photometric gradients, resulting in holes and missing walls. 3DGS/2DGS interpolates smoothly across low-texture surfaces, reconstructing full enclosed rooms.
  * **Geometric Sharpness & Planarity:** MVS produces flat planar surfaces with sharp $90^\circ$ corners. Standard 3DGS suffers from "floaters" and surface ripple/bumpiness (~1–3 cm high-frequency noise on flat drywall). For precision room measurement, 2DGS or post-processed planar fitting is required to avoid dimensional errors ([2DGS Metric Reconstruction Evaluation](https://surfsplat.github.io/)).

---

### 10. Phone Capture Best Practices & ARCore Intrinsics Mapping

* **Capture Guidelines:**
  * **Overlap:** Minimum **70%–80% forward overlap**, **60%–70% sidelap** between adjacent passes ([COLMAP Capture Recommendations](https://colmap.github.io/tutorial.html)).
  * **Image Count:** A standard residential room ($4 \times 5\,\text{m}$) requires **180 to 300 images** covering perimeter perimeter loops at two distinct heights, plus $45^\circ$ oblique shots targeting wall-ceiling and wall-floor intersections.
  * **Motion Blur Mitigation:** Enforce camera shutter speeds $\ge 1/120\,\text{s}$ (ideally $1/250\,\text{s}$), maintain bright ambient illumination, and utilize stop-and-shoot capture or continuous smooth walking with motion-based image rejection.
  * **Locking Exposure & Focus:** **Mandatory.** Auto-focus dynamically alters optical focal length (lens breathing), violating camera calibration. Auto-exposure causes photometric mismatch across overlapping frames. In Android Camera2 / ARCore, lock both:
    ```kotlin
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
    ```
* **Mapping ARCore Intrinsics to COLMAP Models:**  
  ARCore provides `CameraIntrinsics` via `Camera.getImageIntrinsics()` ([Google ARCore CameraIntrinsics API](https://developers.google.com/ar/reference/java/com/google/ar/core/CameraIntrinsics)):
  * `getFocalLength()` returns `[fx, fy]` in pixels.
  * `getPrincipalPoint()` returns `[cx, cy]` in pixels.
  * `getImageDimensions()` returns `[width, height]`.
  
  **COLMAP Camera Model Mappings:**
  * **`PINHOLE` (4 parameters):**  
    Parameters: `fx, fy, cx, cy`. Use when feeding ARCore’s `getImageIntrinsics()` directly, assuming negligible lens distortion.
  * **`OPENCV` (8 parameters):**  
    Parameters: `fx, fy, cx, cy, k1, k2, p1, p2`. ARCore's Java SDK does not expose raw radial/tangential coefficients directly via `CameraIntrinsics`. To populate the `OPENCV` model, query the underlying Android Camera2 API:
    `CameraCharacteristics.LENS_DISTORTION` or `LENS_INTRINSIC_CALIBRATION` ([Android Camera2 CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)).

---

### 11. Drone Photogrammetry: Mini 3 Pro Parameters, GSD, and Metric Accuracy

* **Flight Capture Parameters:**
  * **Recommended Overlap:** 75%–80% frontal overlap, 70% side overlap (increase to 80%/80% over dense vegetation or repetitive roofs) ([Pix4D Overlap Guidelines](https://support.pix4d.com/)).
  * **Altitude:** Typically **30 m to 60 m AGL** (Above Ground Level) for high-resolution asset mapping.
* **Mini 3 Pro Sensor & Optics Specifications:**
  * **Actual Focal Length ($F$):** **$6.72\,\text{mm}$** ($0.00672\,\text{m}$); 35mm-equivalent is $24\,\text{mm}$ ([DJI Mini 3 Pro Specs](https://www.dji.com/mini-3-pro)).
  * **Sensor Size (1/1.3″ CMOS):** Effective imaging area is **$9.68\,\text{mm} \times 7.26\,\text{mm}$** (diagonal ~12.1 mm) ([DJI Mini 3 Pro User Manual](https://www.dji.com/mini-3-pro/downloads)).
  * **12 MP Mode Resolution:** $4032 \times 3024$ pixels.
  * **Pixel Pitch ($p$):** $2.4\,\mu\text{m} = 0.0024\,\text{mm}$ (via 4-in-1 Quad Bayer binning).
  * **48 MP Mode Resolution:** $8064 \times 6048$ pixels, native pixel pitch $p = 1.197\,\mu\text{m} \approx 0.0012\,\text{mm}$.
* **Ground Sampling Distance (GSD) Formula & Calculations:**
  $$\text{GSD} = \frac{H \times p}{F} = \frac{H \times S_w}{F \times I_w}$$
  Where:
  * $H$ = Flight altitude above ground
  * $p$ = Pixel size ($0.0024\,\text{mm}$ for 12 MP)
  * $F$ = Focal length ($6.72\,\text{mm}$)
  
  **Ratio for 12 MP:**
  $$\text{GSD}_{12\text{MP}} = H \times \left(\frac{0.0024\,\text{mm}}{6.72\,\text{mm}}\right) = \frac{H}{2800}$$
  * At $H = 28\,\text{m}$ ($92\,\text{ft}$): $\text{GSD} = 1.0\,\text{cm/pixel}$.
  * At $H = 50\,\text{m}$ ($164\,\text{ft}$): $\text{GSD} = \frac{50}{2800} \approx 0.0179\,\text{m/pixel} = \mathbf{1.79\,\text{cm/pixel}}$.
  * At $H = 100\,\text{m}$ ($328\,\text{ft}$): $\text{GSD} = \frac{100}{2800} \approx \mathbf{3.57\,\text{cm/pixel}}$.
  
  **Ratio for 48 MP Mode ($p \approx 0.0012\,\text{mm}$):**
  $$\text{GSD}_{48\text{MP}} = \frac{H}{5600} \implies \text{At } 50\,\text{m}, \text{GSD} \approx \mathbf{0.89\,\text{cm/pixel}}.$$
* **Metric Scale from Image EXIF/XMP:**
  * **COLMAP:** Raw incremental SfM is scale-arbitrary. However, running `colmap model_aligner` with GPS coordinates extracted from EXIF estimates a 7-DoF Sim(3) similarity transform, converting the sparse reconstruction to true metric scale (meters) ([COLMAP Model Aligner Documentation](https://colmap.github.io/tutorial.html)).
  * **OpenMVS:** Inherits the metric scale directly from the aligned COLMAP sparse model.
* **Expected Absolute Accuracy Without Ground Control Points (GCPs):**
  * The Mini 3 Pro uses standard commercial GNSS (GPS + GLONASS + Galileo + BeiDou) without RTK/PPK differential corrections.
  * **Horizontal Absolute Accuracy:** **$\pm 1.5\,\text{m}$ to $\pm 3.0\,\text{m}$**.
  * **Vertical Absolute Accuracy:** **$\pm 3.0\,\text{m}$ to $\pm 8.0\,\text{m}$** (barometric altitude drift during flight introduces systemic vertical offset).
  * **Relative Scale Accuracy:** Model internal measurements (e.g., roof lengths or wall spans) achieve **$\sim 1\%$ to $3\%$ relative error**, though uncalibrated consumer rolling shutter and pure nadir flights without oblique angles risk non-linear elevation bowing ("doming effect") without GCPs.

---

## (i) Facts That Could Not Be Verified

1. **Android API 37 Compatibility:** While MSDK v5.18.0 builds cleanly against `targetSdk 34/35`, no official DJI documentation or release notes have validated runtime behavior or stability under Android API 37 (`compileSdk 37`).
2. **ARCore Direct Access to Lens Distortion Coefficients in Java:** ARCore’s public Android SDK `CameraIntrinsics` class documentation explicitly exposes only `getFocalLength()`, `getPrincipalPoint()`, and `getImageDimensions()`. Whether ARCore exposes the 5 radial/tangential distortion parameters through private reflection or only via the Android NDK `ArCameraIntrinsics_getDistortionCoefficients` could not be confirmed in Java reference docs.

---

## (ii) The Three Biggest Technical Risks for This Project

1. **Remote Controller Architecture Blocker (DJI RC vs. RC-N1):**  
   The most common bundled remote for the Mini 3 Pro is the **DJI RC** (integrated screen). Because it runs a locked-down 32-bit OS that strictly prohibits installing third-party apps and cannot pass telemetry/video to an attached smartphone via USB, your Android app cannot interact with the drone if the user owns this controller. Users must acquire a **DJI RC-N1** or an expensive enterprise **DJI RC Pro**.
2. **Firmware Absence of Native Waypoints on Mini 3 Pro:**  
   Because the Mini 3 Pro firmware does not support onboard KMZ Wayline missions (`WPMZManager`), your application cannot upload an automated mapping flight plan for the aircraft to execute independently. The app must implement a real-time **Virtual Stick control loop** over Wi-Fi/OcuSync at 10–20 Hz. If radio interference or packet drops occur mid-mission, automated photogrammetry flight terminates or drifts unless elaborate fail-safe logic is built into your client app.
3. **MVS Failure on Indoor Textureless Surfaces vs. 3DGS Measurement Noise:**  
   Standard photogrammetry pipelines (COLMAP + OpenMVS) fail when reconstructing blank interior drywall, smooth ceilings, and reflective floors due to a lack of matching keypoints. While Gaussian Splatting (3DGS/2DGS) reconstructs these surfaces visually, standard 3DGS does not output a polygonal mesh and exhibits 1–3 cm of surface "wrinkling" and floater artifacts. Extracting a geometrically flat, metric mesh suitable for architectural room dimensioning requires specialized 2DGS surfel fusion or planar segmentation algorithms rather than a turnkey MVS pipeline.
