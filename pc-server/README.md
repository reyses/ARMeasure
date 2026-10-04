# ARMeasure PC server

PC side of the phone <-> PC processing link. Implements `docs/PROCESSING_PROTOCOL.md` (API v1, FastAPI + Open3D).
Python 3.12.10, venv `C:\venvs\armeasure-pc`, all dependencies pinned in `requirements.txt`.

## Start

```powershell
C:\venvs\armeasure-pc\Scripts\python.exe -m armeasure_pc            # Tailscale + LAN URLs (--bind auto)
C:\venvs\armeasure-pc\Scripts\python.exe -m armeasure_pc --tunnel   # Cloudflare quick tunnel, https URL in the QR
```

Run it from `D:\ARMeasure\pc-server`. On start it prints the pairing JSON `{"v":1,"url","token","name"}`, an ASCII QR in
the console and opens `data\pairing.png` in the default image viewer (`--no-open` to skip). Scan it in the app.

Flags: `--tunnel`, `--new-token` (rotate), `--no-open`, `--bind` (default `auto`, see below; `--host` is an alias),
`--port` (48310), `--apk-dir` (default `D:\APK`), `--data-dir`.
`--tunnel` needs `%LOCALAPPDATA%\Programs\cloudflared\cloudflared.exe`. The LAN IP is the physical adapter that carries
the default route (virtual adapters such as Hyper-V, WSL, VMware, VPN are skipped).

Tests: `C:\venvs\armeasure-pc\Scripts\python.exe -m pytest` (about 10 s).

## Tailscale-first pairing and binding

The pairing JSON is `{"v":1,"url":<first>,"urls":[...],"token","name"}`. `urls` is ordered `[tailnet, lan, tunnel]`: when
`C:\Program Files\Tailscale\tailscale.exe` exists and is logged in (`tailscale ip -4` gives a 100.64.0.0/10 address),
the MagicDNS short name (`tailscale status --json`, `Self.DNSName`) comes first, then the 100.x address, then the LAN URL,
then the quick-tunnel URL when `--tunnel`. Tailscale missing, stopped or logged out is not an error: the list is LAN
(+ tunnel). `url` equals `urls[0]` for old phones.

`--bind auto` (default) opens one socket each on 127.0.0.1, the Tailscale IP and the LAN IP (no 0.0.0.0), and the app also
rejects (403) any request whose server-side address is not one of those. `--bind 0.0.0.0` (or any single address) restores
the old behaviour with no filter. 127.0.0.1 stays bound because cloudflared connects there.

## Dev link (docs/DEV_LINK.md)

Same bearer auth as everything else.

- `GET /v1/dev/apk?package=<applicationId>`: newest settled APK (not `.part`, not empty, mtime older than 2 s) in `--apk-dir`;
  `com.example.arruler` -> `ARMeasure-*.apk`, `com.reyses.leaveontime` -> `LeaveOnTime-*.apk`. Returns versionCode,
  versionName (aapt2 if found in PATH or the Android SDK, else 1 and ""), commit (last hyphen token if 7-40 hex), size,
  sha256 (cached by path+mtime+size), url.
- `GET /v1/dev/apk/<file>`: streams the APK (`application/vnd.android.package-archive`, Content-Length, Range). Only a plain
  name present in a package listing is served; everything else 404.
- `POST /v1/dev/logs`: multipart `device`, `app`, `commit`, `kind` (`logs|crash|diagnostics`, else 400) and `file`; cap
  20 MB (413). Stored as `data\devlogs\<YYYY-MM-DD>\<id>-<kind>-<device>.txt`, newest 500 kept; answer
  `{"id":"L-20261003-101500-9f3a"}`. (DEV_LINK.md names `D:\ARMeasure-logs\...` with meta.json and an 8 MB cap; this server
  follows the PC-guard brief instead.)

## Access log, auth failures, lockout

- `data\access.log`: JSON lines `{ts, ip, method, path, status[, peer]}` for every request (5 MB x 6 rotating files). `ip` is
  the TCP peer, except when the peer is 127.0.0.1/::1 (cloudflared): then `CF-Connecting-IP`, else the first
  `X-Forwarded-For`, and `peer` is added. Those headers from any other peer are ignored.
