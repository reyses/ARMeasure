# Processing protocol (phone <-> PC)

Heavy work runs on the phone (chosen by phone power) or on the owner's PC (RTX 3060 12 GB):
the phone uploads a job ZIP, the PC processes it, the phone downloads a result ZIP.
Code: `app/src/main/java/com/example/arruler/processing/`. All JSON keys are snake_case, all lengths are meters.

## 1. Job types and quality

| job_type | input | output |
|---|---|---|
| `scan_analyze` | voxel cloud | planes + room (area, perimeter, height, volume, walls) |
| `object_mesh` | isolated object points | object dims + mesh |
| `photogrammetry` | images + poses + intrinsics | textured mesh (PC only) |

Object quality (objects are about 200 mm) is written to `manifest.json` as `quality` (+ `voxel_mm`):

| quality | job_type | voxel_mm | backend | accuracy |
|---|---|---|---|---|
| `QUICK` | object_mesh | 5 | phone, any tier | +-1 cm |
| `FINE` | object_mesh | 3 (two orbits) | phone on MID/HIGH, else PC | +-5-8 mm |
| `DETAILED` | photogrammetry (60-120 photos + ARCore poses) | none | PC only | +-1-2 mm |
| `DETAILED_SPLAT` | reserved | none | PC only, disabled (`ObjectQuality.SPLAT_ENABLED = false`) | +-1-2 mm |

The server must accept `quality` values it does not know and fall back on `job_type`.

## 2. Routing (phone side, `Router`)

1. `DETAILED_SPLAT` is disabled -> blocked.
2. PC-only work (photogrammetry, `DETAILED`, `FINE` on a LOW phone) -> PC; no PC -> blocked.
3. Preference PHONE -> phone. Preference PC -> PC, else phone with a warning.
4. AUTO with a PC: PC when points exceed the tier comfort limit (LOW 50k, MID 150k, HIGH 400k), thermal status >= MODERATE, or battery < 20 % and not charging; else phone.
5. AUTO without a PC: phone, with a warning above the comfort limit.
6. PC upload on mobile data above 20 MB asks for confirmation.

Tier (`ProfileRules`): LOW if `isLowRamDevice`, RAM < 3 GiB or < 4 cores; otherwise by reported RAM (< 4.5 GiB LOW, < 7 GiB MID, else HIGH), lifted by `MEDIA_PERFORMANCE_CLASS` (>= 31 MID, >= 33 HIGH), capped by the cached 2 s benchmark (> 1500 ms LOW, > 600 ms MID; thresholds are provisional until calibrated on devices).

`Router.availableQualities(tier, pcAvailable, onWifi, estimate)` returns the picker rows (backend, label, estimated time, accuracy, enabled, reason such as "needs your PC"); `Router.defaultQuality` picks the best enabled row that needs no confirmation.

## 3. HTTP API (v1)

Base URL from pairing. Every request carries `Authorization: Bearer <token>`. HTTPS, or plain http only to private LAN IPv4 literals (10/8, 172.16/12, 192.168/16); the phone refuses anything else and does not follow redirects. Max upload 2 GB. Responses are JSON except the result download.

| Call | Success | Notes |
|---|---|---|
| `GET /v1/ping` | 200 `{"name":"RYZEN-PC","version":"0.1.0","gpu":"RTX 3060 12GB","api_version":1,"max_upload_bytes":2147483648}` | auth check too |
| `POST /v1/jobs` multipart: `type` (job_type wire string), `file` (ZIP) | 202 `{"id":"<opaque>"}` | validates manifest before queueing |
| `GET /v1/jobs/{id}` | 200 status object | |
| `GET /v1/jobs/{id}/result` | 200 `application/zip` (result ZIP) | 409 `not_ready` until state is `done` |
| `DELETE /v1/jobs/{id}` | 204 | cancels if queued/running, deletes data; idempotent |

Status object: `{"id":"..","state":"queued|running|done|failed|cancelled","progress":0.0-1.0,"stage":"mesh","position":2,"error":"message only when failed"}`.
`position` is the queue place for `queued`; `progress`/`stage` for `running`. The phone polls every 2 s, backing off x1.5 to 10 s while nothing changes, and resets to 2 s on change.

Errors: non-2xx with body `{"error":{"code":"<code>","message":"<human text>"}}`.

| HTTP | code | meaning |
|---|---|---|
| 400 | `bad_request` | missing part / unknown `type` |
| 401 | `unauthorized` | missing or wrong token |
| 404 | `not_found` | unknown job id |
| 409 | `not_ready` | result requested before done |
| 413 | `too_large` | over 2 GB |
| 415 | `unsupported_media` | not a ZIP |
| 422 | `invalid_package` | manifest/payload invalid, unsupported schema |
| 429/503 | `busy` | server cannot accept now; client may retry |
| 500 | `internal` | processing crash (job state also becomes `failed`) |

## 4. Job ZIP

`manifest.json`:

```json
{"schema":1,"job_type":"object_mesh","app_version":"1.0.0","units":"meters",
 "created":"2026-10-03T12:00:00Z",
 "device":{"tier":"MID","performance_class":31,"total_mem_mb":5600,"cores":8,"soc":"SM8550","bench_ms":420},
 "coordinates":{"frame":"ARCore world","units":"meters","up":"+Y","handedness":"right",
   "pose_layout":"4x4 column-major, camera-to-world","camera_axes":"OpenGL: +X right, +Y up, camera looks along -Z"},
 "quality":"FINE","voxel_mm":3,"point_count":183000,"image_count":0,
 "files":["cloud.ply"]}
```

