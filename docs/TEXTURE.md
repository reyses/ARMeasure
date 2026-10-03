# Photo texture, keyframes, spin capture (package `com.example.arruler.texture`)

Everything below is new files in `texture/`; nothing outside it is edited. Pure Kotlin (JVM-tested) unless marked Android.

## Files

| File | What |
|---|---|
| `CameraModel.kt` | `Intrinsics`, `CameraPose` (ARCore `Pose.toMatrix`, camera to world, OpenGL axes), projection |
| `KeyframePolicy.kt` | walk-around selection rules + `Sharpness.laplacianVariance` + `RunningMedian` |
| `KeyframeCapture.kt` (Android) | `Frame.acquireCameraImage()` to JPEG on a background thread, `keyframes.json` |
| `KeyframeStore.kt` | `keyframes.json` + `kf_000001.jpg ...` format, read/write, to `PhotoFrame` |
| `YuvConvert.kt` | YUV_420_888 planes to NV21 with strides |
| `CameraConfigChooser.kt` | ranking (pure) + `select(session)` (Android) for a bigger CPU image that keeps depth |
| `TextureBaker.kt` | atlas bake, `bakeVertexColors`, z-buffer occlusion, charts, shelf packing |
| `TexturedMeshIo.kt` | OBJ + MTL text (pure), vertex-colour PLY (pure), PNG/OBJ writer (Android) |
| `TexturedMeshNode.kt` (Android) | SceneView composables `TexturedMeshNode`, `VertexColouredMeshNode` |
| `PhotogrammetryJobBuilder.kt` | keyframes to the PHOTOGRAMMETRY ZIP via `JobPackage.writePhotoJob` |
| `SpinKeyframePolicy.kt`, `BoxMask.kt`, `SpinJobBuilder.kt` | spin / hybrid capture (below) |

## Walk-around keyframes

Call `KeyframeCapture.onFrame(frame, boxCentre)` from the session's per-frame callback while the object scan runs,
`finish()` at the end. Rules (`KeyframePolicy`): tracking == TRACKING; box centre inside the central 80 % of the image;
at least 8 degrees from every kept keyframe as seen from the box centre; Laplacian-variance sharpness (Y plane,
downsampled to about 240 px short side) at least 0.6 x the running median (last 40); at most 120.
Stored per keyframe: JPEG (quality 90), pose = `Camera.getPose()` 16 floats (physical camera, camera to world, OpenGL axes),
intrinsics from `getImageIntrinsics()` scaled to the JPEG size, `timestamp_ns`, sharpness. At most one acquire per 200 ms,
at most 2 encodes in flight. The 120 cap is reached after about 18 degrees of average spacing on a full sphere, so
sweep the object evenly.

`PhotogrammetryJobBuilder.build(keyframeDir, dest, meta)` writes the standard PHOTOGRAMMETRY package (this is what un-greys
DETAILED once a build captures keyframes).

## Higher CPU image resolution (camera config)

ARCore's CPU image (`Frame.acquireCameraImage`, `CameraConfig.getImageSize()`) is independent of the GPU texture
(`getTextureSize()`); the default config commonly has a 640x480 CPU image. `Session.getSupportedCameraConfigs(CameraConfigFilter)`
lists every config with its `imageSize`, `textureSize`, `fpsRange` and `depthSensorUsage` (REQUIRE_AND_USE needs a hardware
ToF sensor, DO_NOT_USE does not). The actual list is device-only knowledge; on most phones it contains 640x480 and often
1280x720 and 1920x1080 for the back camera.

`CameraConfigRanking.rank` (pure, tested) orders configs: largest CPU image up to 1920x1080 (more costs copy and JPEG
time), configs reaching 30 fps first, configs that do not need the depth sensor before those that do, lower index
first. `CameraConfigChooser.select(session)` applies the ranked configs in turn (the session is not yet resumed, the only
time ARCore accepts `session.cameraConfig = ...`) and keeps the first for which `session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)`
is true; otherwise it restores the default. Wiring in `ar/ArSceneHost.kt` (one line, replaces `sessionCameraConfig = null`):

```kotlin
sessionCameraConfig = { session -> com.example.arruler.texture.CameraConfigChooser.select(session) },
```

SceneView 4.52's `ARSceneView(sessionCameraConfig: ((Session) -> CameraConfig)?)` is called before the session resumes
(checked with javap: parameter type `Function1<Session, CameraConfig>`). Note SceneView also ships `highestResolutionCameraConfig(Session)`
but it ignores depth. Changing the config changes the camera stream, so apply it only when photo capture is wanted
(it needs `key(...)` to rebuild the view, as the playback dataset does).

## Texture baking

