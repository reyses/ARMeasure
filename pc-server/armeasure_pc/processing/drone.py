"""DRONE_PHOTOS: DJI photos (EXIF GPS, no phone poses) -> COLMAP SfM -> GPS georeference (ENU) -> OpenMVS.

Stages: unpack/collect -> images_meta.json -> downscale per quality -> feature_extractor (OPENCV, one camera per
image size, focal prior from EXIF) -> sequential_matcher (overlap 10, time-ordered names) or exhaustive_matcher ->
mapper -> model_aligner on ENU reference positions (ref_is_gps 0, robust 3 m) -> image_undistorter -> OpenMVS
(Interface/Densify/Reconstruct/Refine/Texture) -> cloud_clean.ply, mesh.obj, texture.png, ortho.png, measures.
The frame of every output is local ENU metres (x east, y north, z up) with the origin at the first image's GPS.
"""
from __future__ import annotations

import json
import shutil
import time
from pathlib import Path

import numpy as np

from .common import Ctx, JobError, read_manifest, safe_extract, versions
from .dronemeta import build_table, has_gps, time_ordered, write_table
from .geo import (camera_from_meta, convex_hull_area, fit_ground_plane, geodetic_to_enu, gsd_cm_per_px)
from .photogrammetry import (collect_outputs, detect_tools, missing_tools_message, pick_flag, run_cmd,
                             openmvs_chain)

# quality -> (max image side px or None for full resolution, DensifyPointCloud --resolution-level)
QUALITY = {"QUICK": (1600, 2), "FINE": (2400, 1), "DETAILED": (None, 0)}
DEFAULT_QUALITY = "FINE"
JPEG_EXT = (".jpg", ".jpeg")
RAW_EXT = (".dng",)
MIN_IMAGES = 3
SEQ_OVERLAP = 10
EXHAUSTIVE_MAX = 300
ALIGN_MAX_ERROR_M = 3.0
GROUND_BAND_M = 0.5


# ---------------------------------------------------------------- validation (shared by app, CLI, worker)
def check_manifest(man: dict) -> str | None:
    q = man.get("quality")
    if q is not None and (not isinstance(q, str) or q.strip().upper() not in QUALITY):
        return f"unknown quality {q!r} (use QUICK, FINE or DETAILED)"
    g = man.get("gcp")
    if g is not None and not isinstance(g, list):
        return "manifest gcp must be a list (reserved, ignored)"
    return None


def image_files(names: list[str]) -> list[str]:
    return [n for n in names if n.lower().endswith(JPEG_EXT + RAW_EXT) and not n.endswith("/")
            and not Path(n).name.startswith(".")]


def select_images(files: list[Path]) -> tuple[list[Path], list[str]]:
    """JPEG files to process. A DNG is skipped when a JPEG with the same stem exists (JPG+RAW shooting);
    a DNG without a JPEG cannot be read by COLMAP/Pillow and is an error. Returns (jpegs, notes)."""
    jpg = sorted((f for f in files if f.suffix.lower() in JPEG_EXT), key=lambda p: p.name)
    stems = {f.stem.lower() for f in jpg}
    raw_only = [f.name for f in files if f.suffix.lower() in RAW_EXT and f.stem.lower() not in stems]
    if raw_only:
        raise JobError(f"DNG without a matching JPG is not supported ({len(raw_only)} file(s), e.g. "
                       f"{raw_only[0]}): shoot JPG or JPG+RAW so each DNG has a JPG next to it")
    if len({f.name.lower() for f in jpg}) != len(jpg):
        raise JobError("duplicate image file names (the pipeline flattens folders); rename them")
    notes = []
    n_dng = sum(f.suffix.lower() in RAW_EXT for f in files)
    if n_dng:
        notes.append(f"{n_dng} DNG file(s) ignored (their JPG twins are used)")
    return jpg, notes