`quality`/`voxel_mm` are absent for `scan_analyze` jobs without one. Readers reject `schema` greater than 1.

Point jobs (`scan_analyze`, `object_mesh`): `cloud.ply`, binary little-endian PLY, 15 bytes per vertex: `float x, float y, float z, ushort hits, uchar confidence` (header lists exactly these properties in this order). Coordinates are ARCore world meters, +Y up.

Photogrammetry: `images/000001.jpg ...` (1-based, sorted order = pose order) and `poses.json`:

```json
{"schema":1,"images":[{"file":"images/000001.jpg","timestamp_ns":123456789,
  "pose":[16 floats, column-major 4x4 camera-to-world],
  "fx":1500.0,"fy":1500.0,"cx":960.0,"cy":540.0,"width":1920,"height":1080}]}
```

The pose is the physical (sensor-oriented) camera pose `Camera.getPose()`, which matches the CPU image and `getImageIntrinsics()`. Do NOT use `Camera.getDisplayOrientedPose()`: it differs by a rotation about Z of a multiple of 90 degrees (ARCore Camera reference). There is no `getImagePose()` in ARCore 1.56 (checked with javap 2026-10-03). Axes are the OpenGL camera convention (+X right, +Y up, looking along -Z); the server converts to the COLMAP/OpenCV convention (flip Y and Z). `width`/`height` are the CPU image size the intrinsics refer to. Device-verify the pose convention against a known scene.

### Photogrammetry capture requirements (phone)

- Request the highest CPU image resolution: build the session `CameraConfigFilter`, call `session.getSupportedCameraConfigs(filter)`, pick the config with the largest `getImageSize()` and `session.setCameraConfig(...)` before resuming. (Check depth-sensor support stays enabled on that config.)
- Capture with autofocus ON (`Config.FocusMode.AUTO`) for sharpness; skip blurred frames.
- Intrinsics come per frame from `getImageIntrinsics()` (they change with focus). The server should refine the focal length: COLMAP with per-image intrinsics (`--ImageReader.single_camera 0`) and bundle-adjust focal length (`--BundleAdjustment.refine_focal_length 1`), using the ARCore poses as the initial guess / scale and gravity prior.
- 60-120 photos on orbits around the object, 60 % overlap, avoiding motion blur.

## 5. Result ZIP

`result.json`:

```json
{"schema":1,"job_type":"scan_analyze",
 "measures":{"area_m2":{"low":11.5,"high":12.5,"recommended":12.0},
   "perimeter_m":{...},"height_m":{...},"volume_m3":{...},
   "volume_variants_m3":{"bounding_box":31.0,"mesh":29.5},
   "wall_count":4,"object_dims":{"length_m":0.2,"width_m":0.1,"height_m":0.05}},
 "stats":{"backend":"pc","duration_ms":4200,"versions":{"server":"0.1.0","open3d":"0.19"},"notes":[]},
 "files":["mesh.ply","texture.png"]}
```

Every `measures` field is optional (omit what the job does not produce); estimates are `{low, high, recommended}`. Optional entries, any subset: `mesh.ply`, `mesh.obj`, `texture.png`, `planes.json` (`{"schema":1,"planes":[{"kind":"FLOOR|CEILING|WALL|OTHER","normal":[x,y,z],"d":0.0,"centroid":[x,y,z],"inliers":900,"outline_3d":[[x,y,z],...]}]}`), `cloud_clean.ply` (same PLY layout as the job). No other entry names; paths are flat (no `..`).

## 6. QR pairing

The PC shows a QR containing:

```json
{"v":1,"url":"https://pc.example:8765","token":"<32+ random chars>","name":"RYZEN-PC"}
```

The phone validates `v == 1`, token length >= 32, URL policy (section 3), then stores url + token in EncryptedSharedPreferences (`AndroidPairingStore`; falls back to plain preferences with a TODO if the keystore fails) and calls `/v1/ping`.
A self-signed HTTPS certificate is not trusted (system trust only); use LAN http mode, a real certificate or a tunnel.

## 7. Android wiring (not done in this change)

- Manifest: `INTERNET` + `ACCESS_NETWORK_STATE` are added. Add `android:networkSecurityConfig="@xml/network_security_config"` to `<application>` for LAN http (the file exists; cleartext is allowed at platform level and restricted in code by `UrlPolicy`).
- Settings: "Processing" = Auto / Phone / PC (`UserPref`), "Pair PC" (QR), connection status from `ping()`, quality picker from `Router.availableQualities(...)`.
- QR scanning: use ML Kit `com.google.mlkit:barcode-scanning` with CameraX, or the Google code scanner `com.google.android.gms:play-services-code-scanner` (no camera permission, no CameraX, simplest). ARCore owns the camera while its session runs, so scan on a separate screen with the AR session paused (`ArSessionController` pause before navigating, resume after); never run both at once.
- Call: `ProcessingService(signals, HttpPcLink(store.load()!!), DefaultJobPackager(versionName, { profile.summary() })).process(job)` and collect the `ProcessingState` flow (`Routed`, `NeedsConfirmation` -> re-call with `confirmedMobileUpload = true`, `Warning`, `Packaging`, `Uploading`, `Queued`, `Running`, `Downloading`, `Done`, `Failed`). Cancelling the collector sends `DELETE /v1/jobs/{id}`.
- Call `DeviceProfile.read(context, useBenchmark = true)` once off the main thread (about 2 s, cached per versionCode).