- `data\auth_failures.jsonl`: every 401 with `reason` (`missing|invalid`) and `locked`. The token is never logged.
- Lockout: 10 failed auths from one IP within 10 min -> 429 (Retry-After) for that IP for 15 min, even with the right
  token. State is in memory (a restart clears it). The token comparison stays constant time.

## Firewall and Tailscale

Windows Firewall inbound default is block. Tailscale traffic arrives on the Tailscale adapter, whose profile is often
Public, so a rule limited to `-Profile Private` does not cover it. Checked without admin on 2026-10-03: all three profiles
enabled with DefaultInboundAction NotConfigured (block); an enabled inbound Allow rule for
`C:\users\reyse\python312\python.exe` exists on the Public profile (the venv's base interpreter); the only network is
`Ethernet` (Public). No rule was added. Tailscale is not installed on this PC yet, so its adapter's profile is unverified.

## Firewall (LAN mode)

Allow inbound TCP 48310 on Private networks only. Run once in an elevated PowerShell (not run for you):

```powershell
New-NetFirewallRule -DisplayName "ARMeasure PC server" -Direction Inbound -Protocol TCP -LocalPort 48310 -Action Allow -Profile Private
```

## Security

- Every request needs `Authorization: Bearer <token>`; the token (43 chars, `secrets.token_urlsafe(32)`) is created on
  first run in `data\config.json` (gitignored) and compared in constant time. Uploads are authenticated and
  size-checked before the body is read.
- The quick-tunnel URL is PUBLIC (anyone who learns it reaches the server), so the token is the only protection.
  Do not paste the pairing JSON or `pairing.png` anywhere. The tunnel URL changes on every start.
- Rotate with `--new-token`; the phone must pair again.
- ZIPs are extracted with a path-traversal check; jobs older than 7 days are deleted (checked at start and hourly).

## Processing

| job_type | what runs |
|---|---|
| `scan_analyze` | outlier removal, iterative RANSAC planes, floor/ceiling/wall, room outline, area/perimeter/height/volume with low/high = walls and floor/ceiling moved by 1 cm |
| `object_mesh` | crop to oriented box (optional), support-plane removal (1.6 x voxel), DBSCAN, Poisson (depth 9, 10 below 4 mm voxel, trimmed at 5 % density) with ball-pivoting fallback; dimensions and hull volume come from denoised points (raw noise inflates a 4 mm-noise 200 mm cube to about 230 mm) |
| `drone_photos` | DJI photos with EXIF GPS, no phone poses: COLMAP + GPS georeferencing + OpenMVS, see the drone section below |
| `photogrammetry` | COLMAP + OpenMVS, see `INSTALL_PHOTOGRAMMETRY.md`; fails with a clear message when the tools are missing. Manifest `capture` = `spin` (static phone, turning object, no pose priors) or `hybrid` (walk-around + spin, fused): see "Spin and hybrid capture" below |

Open3D (pip) is CPU-only; the RTX 3060 is used only by COLMAP/OpenMVS. Result entries are exactly the protocol names
(`result.json`, `mesh.ply`, `mesh.obj`, `texture.png`, `planes.json`, `cloud_clean.ply`).

## Drone photos (DJI Mini 3 Pro + DJI RC)

The DJI RC (screen remote) cannot be controlled by an app, so you fly manually in DJI Fly, copy the photos off the SD
card, and the PC server turns them into a metric 3D model (job type `drone_photos`). Needs COLMAP and OpenMVS
(`INSTALL_PHOTOGRAMMETRY.md`); without them the job fails with the install hint.

### Flight recipe

- Pattern: a manual grid (lawnmower rows) with the gimbal at -90 degrees (nadir), plus two orbits around the object at
  about -45 degrees; a pure nadir grid alone tends to bend the model (doming).
- Overlap: 75-80 % front, 70 % side. Altitude vs ground sampling distance (formula below, 12 MP mode 4032 x 3024,
  48 MP mode in brackets); footprint is the ground area of one frame (long x short side):

| altitude | GSD 12 MP (48 MP) | footprint | shot spacing for 80 % front | line spacing for 70 % side | speed for a shot every 2-3 s |
|---|---|---|---|---|---|
| 20 m | 0.72 cm/px (0.36) | 28.9 x 21.7 m | 4.3 m | 8.7 m | 1.4-2.2 m/s |
| 30 m | 1.07 cm/px (0.54) | 43.3 x 32.5 m | 6.5 m | 13.0 m | 2.2-3.2 m/s |
| 50 m | 1.79 cm/px (0.89) | 72.2 x 54.1 m | 10.8 m | 21.7 m | 3.6-5.4 m/s |

  GSD = H x sensor width / (focal length x image width in px) = H x 9.7 mm / (6.72 mm x 4032 px). The 9.7 mm sensor
  width is an UNVERIFIED constant (research brief: 9.68 x 7.26 mm), used only when EXIF has no FocalPlaneXResolution;
  focal length 6.72 mm is from the same brief. Footprints assume the short side lies along the flight direction.
- Camera: timed shots every 2-3 s in DJI Fly (Photo > Timed), JPG (or JPG+RAW; DNG-only is rejected, each DNG needs
  its JPG twin), lock exposure (manual or AE lock) and white balance, fly with the sun behind clouds or early/late;
  avoid midday hard shadows. Keep the drone moving slowly (blur kills matching) and do not stop to hover mid-row.
- Take at least 60 and ideally 100-300 photos; more than 300 images use the sequential matcher.

### Copy the SD card

Copy `DCIM\100MEDIA\DJI_*.JPG` of one flight into one folder on the PC (for example `D:\flights\2026-10-03-roof`).
Use the original files: re-exported or messenger copies lose the GPS tags. Pass the folder that directly contains the
images (not recursive); do not mix flights.

### Run

```powershell
cd D:\ARMeasure\pc-server
C:\venvs\armeasure-pc\Scripts\python.exe -m armeasure_pc import-drone D:\flights\2026-10-03-roof --quality FINE --name roof
```

The CLI creates the job directly (the photos are read in place, never copied into a ZIP or modified), runs it in this
console with progress lines and prints the result folder `data\jobs\<id>\result` and `result.zip` (kept 7 days).
esult` and `result.zip` (kept 7 days).
Quality: `QUICK` images downscaled to 1600 px longest side and OpenMVS `--resolution-level 2`; `FINE` 2400 px and
level 1 (default); `DETAILED` full resolution and level 0.

Other entry points: `POST /v1/jobs` with a ZIP of JPG/DNG files and an optional `manifest.json`
`{"schema":1,"job_type":"drone_photos","quality":"FINE","gcp":[]}` (`gcp` is reserved and ignored), and
`POST /v1/jobs/local` with `{"path":"D:/flights/roof","type":"drone_photos","quality":"FINE","name":"roof"}`.
The local endpoint accepts requests from 127.0.0.1 only (anything else gets 403, the token is still required): the
server process opens the path itself, so a LAN client or a public tunnel visitor holding the token must not be able
to make it read arbitrary folders of this PC. cloudflared connects from 127.0.0.1, so requests that carry proxy headers
(`X-Forwarded-For`, `CF-Connecting-IP`, ...) are refused too.

### What it does and what you get

EXIF/XMP of every image (GPS, `drone-dji:Gimbal*Degree`, `FlightYawDegree`, `RelativeAltitude`, `AbsoluteAltitude`,
focal length, size, make/model; library ExifRead 3.5.1 plus a small XMP parser) goes to `images_meta.json` (job folder
and result ZIP). COLMAP: OPENCV camera per image size with the EXIF focal prior, sequential matcher (overlap 10) when
file names are in capture-time order, else the exhaustive matcher up to 300 images, mapper, then GPS lat/lon/alt ->
local ENU metres (pure numpy WGS84 -> ECEF -> ENU at the first image) and `colmap model_aligner` (`ref_is_gps 0`,
robust maximum error 3 m). Then OpenMVS as in the phone path. If the GPS positions cannot be fitted within 3 m the job
fails instead of returning an unscaled model.

Result ZIP (frame: ENU metres, x east, y north, z up, origin at the first image): `result.json` with
`measures` {`extent_m` {x,y,z}, `ground_area_m2` (2D convex hull of points within 0.5 m of the fitted ground plane),
`gsd_cm_per_px_estimate` (processed images; from XMP RelativeAltitude, focal length and pixel pitch), `images_used`,
`images_registered`, `alignment_rmse_m` (camera centres vs GPS)} and `stats`; `mesh.obj` + `texture.png`;
`cloud_clean.ply`; `images_meta.json`; `ortho.png`. `ortho.png` is a top-down numpy splat of the coloured dense cloud
at the GSD (at most 4096 px, north up) with a scale bar: an approximation, NOT a survey-grade orthomosaic.

### Expected accuracy without ground control points

From the research brief (`docs/research/dji_photogrammetry_brief_agy.md` section 11, unverified, not measured here):
relative (distances inside the model) about 1-3 %; absolute horizontal about 1.5-3 m; absolute vertical worse
(barometric drift). Use relative measurements; the absolute position is only as good as the drone's GPS. Ground control
points (the `gcp` manifest field) are reserved for later.

## Spin and hybrid capture (turntable)

Manifest `capture` selects the pipeline of a `photogrammetry` job (absent = the known-pose walk-around path above).
The phone's format is `docs/TEXTURE.md` ("Spin and hybrid capture"): `images/`, `poses.json` (static camera: the poses
of a turn are all equal), `masks/<image>.jpg.png`, manifest `capture`, `box`, `support_plane`, `rotation_axis`,
`camera_to_axis_m`, `camera_height_above_plane_m`; hybrid adds `cloud.ply`, `walk/poses.json`, `walk/images/`.

### Capture recipe

- Turntable: a flat disc (or a lazy Susan) with a textured top, turned by hand in steady 10 degree steps (or slowly and
  continuously, the phone picks frames by image change); the object stays centred on the axis. The box the phone draws
  should be centred on the axis.
- Phone on a stand 0.35 to 0.50 m from the axis (the object fills about half of the image height), not touched during a
  turn (the app warns when it moves more than 1 cm or 1.5 degrees).
- Two turns at different tilts: about 15 degrees and about 40 degrees elevation above the horizontal as seen from the
  object centre (raise the stand or tilt the phone between the turns). 36 frames per turn (10 degrees apart) is the tested
  setting; 72 per turn is the cap.
- Light: soft and steady (a window with the sun off the object, or two lamps at the sides); no flash; the light must not
  move relative to the PHONE (it does not turn with the object). Textured surfaces match, glossy or plain ones do not.
- Background: plain, matte and far away. Anything textured behind the object stays put while the object turns; the server
  removes static pixels from the masks, but it costs features near the silhouette and it cannot invent texture on a
  uniformly coloured object.
- Hybrid: do the normal walk-around scan first (12 to 24 well spread keyframes), then the spin without moving the object
  or the turntable between them; the spin model is registered to the walk-around through the shared photos, so the first
  spin frame may start at any angle.

### What runs

SPIN (no pose priors):

1. Masks. The phone mask is the 2 cm padded hull of the box at the starting orientation: at 0.3 m the padding is 60 to
   90 px wide and holds static ground. Per turn (frames with the same phone pose) the per-pixel median over the frames is
   the static scene; pixels equal to it are background; final mask = phone mask AND not-static. (Measured on the synthetic
   scene without this: most "inliers" between consecutive frames were the static ground, two-view geometry came out
   PLANAR_OR_PANORAMIC, and the mapper broke into 15 models of 16 to 24 frames and took 8 min.)
2. COLMAP SIFT with the masks (`--ImageReader.mask_path`, file `<image name>.png`, e.g. `masks/000001.jpg.png`, checked:
   the log prints "Mask: Yes"), one PINHOLE camera from the phone intrinsics (focal refined by the mapper), exhaustive
   matching (up to 300 images; beyond that sequential with quadratic overlap), mapper with fewer global bundle
   adjustments and GPU BA (88 s instead of 264 s); the largest model must hold 60 % of the frames or the job fails with
   the likely causes.
3. Rotation axis: common axis of one circle per turn through the camera centres (per-turn radius and height, Levenberg
   Marquardt on 4 parameters). The gravity direction the phone saw (static pose rotation seen through the model's camera
   rotations) gives the sign and a cross-check (`axis_vs_phone_gravity_deg`). An orbit arc under 270 degrees keeps the
   axis at the gravity direction.
4. Scale: `camera_to_axis_m / mean orbit radius`, frame-count weighted over the turns (so turns at different heights or
   distances average like the phone's mean camera centre). The independent cross-check from `camera_height_above_plane_m`
   is reported as `scale.scale_check_from_camera_height.ratio_to_scale` and never used.
5. Frame: axis = +Y, origin on the axis at the support plane (the lowest strong horizontal layer of the sparse points when
   it is within 3 cm of the phone's plane, else the phone's plane), metres; the manifest box becomes a box on the axis with
   horizontal size = the box diagonal (the box yaw about the axis is arbitrary). The text model is rewritten in that frame
   (COLMAP 4.2.1 `model_transformer` collapsed the points with a 3x4 [sR|t] file, so it is not used), then
   `image_undistorter`, OpenMVS (level 1, level 0 for quality DETAILED), `measure_obj`. The mesh is in that frame.

HYBRID:

1. One database with `spin/*.jpg` (masked) and `walk/*.jpg` (all-white masks: COLMAP 4.2.1 skips an image whose mask is
   missing once `mask_path` is set), exhaustive matching over all pairs.
2. Walk-around only: the known-pose path (ARCore poses fixed, focal refined) with its own OpenMVS chain:
   `measures.walk_only`.
3. Spin SfM as above; the spin SfM points are matched to the walk points through the database's verified matches between
   spin and walk photos (votes per point pair), then a RANSAC 7-DoF Umeyama (spin model to the ARCore frame, scale free).
   The axis/box prior (`scale_ratio_vs_camera_to_axis`) is only a check. The match-based fit resolves the 4-fold symmetry
   of a cube, which a geometric ICP started from the axis prior cannot.
4. Fused: the spin camera poses carried into the ARCore frame (`R' = R_m R^T`, `t' = s t_m - R' t`) join the ARCore walk
   poses in ONE known-pose reconstruction (point_triangulator on all matches, focal-only bundle adjustment, OpenMVS level
   1, `measure_obj` with the phone box and plane): `measures.fused`. If the registration fails (fewer than 6 shared
   points) the result is the walk-around only and `stats.fused` is false.

`result.json` of a hybrid job: `measures` = the fused measures plus two keys, `walk_only` and `fused`, each shaped like
`measures` (`object_dims`, `volume_m3` {low, high, recommended}, `volume_variants_m3`); `stats.images`, `stats.sparse`
{registered_images, points, mean_reprojection_error_px} (fused model), `stats.sparse_walk_only`, `stats.registration`
{correspondences, image_pairs, inliers, scale_spin_model_to_metres, rmse_mm, scale_ratio_vs_camera_to_axis},
`stats.spin_scale_check`, `stats.fused`. A spin job returns the usual `measures`, `stats.scale` (method, scale, orbit radii,
axis fit residuals, support plane source and its offset from the phone plane) and `stats.masks`.

### Measured accuracy (synthetic, 2026-10-03)

Scene (`tests/synth/render_scene.py spin OUT_DIR` writes `spin_job.zip` and `hybrid_job.zip`): 200 mm textured cube on a
textured 0.44 m disc, static textured ground, static camera 0.40 m from the axis, 1280 x 960, fx = 1000, two turns of 36
frames (10 degree steps, 15 and 40 degrees elevation); the cube and disc turn while the ground does not; the phone poses
carry 2 mm / 0.1 degree noise; masks are the phone's padded box hull; ARCore frame = render frame turned 37 degrees and
shifted. Hybrid adds 24 walk-around frames (two rings of 12, 0.5 m, 25 and 50 degrees, true poses) and a 3 mm-noise
depth cloud. Tests (`pytest -m gpu_e2e`): spin sides +-3 mm and volume +-4 %; hybrid sides +-2.5 mm.

| run | sides L / W / H (mm) | error (mm) | volume (cm3) | volume error |
|---|---|---|---|---|
| walk-around only (24 frames, known poses) | 204.20 / 202.19 / 201.93 | +4.20 / +2.19 / +1.93 | 8241 | +3.0 % |
| spin only (72 frames, no pose priors, scale from `camera_to_axis_m`) | 201.26 / 201.06 / 201.73 | +1.26 / +1.06 / +1.73 | 8130 | +1.6 % |
| hybrid fused (24 + 72 frames) | 200.86 / 200.77 / 200.38 | +0.86 / +0.77 / +0.38 | 8059 | +0.7 % |
| (reference: 60-frame orbit, true poses, phone job below, GPU densify) | 202.53 / 201.64 / 201.20 | +2.53 / +1.64 / +1.20 | 8133 | +1.7 % |

Fused mesh against the true cube: mean 0.08 mm, p95 0.18 mm, p99 0.45 mm. Spin internals: 72 of 72 frames registered in one
model, reprojection error 0.29 px; the camera centres fit two coaxial circles to 0.008 % of the radius; the fitted axis is
0.013 degrees from the phone's gravity direction; the camera-height scale cross-check agrees to 0.05 %. Hybrid registration:
13,322 inlier point pairs of 14,858, rmse 0.24 mm, scale 0.9996 of the camera-distance scale.

Wall time (s; OpenMVS 2.3.0 densify on the GPU, ReconstructMesh / RefineMesh / TextureMesh on the CPU, level 1):

| stage | spin, 72 frames | hybrid, 24 + 72 frames (two OpenMVS chains) |
|---|---|---|
| masks (static background) | 3 | 4 |
| features + matching | 11 | 23 |
| spin mapper | 77 | 78 |
| known-pose models + BA (walk, fused) | | 20 |
| spin registration | | 3 |
| undistort + InterfaceCOLMAP | 3 | 3 |
| densify (GPU) | 26 | 64 |
| ReconstructMesh | 44 | 137 |
| RefineMesh | 98 | 236 |
| TextureMesh | 22 | 44 |
| total | 285 s (4.8 min) | 612 s (10.2 min) |

OpenMVS level 0 (4x denser) was tried on the same scenes: the stray points at the cube edges and base inflate the oriented box
by 1.5 to 3 mm (phone cube height 203.5 mm, hybrid walk-only +5.1 mm), so the photo paths use level 1 (quality DETAILED: 0).

### What limits it, and what is not verified

- SPIN SCALE IS THE PHONE'S NUMBER. The spin reconstruction has no metric information of its own; its scale error equals the
  error of `camera_to_axis_m` divided by it: a box centre 1 cm off the true axis at 0.40 m scales every side by 2.5 % (5 mm
  on 200 mm). The synthetic job gives the exact distance, so the table is the best case. The camera-height cross-check is
  independent but equally ARCore-grade. Hybrid takes its scale from the walk-around poses instead
  (`scale_ratio_vs_camera_to_axis` shows how far the phone's distance was off).
- The phone's mask hull follows neither the turning object (a cube's corners swing out to a 141 mm radius, the hull
  reaches 120 mm) nor excludes the background (step 1). For turntable captures a vertical cylinder of radius (half box
  diagonal + 2 cm) around the axis would be the right mask; the server tolerates the box hull because it intersects it with
  the dynamic pixels.
- Not verified: real phone footage (autofocus, rolling shutter, ARCore drift on a static phone, a plain-coloured or glossy
  object, a hand-turned object that wobbles off the axis); the hybrid fusion with real ARCore walk-around poses (the
  known-pose path treats them as exact and refines only the focal length, so their cm-level error goes straight into the
  fused model; the synthetic poses are exact); the `cloud.ply` of a hybrid job is not used by the server; spin jobs with
  more than 300 images (quadratic-overlap sequential matching, untested); the CPU fallback of the densify inside a spin job.

## Measured accuracy and timing (synthetic scenes, this PC, 2026-10-03)

PC: Ryzen 5 5600X (12 threads), 16 GB, RTX 3060 12 GB, COLMAP 4.2.1 CUDA, OpenMVS 2.4.0 (depth-maps by the 2.3.0
binary on the GPU, see "Known problems"). The tables below are the FIRST runs (OpenMVS depth-maps on the CPU); the GPU re-run
is in "Re-run with GPU depth-maps". The scenes have known
geometry and are rendered by `tests/synth/render_scene.py` (numpy ray caster, textured boxes, 2x2 supersampling):

- Phone: 200 mm cube with 1024 px random textures on a 2 x 2 m textured ground, 60 views on two orbits (radius 0.45 m,
  heights 0.15 / 0.35 m above the top face), 1280 x 960, fx = fy = 1000, true poses in the ARCore convention.
  Variant `per_image_focal_jitter`: focal length +-1 % per image.
- Drone: 10 x 6 x 4 m textured house on a 60 x 60 m textured ground, 40 images (5 x 8 snake grid, 30 m altitude, 75 %
  overlap, 12 of them oblique at the house), 1600 x 1200, EXIF GPS (WGS84 around 33.0 N, 117.0 W), DJI XMP,
  FocalLength 6.72 mm, FC3170. Variant `noisy_gps`: 0.5 m horizontal / 1 m vertical GPS noise per image.

Run the end-to-end tests (about 35 min with GPU depth-maps; scenes are rendered once into `_e2e_data/`, gitignored):

```powershell
$env:ARMEASURE_E2E = "1"; C:\venvs\armeasure-pc\Scripts\python.exe -m pytest -q -m gpu_e2e -s
```

Plain `pytest -q` skips them (`@pytest.mark.gpu_e2e`; also skipped when a tool is missing). Each test writes
`_e2e_data/report_<name>.json`.

### Phone cube (PHOTOGRAMMETRY), result.json vs truth 200 x 200 x 200 mm = 8000 cm3

| run | sides L / W / H (mm) | error (mm) | volume (cm3) | volume error | mesh to truth, cube part: rms / p99 / max (mm) | sparse: images, points, reproj. error |
|---|---|---|---|---|---|---|
| shared intrinsics | 201.65 / 201.40 / 202.08 | +1.65 / +1.40 / +2.08 | 8117 | +1.5 % | 0.26 / 1.05 / 1.97 | 60 / 60, 54164, 0.40 px |
| focal jitter 1 % | 202.16 / 201.62 / 201.87 | +2.16 / +1.62 / +1.87 | 8132 | +1.7 % | 0.29 / 1.11 / 2.19 | 60 / 60 |

The mesh surface lies within 0.3 mm rms of the true cube; the +1.4 to +2.2 mm in the box sides is the oriented box of
the mesh vertices touching the few outliers of the cube edges and base (largest 2.2 mm). Tolerance in the test: sides +-3 mm,
volume +-3 %, cube mesh rms < 0.75 mm.

### Drone house (DRONE_PHOTOS), cloud and result.json vs truth

| run | images registered | alignment rmse (m) | house x / y / height (m) | scale error | ground plane tilt | ground_area_m2 vs 3600 m2 | house centre error |
|---|---|---|---|---|---|---|---|
| exact GPS | 40 / 40 | 0.003 | 9.961 / 5.946 / 4.016 (truth 10 / 6 / 4) | -0.30 % | 0.002 deg | 3622.6 (+0.63 %) | 0.00 / 0.02 m |
| noisy GPS 0.5 / 1 m | 40 / 40 | 0.992 (the GPS noise) | 9.969 / 5.943 / 3.997 | -0.45 % | 0.73 deg | 3614.3 (+0.40 %) | -0.41 / -0.10 m, ground height -0.30 m |

Scale error = mean of the three house ratios minus 1. With noisy GPS the model is still metric to about 0.5 %, but it is
shifted by the GPS error (0.4 m horizontal, 0.3 m vertical here) and tilted 0.7 deg, because the camera centres all lie in
one plane (30 m altitude) so the GPS fit cannot pin the tilt: absolute position and slope are only as good as the GPS, as
the "Expected accuracy" section says; distances inside the model are good. Wall points are sparse (8 to 32 points):
the oblique views reconstruct the roof and ground, hardly the walls, so `extent_m` z is roof height only.

### Wall-clock per stage (s), first runs, OpenMVS on the CPU

| stage | phone, 60 images | drone, 40 images |
|---|---|---|
| unpack / prepare_images | 1 | 0.5 |
| features (COLMAP SIFT, GPU) | 2.5 | 1.5 |
| matching (GPU; exhaustive / sequential) | 13 | 6.5 |
| known-pose model + triangulate / mapper | 10 | 79 |
| bundle adjust / georeference | 2.5 | 2 |
| undistort + InterfaceCOLMAP | 2 | 2 |
| densify (OpenMVS, CPU, level 1 / level 2) | 320 | 212 |
| ReconstructMesh | 38 | 38 |
| RefineMesh | 87 | 59 |
| TextureMesh | 13 | 5 |
| collect + measure / cloud clean + ortho | 3 | 12 |
| total | 491 s (8.2 min) | 418 s (7.0 min) |

### Re-run with GPU depth-maps (2026-10-03, after the OpenMVS finding below; all six `gpu_e2e` tests pass)

| run | result | wall time (s) |
|---|---|---|
| phone, shared intrinsics (level 1) | sides 202.53 / 201.64 / 201.20 mm, volume +1.7 %, mesh to truth rms 0.26 / p99 1.07 / max 2.03 mm | 322 (was 491) |
| phone, focal jitter | sides 201.85 / 201.58 / 201.26 mm, volume +1.5 %, mesh rms 0.25 mm | 289 |
| drone, exact GPS (level 1, was level 2 on the CPU) | house 10.034 x 5.930 x 4.017 m, scale -0.13 %, tilt 0.001 deg, ground area +2.3 % | 288 (was 418) |
| drone, noisy GPS | house 10.020 x 5.922 x 4.006 m, scale -0.31 %, tilt 0.72 deg, ground area +2.1 % | 276 |

Densify is 20 to 25 s on the GPU (phone level 1; 320 s on the CPU); the remaining time is ReconstructMesh (100 s),
RefineMesh (110 s) and TextureMesh (20 s) on the CPU. The phone path now runs OpenMVS at level 1 for all qualities except
DETAILED (level 0): on the GPU, level 0 gave 203.5 mm height (+3.5 mm), the oriented box inflated by stray edge points.

### Known problems found by these runs

- OpenMVS 2.4.0 CUDA depth-map estimation failed on this PC for every option set tried (`CUDA error at
  UtilCUDADevice.h:54: named symbol not found (code 500)`, GPU initialises fine; `CUDA_FORCE_PTX_JIT`, eager module
  loading, `--cuda-device 0` change nothing). ROOT CAUSE (found 2026-10-03): the `DensifyPointCloud.exe` in
  `OpenMVS_Windows_x64_CUDA.7z` (SHA-256 6aac6b14...1621, equal to the API digest) contains device code for sm_89 only (the
  only `sm_` string in the binary besides a PTX `.target sm_20` stub), i.e. RTX 40xx; the RTX 3060 is sm_86, so the first
  kernel lookup fails. Same report: https://github.com/cdcseacave/openMVS/issues/1267 (RTX 3070, sm_86, v2.4.0); the
  maintainer's fix is https://github.com/cdcseacave/openMVS/pull/1271 (merged 2026-05-13, CMake `native` architecture and
  other crash fixes) but there is NO release after v2.4.0 (2026-01-20: assets macOS arm64, Ubuntu x64, Windows x64 zip =
  CPU build, Windows x64 CUDA 7z), so there is nothing to download that contains it; building master needs CUDA Toolkit,
  MSVC and vcpkg, none installed here. WORKAROUND THAT WORKS: the v2.3.0 asset `OpenMVS_Windows_x64.7z`
  (https://github.com/cdcseacave/openMVS/releases/tag/v2.3.0) carries PTX for sm_50/72/75; the driver JIT-compiles it for
  sm_86. Its DensifyPointCloud ran on the RTX 3060 in 20 s (level 1) / 77 s (level 0) on the phone scene versus 320 s /
  23 min on the CPU, and the 2.4.0 ReconstructMesh/RefineMesh/TextureMesh read its output. The driver uses it when
  `tools/openmvs_alt/v230/DensifyPointCloud.exe` exists (`INSTALL_PHOTOGRAMMETRY.md` 2b); the GitHub API lists no digest for
  that old asset, so its SHA-256 (E1B7AE31...84CA) could not be checked against one. Still kept: the CPU fallback
  (`--cuda-device -2`, ONE level coarser) when the GPU run reports a CUDA error.
- RefineMesh at `--resolution-level 0` on the 1.8 M-face phone mesh ended without output; level 1 works and also
  decimates the mesh, which makes TextureMesh 20 x faster, so the drivers use it.

Run-to-run spread (second full run, same images; COLMAP mapper and OpenMVS are not bit-reproducible): phone sides
201.66 to 201.72 mm (jitter 201.85 to 202.28 mm), volume +1.5 % / +1.7 %; drone house 9.973 / 5.952 / 4.016 m, scale
-0.23 %; noisy GPS scale -0.46 %, tilt 0.73 deg. Wall time of that run: phone 547 s, phone jitter 605 s, drone 560 s,
drone noisy 566 s (about 10 % slower than the first run, same PC with other load).