`TextureBaker.bake(mesh, keyframes)` with `Keyframe(pose, intrinsics, argb)` (decode JPEGs with `BitmapFactory`, `getPixels`).
Per triangle the best view maximises cos(normal, direction to camera) x projected area among views where it is front-facing
(cos >= 0.15), fully inside the image (1 px margin), and not occluded (coarse 160 px z-buffer of the mesh from that view,
inverse-depth, slope-aware tolerance, 3x3 neighbourhood). Adjacent triangles with the same view form a chart; charts are
shelf-packed into a square atlas (power of two, at most 2048; if they do not fit the chart scale shrinks), 2 px padding filled
by gutter dilation, colours sampled bilinearly. Unseen triangles get the average colour of their neighbours (wavefront) in
4-bit-quantised solid cells. Output `BakedMesh`: mesh with seam vertices duplicated and triangles in the source order,
`uvs` (2 floats per vertex, ORIGIN TOP-LEFT as Filament/glTF; the OBJ writer flips v), `atlas` (ARGB, `atlasSize` square),
`triangleView` (keyframe per triangle, -1 unseen), `stats`. `bakeVertexColors` is the cheaper variant (one 0xFFRRGGBB per
vertex) for LOW-tier phones. Vertices on the silhouette sample a blend of object and background; this is visible only as a
darker/lighter rim.