def folder_images(folder: Path) -> list[Path]:
    return sorted(p for p in folder.iterdir() if p.is_file() and p.suffix.lower() in JPEG_EXT + RAW_EXT
                  and not p.name.startswith("."))


def validate_folder(folder: Path) -> str | None:
    if not folder.is_dir():
        return f"{folder} is not a folder"
    n = len(folder_images(folder))
    if n < MIN_IMAGES:
        deeper = any(len(folder_images(d)) >= MIN_IMAGES for d in folder.iterdir() if d.is_dir())
        return (f"{n} image(s) directly in {folder}; need at least {MIN_IMAGES} JPG/DNG"
                + (" (images are only in a sub-folder: pass that folder, e.g. DCIM\\100MEDIA)" if deeper else ""))
    return None


# ---------------------------------------------------------------- pure helpers
def quat_to_rot(q) -> np.ndarray:
    w, x, y, z = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def read_centres(model_txt: Path) -> dict[str, np.ndarray]:
    """Camera centres (-R^T t) from a COLMAP TEXT model's images.txt, keyed by image name."""
    out, lines = {}, [ln for ln in (model_txt / "images.txt").read_text().splitlines() if not ln.startswith("#")]
    for ln in lines[::2]:
        f = ln.split()
        if len(f) < 10:
            continue
        r = quat_to_rot([float(v) for v in f[1:5]])
        out[" ".join(f[9:])] = -r.T @ np.array([float(v) for v in f[5:8]])
    return out


def enu_references(rows: list[dict]) -> tuple[dict[str, np.ndarray], tuple[float, float, float]]:
    """ENU positions (m) of every GPS-tagged image; origin = the first image (by name) with GPS."""
    g = [r for r in rows if has_gps(r)]
    org = (g[0]["lat"], g[0]["lon"], g[0]["alt_m"])
    enu = geodetic_to_enu([r["lat"] for r in g], [r["lon"] for r in g], [r["alt_m"] for r in g], org)
    return {r["file"]: enu[i] for i, r in enumerate(g)}, org


def alignment_rmse(centres: dict[str, np.ndarray], refs: dict[str, np.ndarray]) -> tuple[float, float, int]:
    """(3D rmse m, horizontal rmse m, n) between model camera centres and the GPS ENU positions."""
    names = [n for n in centres if n in refs]
    if not names:
        return float("nan"), float("nan"), 0
    d = np.array([centres[n] - refs[n] for n in names])
    return float(np.sqrt((d ** 2).sum(1).mean())), float(np.sqrt((d[:, :2] ** 2).sum(1).mean())), len(names)


def measure_cloud(pts: np.ndarray) -> dict:
    """extent_m and ground_area_m2 of an ENU cloud. ground_area = 2D convex hull of the points within 0.5 m of
    the RANSAC ground plane (lowest 40 % of z); falls back to all points when no plane is found."""
    ext = pts.max(0) - pts.min(0)
    plane = fit_ground_plane(pts)
    if plane is None:
        area, note = convex_hull_area(pts[:, :2]), "no ground plane found; hull of all points"
        h = None
    else:
        n, d = plane
        near = pts[np.abs(pts @ n + d) <= GROUND_BAND_M]
        area, note = convex_hull_area(near[:, :2]), None
        h = (n, d)
    return {"extent_m": {"x": round(float(ext[0]), 3), "y": round(float(ext[1]), 3), "z": round(float(ext[2]), 3)},
            "ground_area_m2": round(area, 2), "_plane": h, "_note": note}


