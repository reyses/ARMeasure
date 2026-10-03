# ARMeasure PC server

PC side of the phone <-> PC processing link. Implements `docs/PROCESSING_PROTOCOL.md` (API v1, FastAPI + Open3D).
Python 3.12.10, venv `C:\venvs\armeasure-pc`, all dependencies pinned in `requirements.txt`.

## Start

```powershell
C:\venvs\armeasure-pc\Scripts\python.exe -m armeasure_pc            # LAN mode, http://<LAN IPv4>:48310
C:\venvs\armeasure-pc\Scripts\python.exe -m armeasure_pc --tunnel   # Cloudflare quick tunnel, https URL in the QR
```

Run it from `D:\ARMeasure\pc-server`. On start it prints the pairing JSON `{"v":1,"url","token","name"}`, an ASCII QR in
the console and opens `data\pairing.png` in the default image viewer (`--no-open` to skip). Scan it in the app.

Flags: `--tunnel`, `--new-token` (rotate), `--no-open`, `--host`, `--port` (default 0.0.0.0:48310), `--data-dir`.
`--tunnel` needs `%LOCALAPPDATA%\Programs\cloudflared\cloudflared.exe`. The LAN IP is the physical adapter that carries
the default route (virtual adapters such as Hyper-V, WSL, VMware, VPN are skipped).

Tests: `C:\venvs\armeasure-pc\Scripts\python.exe -m pytest` (about 10 s).

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
| `photogrammetry` | COLMAP + OpenMVS, see `INSTALL_PHOTOGRAMMETRY.md`; fails with a clear message when the tools are missing |

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

## Measured accuracy and timing (synthetic scenes, this PC, 2026-10-03)

PC: Ryzen 5 5600X (12 threads), 16 GB, RTX 3060 12 GB, COLMAP 4.2.1 CUDA, OpenMVS 2.4.0 CUDA. The scenes have known
geometry and are rendered by `tests/synth/render_scene.py` (numpy ray caster, textured boxes, 2x2 supersampling):

- Phone: 200 mm cube with 1024 px random textures on a 2 x 2 m textured ground, 60 views on two orbits (radius 0.45 m,
  heights 0.15 / 0.35 m above the top face), 1280 x 960, fx = fy = 1000, true poses in the ARCore convention.
  Variant `per_image_focal_jitter`: focal length +-1 % per image.
- Drone: 10 x 6 x 4 m textured house on a 60 x 60 m textured ground, 40 images (5 x 8 snake grid, 30 m altitude, 75 %
  overlap, 12 of them oblique at the house), 1600 x 1200, EXIF GPS (WGS84 around 33.0 N, 117.0 W), DJI XMP,
  FocalLength 6.72 mm, FC3170. Variant `noisy_gps`: 0.5 m horizontal / 1 m vertical GPS noise per image.

Run the end-to-end tests (about 33 min; scenes are rendered once into `_e2e_data/`, gitignored):

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

### Wall-clock per stage (s), OpenMVS on the CPU (see below)

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

### Known problems found by these runs

- OpenMVS 2.4.0 CUDA depth-map estimation fails on this PC for every option set tried (`CUDA error at
  UtilCUDADevice.h:54: named symbol not found (code 500)`, GPU initialises fine; `CUDA_FORCE_PTX_JIT`, eager module
  loading, `--cuda-device 0` change nothing). The drivers catch it and rerun DensifyPointCloud on the CPU with
  `--cuda-device -2` ONE `--resolution-level` coarser (phone level 1 = 640 x 480 working images, drone level 2); the
  result notes say so. At level 0 on the CPU the phone job's densify took 23 min instead of 5 min and gave sides
  200.5 / 200.3 / 199.8 mm. A working CUDA build of OpenMVS (or another version) would cut densify to about a minute.
  COLMAP itself runs SIFT extraction and matching on the GPU.
- RefineMesh at `--resolution-level 0` on the 1.8 M-face phone mesh ended without output; level 1 works and also
  decimates the mesh, which makes TextureMesh 20 x faster, so the drivers use it.

Run-to-run spread (second full run, same images; COLMAP mapper and OpenMVS are not bit-reproducible): phone sides
201.66 to 201.72 mm (jitter 201.85 to 202.28 mm), volume +1.5 % / +1.7 %; drone house 9.973 / 5.952 / 4.016 m, scale
-0.23 %; noisy GPS scale -0.46 %, tilt 0.73 deg. Wall time of that run: phone 547 s, phone jitter 605 s, drone 560 s,
drone noisy 566 s (about 10 % slower than the first run, same PC with other load).
