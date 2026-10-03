# Photogrammetry tools (not installed by the server)

The `photogrammetry` job fails with "Photogrammetry tools missing: ..." until both are present. The server looks for each
exe on PATH, then anywhere under `D:\ARMeasure\pc-server\tools\` (searched recursively, so unzip as-is).

## 1. COLMAP 4.2.1, CUDA build (RTX 3060)

https://github.com/colmap/colmap/releases/download/4.2.1/colmap-x64-windows-cuda.zip  (414,666,626 bytes, 2026-09-29)

Unzip into `D:\ARMeasure\pc-server\tools\colmap\` so that `colmap.exe` ends up inside it (or below it).
CPU-only alternative: `.../4.2.1/colmap-x64-windows-nocuda.zip` (127,731,595 bytes), much slower matching.
Release page: https://github.com/colmap/colmap/releases/tag/4.2.1

## 2. OpenMVS v2.4.0 (2026-01-20)

CUDA build (needs 7-Zip to open): https://github.com/cdcseacave/openMVS/releases/download/v2.4.0/OpenMVS_Windows_x64_CUDA.7z
CPU build: https://github.com/cdcseacave/openMVS/releases/download/v2.4.0/OpenMVS_Windows_x64.zip
Release page: https://github.com/cdcseacave/openMVS/releases/tag/v2.4.0

Unzip into `D:\ARMeasure\pc-server\tools\openmvs\`. Required exes: `InterfaceCOLMAP.exe`, `DensifyPointCloud.exe`,
`ReconstructMesh.exe`, `RefineMesh.exe`, `TextureMesh.exe`.

## What the driver runs (known-pose path)

1. `feature_extractor` (PINHOLE; `--ImageReader.single_camera 1` when all intrinsics agree to 0.5 px, otherwise
   `--ImageReader.single_camera_per_image 1` because autofocus changes the focal length), then the per-image
   intrinsics from `poses.json` are written into the database (`cameras` table).
2. `exhaustive_matcher` (up to 200 images, else `sequential_matcher`).
3. Text sparse model from the ARCore poses (`R_cw = diag(1,-1,-1) R_wc^T`, `t_cw = -R_cw t_wc`; unit test checks pixels).
4. `point_triangulator` with the poses fixed, then `bundle_adjuster` refining focal length only
   (principal point, extra params and poses fixed).
5. `image_undistorter`, `InterfaceCOLMAP`, `DensifyPointCloud --resolution-level 0`, `ReconstructMesh` (defaults, writes
   `scene_mesh.ply` only), `RefineMesh -m scene_mesh.ply --resolution-level 1` (optional, skipped on failure),
   `TextureMesh scene_dense.mvs -m <mesh>.ply --export-type obj` (the cameras come from the .mvs, the mesh from the PLY).
   OpenMVS 2.4.0 CUDA depth-maps fail on the RTX 3060 here ("CUDA error ... named symbol not found (code 500)"), so the
   driver repeats DensifyPointCloud with `--cuda-device -2` (CPU) one `--resolution-level` coarser; see README.

Option names differ between COLMAP releases, so the driver reads `colmap <command> -h` at run time and uses whichever
spelling exists. Names found in the 4.2.1 source and confirmed by running 4.2.1 on 2026-10-03:
`--FeatureExtraction.use_gpu`, `--FeatureMatching.use_gpu`, `--ImageReader.camera_model|single_camera|single_camera_per_image`,
point_triangulator `--Mapper.fix_existing_frames` and `--refine_intrinsics`, bundle_adjuster
`--BundleAdjustment.refine_focal_length|refine_principal_point|refine_extra_params|refine_rig_from_world`
(3.x spellings: `fix_existing_images`, `refine_extrinsics`).

Measured times: see README ("Measured accuracy and timing").