def gsd_estimate(rows: list[dict], proc_scale: float, fallback_height_m: float | None) -> dict | None:
    """GSD (cm/px) = H * pixel pitch / focal. H = median XMP RelativeAltitude (height above the take-off point,
    only an AGL approximation) else the model's camera height above the fitted ground plane."""
    cams = [(camera_from_meta(r), r) for r in rows]
    cams = [(c, r) for c, r in cams if c]
    if not cams:
        return None
    pitch = float(np.median([c["pixel_pitch_mm"] for c, _ in cams]))
    focal = float(np.median([r["focal_length_mm"] for _, r in cams]))
    rel = [r["relative_altitude_m"] for r in rows if r.get("relative_altitude_m") is not None]
    if rel and np.median(rel) > 1.0:
        h, src = float(np.median(rel)), "xmp_relative_altitude"
    elif fallback_height_m and fallback_height_m > 1.0:
        h, src = float(fallback_height_m), "model_camera_height_above_ground_plane"
    else:
        return None
    full = gsd_cm_per_px(h, focal, pitch)
    return {"height_m": round(h, 2), "height_source": src, "full_res_cm_per_px": round(full, 4),
            "processed_cm_per_px": round(full / proc_scale, 4), "sensor_source": cams[0][0]["source"]}


# ---------------------------------------------------------------- job source
def read_source(upload_zip: Path) -> dict | None:
    p = upload_zip.with_name("source.json")
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise JobError(f"source.json unreadable: {e}")


def prepare_images(jpgs: list[Path], dest: Path, max_side: int | None, ctx: Ctx) -> dict[str, tuple[int, int, int, int]]:
    """Copy or downscale into dest. Returns {name: (orig_w, orig_h, new_w, new_h)}."""
    from PIL import Image
    dest.mkdir(parents=True, exist_ok=True)
    sizes = {}
    for i, src in enumerate(jpgs):
        ctx.progress(0.03 + 0.04 * i / max(len(jpgs), 1), "prepare_images")
        try:
            with Image.open(src) as im:
                w, h = im.size
                if max_side and max(w, h) > max_side:
                    s = max_side / max(w, h)
                    nw, nh = max(int(round(w * s)), 1), max(int(round(h * s)), 1)
                    im.convert("RGB").resize((nw, nh), Image.LANCZOS).save(
                        dest / src.name, "JPEG", quality=95, exif=im.info.get("exif", b""))
                else:
                    nw, nh = w, h
                    shutil.copyfile(src, dest / src.name)
        except OSError as e:
            raise JobError(f"image {src.name} unreadable: {e}")
        sizes[src.name] = (w, h, nw, nh)
    return sizes