Measured on the JVM (desktop, single thread, this repo's test): 125,000 triangles x 60 keyframes (640x480): atlas bake
about 1.5 s, vertex colours about 0.5 s. Phone numbers are device-only; expect 5 to 10 times slower, so run it on a background thread
with the `progress` / `cancelled` hooks.

## Viewer (SceneView 4.52)

4.52 HAS a textured material, so no fallback is needed for the atlas path:
`ImageTexture.Builder().bitmap(atlas).build(engine)` (`io.github.sceneview.texture.ImageTexture`) and
`MaterialLoader.createTextureInstance(texture, isOpaque, metallic, roughness, reflectance)` (lit), with
`Geometry.Vertex(position, normal, uvCoordinate)`; drawn with `SceneScope.MeshNode(...)` exactly as `scan3d/Scan3DViewer.kt` does.
Wiring in `Scan3DViewer` (inside the `SceneView { ... }` content lambda, instead of the grey mesh node):

```kotlin
val bmp = remember(baked) { TexturedMeshWriter.atlasBitmap(baked) }
TexturedMeshNode(engine, materialLoader, baked.mesh, baked.uvs, bmp)          // photo-textured
// LOW tier: VertexColouredMeshNode(engine, materialLoader, mesh, TextureBaker.bakeVertexColors(mesh, kfs))
```

`VertexColouredMeshNode` is the per-vertex-colour fallback: triangles median-cut into at most 64 colour buckets, one unlit
mesh per bucket (the point viewer's trick). Export: `TexturedMeshWriter.writeTextured(dir, base, baked)` writes
`base.obj`, `base.mtl`, `base.png`; `writeColoured` writes a vertex-coloured binary PLY.

## Spin and hybrid capture

Spin: the phone sits on a stand, the object turns on a turntable or by hand. Hybrid: one walk-around depth pass (the
normal object scan: cloud + keyframes with real poses) plus a spin pass.

### Spin keyframe policy (`SpinKeyframePolicy`)

- PhoneMoved: `phoneMoved(pose)` is true when the camera pose drifts more than 1.0 cm or 1.5 degrees from the pose given to
  `begin(pose)` (the rotation angle is taken from the trace of R_ref^T R). Show a warning and ask for a restart.
- Selection by image change: `SpinRoi.fromHull(BoxMask.hull(box, pose, intrinsics), w, h)` builds a 48x48 luma grid over the
  box's projected hull (cells whose centre is in the hull). A frame is kept when mean |luma difference| over those cells against
  the LAST KEPT frame exceeds `roiDiffThreshold x max(ROI contrast of the last kept frame, 4 luma levels)`; contrast is the mean
  absolute deviation of the ROI luma from its mean. Plus the same sharpness gate (>= 0.6 x running median) and the cap
  of 72 per turn, 2 turns (`nextTurn()`; the first frame of a turn is always kept).
- Derivation of the threshold (synthetic rotating textured 200 mm cube, camera 40 cm from the axis and 25 cm above
  the plane, 640x480, 1 degree steps, checkerboard texture with n x n cells per face): a plain absolute threshold is not texture
  independent (the difference saturates at about 20 luma levels on the contrasty cube and far lower on a dull object),
  hence the ratio. Keyframes per 360 degree turn at ratio 0.5 / 0.65 / 0.8: 39 / 29 / 23 (n = 4, 50 mm cells) and 69 / 52 / 38
  (n = 8, 25 mm cells). The default is **0.65** (`SpinConfig.DEFAULT_ROI_DIFF`): 12 degrees between keyframes on the coarse
  texture, 7 degrees on the fine one, about 10 degrees geometric mean, and below the 72 cap for both. Cube corners moving
  toward the camera make the spacing uneven (9 to 15 degrees on the coarse texture). Caveats: an object much smaller than its
  box dilutes the difference (fewer keyframes: set a smaller ratio or fit the box tight); a very fine texture can use up the 72
  cap before the turn ends (the UI should show the count and let the user end the turn).

### Masks (`BoxMask`)

The 8 box corners grown by 2 cm (`BoxMask.DEFAULT_PAD_M`) are projected with the keyframe's pose and intrinsics (edges are
clipped at a 2 cm near plane), the convex hull is rasterised (pixel centres), written as 8-bit grayscale PNG, 255 = object
region. COLMAP: `--ImageReader.mask_path <masks_dir>` expects `<masks_dir>/<image name>.png` relative to the image folder, so
the mask of `images/000001.jpg` is `masks/000001.jpg.png` (use `--ImageReader.image_path images` and `--ImageReader.mask_path masks`).

### Job package (`SpinJobBuilder`)

A PHOTOGRAMMETRY package (`processing/JobPackage`, unchanged; `JobPackage.read` still accepts it) with these additions.

ZIP layout:

```
manifest.json            standard manifest + the extras below
poses.json               spin images (standard format, per-image intrinsics, STATIC camera: all poses equal up to tracking noise)
images/000001.jpg ...    spin images (the object turns, the camera does not)
masks/000001.jpg.png ... one per spin image
cloud.ply                hybrid only: the walk-around depth cloud (standard cloud.ply)
walk/poses.json          hybrid only: walk-around keyframes, standard poses.json format, REAL camera poses
walk/images/000001.jpg   hybrid only
```

Manifest extras (all in `manifest.json`, units meters, world = ARCore world, +Y up):

| key | type | meaning |
|---|---|---|
| `capture` | `"spin"` or `"hybrid"` | capture mode |
| `camera_static` | `true` | the spin camera did not move (the object did) |
| `box` | `{"center":[x,y,z],"size":[w,h,d],"yaw_deg":..}` | same as the standard object job: centre of the volume |
| `support_plane` | `{"normal":[x,y,z],"d":..}` | n.p + d = 0, same as the standard object job |
| `rotation_axis` | `{"point":[x,y,z],"direction":[0,1,0]}` | turntable axis: vertical line through the box base centre |
| `camera_to_axis_m` | number | horizontal distance from the (mean) spin camera centre to the vertical axis through the box |
| `camera_height_above_plane_m` | number | signed distance of the camera centre above the support plane |
| `masks_dir` | `"masks/"` | mask folder, naming `<image file name>.png` |
| `mask_padding_m` | number | padding used for the hull (0.02) |
| `walk` (hybrid) | `{"dir":"walk/","poses":"walk/poses.json","images_dir":"walk/images/","image_count":n}` | walk-around keyframes |

`files` additionally lists the mask names and `cloud.ply` (hybrid). The spin poses are in the same ARCore world frame as
the walk-around poses and the cloud, but the object ROTATES between spin frames, so reconstruction must treat the spin
images as one rigid camera observing a rotating object: either solve for the turntable angle per image about
`rotation_axis`, or (simplest) let COLMAP run with the mask so only the object region is matched, and use the walk-around
cloud for scale and the first alignment. The server drone owns that step.

## Tests (JVM)

`SyntheticCube` (test helper) ray-casts a checkerboard cube; `TextureBakerTest` bakes it from 24 poses and asserts every face
centre colour and UVs in [0,1], plus occlusion, unseen-fill, vertex colours and the 125k-triangle timing; `SpinTest` covers the
mask, the ROI sweep above, per-turn caps, PhoneMoved, and the spin/hybrid ZIPs; `KeyframeSupportTest` covers sharpness, the
walk-around rules, NV21, config ranking and the keyframe-to-ZIP round trip.

## Device-only (not verifiable here)

- the camera configs a phone actually offers and whether depth survives them (`isDepthModeSupported` per config);
- `acquireCameraImage` timing at 1080p, JPEG encode time, memory with 120 keyframes;
- that SceneView's textured material culls/lights the baked mesh as expected (winding, double-sidedness) and the texture
  destroy order on dispose;
- the pose / intrinsics convention against a known scene (see PROCESSING_PROTOCOL.md);
- spin: the real ROI-difference rate on real objects, the box ROI quality, and `PhoneMoved` thresholds under ARCore drift
  on a static phone; an Android `SpinCapture` (Frame to signature to policy) is not written yet: it mirrors `KeyframeCapture`
  (copy the Y plane, `roi.signature(y, rowStride, pixelStride)`, `Sharpness.laplacianVariance`, `policy.decide`).