# ---------------------------------------------------------------- pipeline
def run(upload_zip: Path, outdir: Path, ctx: Ctx) -> dict:
    t0 = time.time()
    tools = detect_tools()
    msg = missing_tools_message(tools)
    if msg:
        raise JobError(msg)
    colmap = tools["colmap"]

    ctx.progress(0.01, "collect")
    src = read_source(upload_zip)
    if src is not None:
        folder = Path(src["path"])
        problem = validate_folder(folder)
        if problem:
            raise JobError(problem)
        manifest, name, files = {"quality": src.get("quality")}, src.get("name"), folder_images(folder)
    else:
        root = ctx.workdir / "in"
        safe_extract(upload_zip, root)
        manifest = read_manifest(root, required=False)
        name = manifest.get("name") if isinstance(manifest.get("name"), str) else None
        files = [p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in JPEG_EXT + RAW_EXT
                 and not p.name.startswith(".")]
    problem = check_manifest(manifest)
    if problem:
        raise JobError(problem)
    quality = (manifest.get("quality") or DEFAULT_QUALITY).strip().upper()
    max_side, level = QUALITY[quality]
    jpgs, notes = select_images(files)
    if len(jpgs) < MIN_IMAGES:
        raise JobError(f"need at least {MIN_IMAGES} JPG images, got {len(jpgs)}")

    rows = build_table(jpgs)
    job_dir = ctx.workdir.parent
    write_table(rows, job_dir / "images_meta.json")
    if sum(has_gps(r) for r in rows) < MIN_IMAGES:
        raise JobError("fewer than 3 images carry EXIF GPS (lat, lon, altitude): the model cannot be scaled to "
                       "metres. Copy the original DJI files (not re-exported or messenger copies)")
    refs, origin = enu_references(rows)
    if len(refs) < len(rows):
        notes.append(f"{len(rows) - len(refs)} image(s) without GPS are used for matching but not for alignment")

    ws = ctx.workdir / "colmap"
    ws.mkdir(exist_ok=True)
    db, log = ws / "database.db", ws / "log.txt"
    image_dir = ctx.workdir / "images"
    sizes = prepare_images(jpgs, image_dir, max_side, ctx)
    by_name = {r["file"]: r for r in rows}

    # ---- features: one OPENCV camera per processed image size, focal prior from EXIF
    gpu = pick_flag(colmap, "feature_extractor", "FeatureExtraction.use_gpu", "SiftExtraction.use_gpu")
    maxsz = pick_flag(colmap, "feature_extractor", "FeatureExtraction.max_image_size",
                      "SiftExtraction.max_image_size")
    params_flag = pick_flag(colmap, "feature_extractor", "ImageReader.camera_params")
    list_flag = pick_flag(colmap, "feature_extractor", "image_list_path")
    groups: dict[tuple, list[str]] = {}
    for n, (_, _, nw, nh) in sizes.items():
        groups.setdefault((nw, nh), []).append(n)
    if len(groups) > 1 and not list_flag:
        raise JobError("this COLMAP build has no --image_list_path; images of different sizes cannot be grouped")
    for gi, ((nw, nh), names) in enumerate(sorted(groups.items())):
        ctx.progress(0.08 + 0.07 * gi / len(groups), "features")
        args = [colmap, "feature_extractor", "--database_path", db, "--image_path", image_dir,
                "--ImageReader.camera_model", "OPENCV", "--ImageReader.single_camera", "1"]
        if list_flag:
            lst = ws / f"images_{nw}x{nh}.txt"
            lst.write_text("\n".join(sorted(names)) + "\n")
            args += [list_flag, lst]
        cams = [camera_from_meta(by_name[n]) for n in names]
        cams = [c for c in cams if c]
        if cams and params_flag:
            ow = sizes[names[0]][0]
            f = float(np.median([c["focal_px"] for c in cams])) * nw / ow
            args += [params_flag, f"{f:.4f},{f:.4f},{nw / 2:.2f},{nh / 2:.2f},0,0,0,0"]
        if maxsz:
            args += [maxsz, str(max(nw, nh))]
        if gpu:
            args += [gpu, "1"]
        run_cmd(args, ctx, log)

    # ---- matching
    ordered = time_ordered(rows)
    n_img = len(jpgs)
    gpu_m = None
    if ordered or n_img > EXHAUSTIVE_MAX:
        matcher = "sequential_matcher"
        gpu_m = pick_flag(colmap, matcher, "FeatureMatching.use_gpu", "SiftMatching.use_gpu")
        args = [colmap, matcher, "--database_path", db]
        ov = pick_flag(colmap, matcher, "SequentialMatching.overlap")
        if ov:
            args += [ov, str(SEQ_OVERLAP)]
        if not ordered:
            notes.append(f"{n_img} images with no capture-time order: sequential matching in name order "
                         f"may miss overlaps")
    else:
        matcher = "exhaustive_matcher"
        gpu_m = pick_flag(colmap, matcher, "FeatureMatching.use_gpu", "SiftMatching.use_gpu")
        args = [colmap, matcher, "--database_path", db]
    if gpu_m:
        args += [gpu_m, "1"]
    ctx.progress(0.17, "matching")
    run_cmd(args, ctx, log)

    # ---- mapper, best model
    ctx.progress(0.3, "mapper")
    sparse = ws / "sparse"
    sparse.mkdir(exist_ok=True)
    run_cmd([colmap, "mapper", "--database_path", db, "--image_path", image_dir, "--output_path", sparse],
            ctx, log)
    best, best_n = None, 0
    for m in sorted(p for p in sparse.iterdir() if p.is_dir()):
        txt = ws / f"txt_{m.name}"
        txt.mkdir(exist_ok=True)
        run_cmd([colmap, "model_converter", "--input_path", m, "--output_path", txt, "--output_type", "TXT"],
                ctx, log)
        n = len(read_centres(txt))
        if n > best_n:
            best, best_n = m, n
    if best is None or best_n < MIN_IMAGES:
        raise JobError(f"COLMAP registered {best_n} of {n_img} images: too little overlap, blur or texture. "
                       f"Re-fly with 75-80 % front / 70 % side overlap")
    if best_n < 0.5 * n_img:
        notes.append(f"only {best_n} of {n_img} images registered")

    # ---- georeference: ENU positions, robust 3 m alignment
    ctx.progress(0.45, "georeference")
    ref_file = ws / "ref_enu.txt"
    ref_file.write_text("".join(f"{n} {float(p[0])!r} {float(p[1])!r} {float(p[2])!r}\n"
                                for n, p in refs.items()))     # float(): numpy 2 repr is 'np.float64(..)'
    aligned = ws / "aligned"
    aligned.mkdir(exist_ok=True)
    args = [colmap, "model_aligner", "--input_path", best, "--output_path", aligned,
            "--ref_images_path", ref_file]
    fl = pick_flag(colmap, "model_aligner", "ref_is_gps")
    if fl:
        args += [fl, "0"]
    fl = pick_flag(colmap, "model_aligner", "alignment_type")
    if fl:
        args += [fl, "custom"]
    err_flag = pick_flag(colmap, "model_aligner", "alignment_max_error", "robust_alignment_max_error")
    if err_flag:
        args += [err_flag, str(ALIGN_MAX_ERROR_M)]
        if err_flag.startswith("--robust_alignment") and (fl := pick_flag(colmap, "model_aligner", "robust_alignment")):
            args += [fl, "1"]
    else:
        notes.append("this COLMAP build has no alignment max-error option: alignment was not robust")
    try:
        run_cmd(args, ctx, log)
    except JobError as e:
        raise JobError("georeferencing failed (GPS positions disagree with the model by more than "
                       f"{ALIGN_MAX_ERROR_M:g} m for too many images, or too few images have GPS). {e}")
    atxt = ws / "aligned_txt"
    atxt.mkdir(exist_ok=True)
    run_cmd([colmap, "model_converter", "--input_path", aligned, "--output_path", atxt, "--output_type", "TXT"],
            ctx, log)
    centres = read_centres(atxt)
    rmse, rmse_h, n_ref = alignment_rmse(centres, refs)

    # ---- undistort + OpenMVS
    ctx.progress(0.5, "undistort")
    dense = ws / "dense"
    args = [colmap, "image_undistorter", "--image_path", image_dir, "--input_path", aligned,
            "--output_path", dense, "--output_type", "COLMAP"]
    if max_side:
        args += ["--max_image_size", str(max_side)]
    run_cmd(args, ctx, log)
    openmvs_chain(tools, dense, level, ctx, log, notes)

    # ---- outputs
    ctx.progress(0.95, "collect")
    outdir.mkdir(parents=True, exist_ok=True)
    collect_outputs(dense, outdir)
    xyz, rgb, clean_note = clean_cloud(dense, outdir, outdir / "mesh.obj", 0.02)
    meas = measure_cloud(xyz)
    plane, plane_note = meas.pop("_plane"), meas.pop("_note")
    cam_h = None
    if plane is not None and centres:
        n, dd = plane
        c = np.array(list(centres.values()))
        cam_h = float(np.median(c @ n + dd))
    scale = float(np.median([nw / ow for ow, _, nw, _ in sizes.values()]))
    gsd = gsd_estimate(rows, scale, cam_h)
    px_m = (gsd["processed_cm_per_px"] / 100.0) if gsd else max(float(np.ptp(xyz[:, :2], axis=0).max()) / 2048, 1e-3)
    ctx.progress(0.98, "ortho")
    ortho_info = write_ortho(outdir / "ortho.png", xyz, rgb, px_m)

    for r in rows:
        r["registered"] = r["file"] in centres
        r["enu_m"] = [round(float(v), 3) for v in refs[r["file"]]] if r["file"] in refs else None
    write_table(rows, job_dir / "images_meta.json")
    write_table(rows, outdir / "images_meta.json")
    if plane_note:
        notes.append(plane_note)
    if clean_note:
        notes.append(clean_note)
    measures = {**meas, "gsd_cm_per_px_estimate": gsd["processed_cm_per_px"] if gsd else None,
                "images_used": n_img, "images_registered": len(centres),
                "alignment_rmse_m": round(rmse, 3) if np.isfinite(rmse) else None}
    return {"measures": measures,
            "stats": {"backend": "pc", "duration_ms": int((time.time() - t0) * 1000), "name": name,
                      "quality": quality, "matcher": matcher,
                      "frame": "local ENU metres (x east, y north, z up); origin = GPS of the first image",
                      "origin_lat_lon_alt_m": [round(float(v), 7) for v in origin],
                      "alignment": {"rmse_3d_m": round(rmse, 3), "rmse_horizontal_m": round(rmse_h, 3),
                                    "n_reference_images": n_ref, "robust_max_error_m": ALIGN_MAX_ERROR_M},
                      "gsd": gsd, "ortho": ortho_info,
                      "versions": {**versions(), "colmap": str(colmap)}, "notes": notes}}


def clean_cloud(dense: Path, outdir: Path, mesh_obj: Path, voxel_m: float):
    """cloud_clean.ply from OpenMVS' coloured dense cloud (statistical outlier removal); returns (xyz, rgb u8, note)."""
    import open3d as o3d
    note = None
    ply = dense / "scene_dense.ply"
    pc = o3d.io.read_point_cloud(str(ply)) if ply.exists() else o3d.geometry.PointCloud()
    if len(pc.points) < 100:
        mesh = o3d.io.read_triangle_mesh(str(mesh_obj))
        pc = o3d.geometry.PointCloud(mesh.vertices)
        z = np.asarray(pc.points)[:, 2]
        t = ((z - z.min()) / (np.ptp(z) + 1e-9))[:, None]
        pc.colors = o3d.utility.Vector3dVector(np.hstack([t, 1 - np.abs(2 * t - 1), 1 - t]))
        note = "dense cloud missing: cloud_clean.ply and ortho.png come from mesh vertices, height-tinted"
    if len(pc.points) < 100:
        raise JobError("OpenMVS produced no usable points")
    if not pc.has_colors():
        pc.paint_uniform_color([0.6, 0.6, 0.6])
    if len(pc.points) > 6_000_000:
        pc = pc.voxel_down_sample(voxel_m)
    pc, _ = pc.remove_statistical_outlier(nb_neighbors=20, std_ratio=2.5)
    o3d.io.write_point_cloud(str(outdir / "cloud_clean.ply"), pc)
    xyz = np.asarray(pc.points)
    rgb = (np.clip(np.asarray(pc.colors), 0, 1) * 255).astype(np.uint8)
    return xyz, rgb, note


def write_ortho(path: Path, xyz: np.ndarray, rgb: np.ndarray, gsd_m: float) -> dict:
    from PIL import Image
    from .ortho import draw_scale_bar, render_ortho
    img, info = render_ortho(xyz, rgb, gsd_m, max_px=4096)
    img, bar = draw_scale_bar(img, info["pixel_size_m"])
    Image.fromarray(img).save(path)
    return {"pixel_size_m": round(info["pixel_size_m"], 5), "width_px": info["width_px"],
            "height_px": info["height_px"], "scale_bar_m": bar["length_m"],
            "note": "approximate nadir splat of the dense cloud, not a survey-grade orthomosaic"}
