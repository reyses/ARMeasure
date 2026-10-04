"""SPIN and HYBRID capture (manifest "capture": "spin" | "hybrid"), PHOTOGRAMMETRY job.

SPIN: the phone is fixed on a stand and the object turns (turntable / by hand); the phone's poses are all (nearly) the
same, so they say nothing about the object-relative geometry.  Pipeline:
  1. static-background removal: per turn (frames with the same phone pose) the per-pixel median over the frames is the
     static scene; a pixel that equals the median in a frame is background.  Final mask = phone box mask AND dynamic.
     (The phone's mask is a 2 cm padded box hull; at 0.3 m that ring is 60-90 px wide and holds static ground.)
  2. COLMAP WITHOUT pose priors: SIFT with masks, one camera from the phone intrinsics (focal refined), exhaustive
     matching (quadratic-overlap sequential beyond 300 images; turn 1 and turn 2 must match), mapper.
  3. The model is in an arbitrary frame/scale; the cameras orbit the object.  Rotation axis = normal of the common
     circle of the camera centres (both turns on one axis, per-turn radius and height; the gravity direction seen by
     the phone is the sign reference and a cross-check).  Scale = camera_to_axis_m / mean orbit radius.  Frame: axis ->
     +Y, origin on the axis at the support plane (the lowest strong horizontal layer of the sparse points when it is
     within 3 cm of the phone's plane, else the phone's plane from camera_height_above_plane_m).
  4. OpenMVS + measure_obj with the manifest box re-expressed in that frame (box centred on the axis, horizontal size =
     the box diagonal because the box yaw relative to the object frame is arbitrary).
HYBRID: the walk-around part runs the known-pose path (metric, ARCore frame); the spin part runs as above; the spin
SfM points are matched to the walk-around points through SIFT matches shared in ONE database (spin masked, walk not),
a RANSAC 7-DoF Umeyama gives the similarity spin model -> ARCore frame, and the spin camera poses converted with it
join the walk poses in one known-pose reconstruction (the fused result).  result.json measures = fused, with
measures.walk_only and measures.fused both in the standard shape.
"""
from __future__ import annotations

import json
import math
import shutil
import sqlite3
import time
from pathlib import Path

import numpy as np

from .common import Ctx, JobError, versions
from .photogrammetry import (dense_and_measure, intrinsics_key, known_pose_model, load_poses, patch_database_cameras,
                             pick_flag, run_cmd)
from .posec import FLIP, pose16_to_matrix, quat_wxyz_to_rot, rot_to_quat_wxyz

STATIC_DIFF = 10          # gray levels: |frame - median| above this is "dynamic"
MIN_TURN_FRAMES = 8       # a pose group needs this many frames for the median background
EXHAUSTIVE_MAX = 300
LEVEL = 1                 # OpenMVS --resolution-level for SPIN and HYBRID (quality DETAILED: 0, see KNOWN_POSE_LEVEL)
PLANE_ACCEPT_M = 0.03     # data-derived support plane accepted when this close to the phone's


# ----------------------------------------------------------------------------------------------- COLMAP text model
def read_model_txt(d: Path) -> dict:
    """cameras / images (with 2D points) / points3D from a COLMAP TEXT model."""
    cams = {}
    for ln in (d / "cameras.txt").read_text().splitlines():
        if ln and not ln.startswith("#"):
            f = ln.split()
            cams[int(f[0])] = {"model": f[1], "w": int(f[2]), "h": int(f[3]), "params": [float(x) for x in f[4:]]}
    lines = [ln for ln in (d / "images.txt").read_text().splitlines() if not ln.startswith("#")]
    images = {}
    for i in range(0, len(lines) - 1, 2):
        f = lines[i].split()
        if len(f) < 10:
            continue
        p = lines[i + 1].split()
        arr = np.array(p, float).reshape(-1, 3) if p else np.zeros((0, 3))
        images[int(f[0])] = {"q": np.array(f[1:5], float), "t": np.array(f[5:8], float), "cam": int(f[8]),
                             "name": " ".join(f[9:]), "xy": arr[:, :2], "pid": arr[:, 2].astype(np.int64),
                             "raw2d": lines[i + 1]}
    pts, rest = {}, {}
    p3 = d / "points3D.txt"
    if p3.exists():
        for ln in p3.read_text().splitlines():
            if ln and not ln.startswith("#"):
                f = ln.split(None, 4)
                pts[int(f[0])] = np.array(f[1:4], float)
                rest[int(f[0])] = f[4] if len(f) > 4 else ""
    return {"cameras": cams, "images": images, "points": pts, "points_rest": rest,
            "cameras_raw": [ln for ln in (d / "cameras.txt").read_text().splitlines() if ln and not ln.startswith("#")]}


def write_similarity_model(src: dict, out: Path, s: float, T: np.ndarray, O: np.ndarray) -> None:
    """Write `src` (read_model_txt) transformed by X' = s T (X - O): cameras R' = R T^T, t' = s (R O + t), points
    s T (X - O); 2D points and tracks unchanged."""
    out.mkdir(parents=True, exist_ok=True)
    (out / "cameras.txt").write_text("# CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n" + "\n".join(src["cameras_raw"]) + "\n")
    with open(out / "images.txt", "w") as f:
        f.write("# IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n")
        for iid, im in sorted(src["images"].items()):
            R = quat_wxyz_to_rot(im["q"])
            q = [float(v) for v in rot_to_quat_wxyz(R @ T.T)]
            t = [float(v) for v in s * (R @ O + im["t"])]
            f.write(f"{iid} {q[0]!r} {q[1]!r} {q[2]!r} {q[3]!r} {t[0]!r} {t[1]!r} {t[2]!r} {im['cam']} {im['name']}\n"
                    f"{im['raw2d']}\n")
    with open(out / "points3D.txt", "w") as f:
        f.write("# POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[] as (IMAGE_ID, POINT2D_IDX)\n")
        for pid, xyz in src["points"].items():
            x = [float(v) for v in s * T @ (xyz - O)]
            f.write(f"{pid} {x[0]!r} {x[1]!r} {x[2]!r} {src['points_rest'][pid]}\n")


def camera_centre(img: dict) -> np.ndarray:
    return -quat_wxyz_to_rot(img["q"]).T @ img["t"]


def to_txt(colmap: Path, model: Path, out: Path, ctx: Ctx, log: Path) -> Path:
    out.mkdir(parents=True, exist_ok=True)
    run_cmd([colmap, "model_converter", "--input_path", model, "--output_path", out, "--output_type", "TXT"],
            ctx, log)
    return out


# ----------------------------------------------------------------------------------------------- turns, masks
def split_turns(poses: list[dict], max_move_m: float = 0.01, max_turn_deg: float = 2.0) -> list[int]:
    """Turn label per image (in order): a new turn starts when the phone pose leaves the first pose of the current one."""
    labels, ref, turn = [], None, 0
    for p in poses:
        M = pose16_to_matrix(p["pose"])
        if ref is not None:
            ang = math.degrees(math.acos(np.clip((np.trace(ref[:3, :3].T @ M[:3, :3]) - 1) / 2, -1, 1)))
            if np.linalg.norm(M[:3, 3] - ref[:3, 3]) > max_move_m or ang > max_turn_deg:
                turn, ref = turn + 1, M
        else:
            ref = M
        labels.append(turn)
    return labels


def _boxmean(a: np.ndarray, k: int) -> np.ndarray:
    pad = k // 2
    c = np.cumsum(np.cumsum(np.pad(a.astype(np.float32), pad, mode="edge"), 0), 1)
    c = np.pad(c, ((1, 0), (1, 0)))
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def refine_masks(entries: list[dict], out_dir: Path, ctx: Ctx) -> dict:
    """Write out_dir/<name>.png for every entry: phone mask AND dynamic pixels (see module doc). Returns stats."""
    from PIL import Image
    out_dir.mkdir(parents=True, exist_ok=True)
    stats = {"turns": {}, "mean_mask_fraction_phone": None, "mean_mask_fraction_final": None}
    fr_a, fr_b = [], []
    turns = sorted({e["turn"] for e in entries})
    for ti in turns:
        grp = [e for e in entries if e["turn"] == ti]
        gray = []
        for e in grp:
            with Image.open(e["path"]) as im:
                im = im.convert("L")
                gray.append(np.asarray(im.resize((im.width // 2, im.height // 2), Image.BILINEAR)))
        use_median = len(grp) >= MIN_TURN_FRAMES
        med = np.median(np.stack(gray), axis=0) if use_median else None
        for e, g in zip(grp, gray):
            ctx.check()
            h2, w2 = g.shape
            if e.get("mask") and Path(e["mask"]).exists():
                with Image.open(e["mask"]) as m:
                    full = np.asarray(m.convert("L")) > 127
                phone = np.asarray(Image.fromarray(full.astype(np.uint8) * 255).resize((w2, h2), Image.NEAREST)) > 127
            else:
                full, phone = None, np.ones((h2, w2), bool)
            if use_median:
                dyn = np.abs(g.astype(np.int16) - med.astype(np.int16)) > STATIC_DIFF
                core = _boxmean(dyn, 9) > 0.3
                keep = _boxmean(core, 9) > 0
                m2 = phone & keep
            else:
                m2 = phone
            W, H = e["w"], e["h"]
            final = np.asarray(Image.fromarray(m2.astype(np.uint8) * 255).resize((W, H), Image.NEAREST))
            dst = out_dir / (e["name"] + ".png")
            dst.parent.mkdir(parents=True, exist_ok=True)
            Image.fromarray(final).save(dst)
            fr_a.append(float(phone.mean()))
            fr_b.append(float((final > 0).mean()))
        stats["turns"][str(ti)] = {"frames": len(grp), "median_background": use_median}
    stats["mean_mask_fraction_phone"] = round(float(np.mean(fr_a)), 4)
    stats["mean_mask_fraction_final"] = round(float(np.mean(fr_b)), 4)
    return stats


# ----------------------------------------------------------------------------------------------- COLMAP stages
def extract_and_match(colmap: Path, db: Path, image_dir: Path, mask_dir: Path | None, poses: list[dict], ctx: Ctx,
                      log: Path, notes: list, p0: float, p1: float) -> str:
    same = len({intrinsics_key(p["intrinsics"]) for p in poses}) == 1
    gpu = pick_flag(colmap, "feature_extractor", "FeatureExtraction.use_gpu", "SiftExtraction.use_gpu")
    ctx.progress(p0, "features")
    args = [colmap, "feature_extractor", "--database_path", db, "--image_path", image_dir,
            "--ImageReader.camera_model", "PINHOLE",
            "--ImageReader.single_camera" if same else "--ImageReader.single_camera_per_image", "1"]
    if mask_dir is not None:
        args += ["--ImageReader.mask_path", mask_dir]
    if gpu:
        args += [gpu, "1"]
    run_cmd(args, ctx, log)
    patch_database_cameras(db, poses, image_dir)
    ctx.progress((p0 + p1) / 2, "matching")
    n = len(poses)
    if n <= EXHAUSTIVE_MAX:
        matcher = "exhaustive_matcher"
        args = [colmap, matcher, "--database_path", db]
    else:
        matcher = "sequential_matcher"
        args = [colmap, matcher, "--database_path", db]
        for names, val in ((("SequentialMatching.overlap",), "10"), (("SequentialMatching.quadratic_overlap",), "1")):
            fl = pick_flag(colmap, matcher, *names)
            if fl:
                args += [fl, val]
        notes.append(f"{n} images: sequential matching with quadratic overlap (no exhaustive pairs)")
    gpu_m = pick_flag(colmap, matcher, "FeatureMatching.use_gpu", "SiftMatching.use_gpu")
    if gpu_m:
        args += [gpu_m, "1"]
    run_cmd(args, ctx, log)
    return matcher


def spin_mapper(colmap: Path, ws: Path, db: Path, image_dir: Path, names: list[str], ctx: Ctx, log: Path,
                p0: float, tag: str = "spin") -> tuple[Path, dict]:
    """Mapper over `names` only; returns (TEXT model dir of the largest model, info)."""
    ctx.progress(p0, "mapper")
    sparse = ws / f"sparse_{tag}"
    sparse.mkdir(exist_ok=True)
    lst = ws / f"{tag}_images.txt"
    lst.write_text("\n".join(names) + "\n")
    args = [colmap, "mapper", "--database_path", db, "--image_path", image_dir, "--output_path", sparse,
            "--Mapper.ba_refine_principal_point", "0"]
    fl = pick_flag(colmap, "mapper", "Mapper.image_list_path")
    if fl:
        args += [fl, lst]
    fl = pick_flag(colmap, "mapper", "Mapper.max_num_models")
    if fl:
        args += [fl, "3"]
    for name, val in (("Mapper.ba_global_frames_ratio", "1.5"), ("Mapper.ba_global_points_ratio", "1.5")):
        fl = pick_flag(colmap, "mapper", name)         # fewer global bundle adjustments: 3.5 x faster, same model
        if fl:
            args += [fl, val]
    gpu_ba = pick_flag(colmap, "mapper", "Mapper.ba_use_gpu")
    try:
        run_cmd([*args, gpu_ba, "1"] if gpu_ba else args, ctx, log)
    except JobError:
        if not gpu_ba:
            raise
        run_cmd(args, ctx, log)                        # GPU bundle adjustment unavailable: CPU
    best, best_n, sizes = None, 0, []
    for m in sorted(p for p in sparse.iterdir() if p.is_dir()):
        txt = to_txt(colmap, m, ws / f"txt_{tag}_{m.name}", ctx, log)
        n = len(read_model_txt(txt)["images"])
        sizes.append(n)
        if n > best_n:
            best, best_n = txt, n
    if best is None or best_n < max(3, int(0.6 * len(names))):
        raise JobError(f"COLMAP registered {best_n} of {len(names)} spin frames (models of {sizes}): the object needs "
                       f"texture, steady light and a background that does not move with it; mask or cover the "
                       f"background, and keep 10 degrees or less between frames")
    return best, {"registered": best_n, "of": len(names), "model_sizes": sizes}


# ----------------------------------------------------------------------------------------------- axis, scale, frame
def _basis(u: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    a = np.array([1.0, 0, 0]) if abs(u[0]) < 0.9 else np.array([0, 1.0, 0])
    e1 = np.cross(u, a)
    e1 /= np.linalg.norm(e1)
    return e1, np.cross(u, e1)


def _circle_residuals(x: np.ndarray, C: np.ndarray, lab: np.ndarray, u0, f1, f2, m, scale) -> np.ndarray:
    u = u0 + x[0] * scale * f1 + x[1] * scale * f2
    u = u / np.linalg.norm(u)
    g1, g2 = _basis(u)
    d = C - m
    px, py, pz = d @ g1 - x[2] * scale, d @ g2 - x[3] * scale, d @ u
    rad = np.hypot(px, py)
    res = []
    for k in np.unique(lab):
        s = lab == k
        res.append(rad[s] - rad[s].mean())
        res.append(pz[s] - pz[s].mean())
    return np.concatenate(res)


def fit_rotation_axis(C: np.ndarray, lab: np.ndarray, gravity: np.ndarray | None, free_axis: bool = True) -> dict:
    """Common axis of circles through the camera centres C (n, 3), labels `lab` = turn per centre (each turn its own
    radius and height). `gravity`: unit vector (model frame, pointing UP) from the phone's gravity seen by every
    camera, the sign reference. free_axis False keeps the axis at the gravity direction (arc too short to trust)."""
    m = C.mean(axis=0)
    cov = np.cov((C - m).T)
    w, v = np.linalg.eigh(cov)
    u0 = v[:, 0]                                     # normal of the best-fit plane of all centres (initial)
    if gravity is not None and u0 @ gravity < 0:
        u0 = -u0
    if gravity is not None and not free_axis:
        u0 = gravity / np.linalg.norm(gravity)
    f1, f2 = _basis(u0)
    d = C - m
    x, y = d @ f1, d @ f2
    A = np.column_stack([2 * x, 2 * y, np.ones(len(x))])
    sol = np.linalg.lstsq(A, x * x + y * y, rcond=None)[0]
    scale = float(np.sqrt(max(sol[2] + sol[0] ** 2 + sol[1] ** 2, 1e-12)))
    p = np.array([0.0, 0.0, sol[0] / scale, sol[1] / scale])
    if free_axis:
        lam = 1e-3
        cur = _circle_residuals(p, C, lab, u0, f1, f2, m, scale)
        for _ in range(60):
            J = np.empty((len(cur), 4))
            for j in range(4):
                dp = p.copy()
                dp[j] += 1e-6
                J[:, j] = (_circle_residuals(dp, C, lab, u0, f1, f2, m, scale) - cur) / 1e-6
            step = np.linalg.solve(J.T @ J + lam * np.eye(4), -J.T @ cur)
            new = _circle_residuals(p + step, C, lab, u0, f1, f2, m, scale)
            if new @ new < cur @ cur:
                p, cur, lam = p + step, new, lam * 0.3
                if np.abs(step).max() < 1e-10:
                    break
            else:
                lam *= 10
    else:
        # axis fixed: centre only (2 params): Gauss-Newton on the same residual with x[0], x[1] = 0
        def fun(q):
            return _circle_residuals(np.array([0.0, 0.0, q[0], q[1]]), C, lab, u0, f1, f2, m, scale)
        q, cur = p[2:], fun(p[2:])
        for _ in range(60):
            J = np.empty((len(cur), 2))
            for j in range(2):
                dq = q.copy()
                dq[j] += 1e-6
                J[:, j] = (fun(dq) - cur) / 1e-6
            step = np.linalg.lstsq(J, -cur, rcond=None)[0]
            q = q + step
            cur = fun(q)
            if np.abs(step).max() < 1e-10:
                break
        p = np.array([0.0, 0.0, q[0], q[1]])
    u = u0 + p[0] * scale * f1 + p[1] * scale * f2
    u /= np.linalg.norm(u)
    g1, g2 = _basis(u)
    centre = m + (p[2] * g1 + p[3] * g2) * scale            # a point on the axis
    dd = C - centre
    rad = np.hypot(dd @ g1, dd @ g2)
    z = dd @ u
    radii, heights, rms_r, rms_z = {}, {}, [], []
    for k in np.unique(lab):
        s = lab == k
        radii[int(k)], heights[int(k)] = float(rad[s].mean()), float(z[s].mean())
        rms_r.append(rad[s] - rad[s].mean())
        rms_z.append(z[s] - z[s].mean())
    az = np.sort(np.degrees(np.arctan2(dd @ g2, dd @ g1)) % 360.0)
    gaps = np.diff(np.concatenate([az, [az[0] + 360.0]]))
    return {"u": u, "point": centre, "radii": radii, "heights": heights, "n": {int(k): int((lab == k).sum()) for k in np.unique(lab)},
            "rms_radius": float(np.sqrt(np.mean(np.concatenate(rms_r) ** 2))),
            "rms_height": float(np.sqrt(np.mean(np.concatenate(rms_z) ** 2))),
            "coverage_deg": float(360.0 - gaps.max()), "free_axis": free_axis}


def support_height(points: np.ndarray, u: np.ndarray, centre: np.ndarray, radius: float, z_max: float) -> float | None:
    """Height (along u, relative to `centre`) of the lowest strong horizontal layer of the sparse points inside the
    object column (within 0.7 x orbit radius of the axis, below z_max): the turntable top."""
    d = points - centre
    z = d @ u
    rho = np.linalg.norm(d - np.outer(z, u), axis=1)
    sel = (rho < 0.7 * radius) & (z < z_max)
    if sel.sum() < 100:
        return None
    zs = z[sel]
    bin_w = 0.004 * radius
    edges = np.arange(zs.min() - bin_w, zs.max() + 2 * bin_w, bin_w)
    cnt, _ = np.histogram(zs, edges)
    sm = np.convolve(cnt, [1, 2, 1], mode="same")
    peaks = [i for i in range(1, len(sm) - 1) if sm[i] >= sm[i - 1] and sm[i] > sm[i + 1] and sm[i] >= 0.3 * sm.max()]
    if not peaks:
        return None
    i = peaks[0]
    lo, hi = edges[max(i - 1, 0)], edges[min(i + 2, len(edges) - 1)]
    near = zs[(zs >= lo) & (zs <= hi)]
    return float(np.median(near))


def spin_frame(C: np.ndarray, lab: np.ndarray, Rm: np.ndarray, gravity_cam: np.ndarray, points: np.ndarray,
               manifest: dict) -> dict:
    """Axis fit, scale and the model -> object-frame similarity X_obj = s * T @ (X - O) (T rows: e1, u, e3)."""
    # gravity (up) in the model frame, per camera: R_m^T FLIP g_arcore_cam
    g = np.array([Rm[i].T @ FLIP @ gravity_cam[i] for i in range(len(C))])
    g_mean = g.mean(axis=0)
    g_spread = float(np.degrees(np.arccos(np.clip((g / np.linalg.norm(g, axis=1, keepdims=True)) @ (g_mean / np.linalg.norm(g_mean)), -1, 1))).max())
    g_up = g_mean / np.linalg.norm(g_mean)
    probe = fit_rotation_axis(C, lab, g_up, free_axis=True)
    fit = probe if probe["coverage_deg"] >= 270 else fit_rotation_axis(C, lab, g_up, free_axis=False)
    u = fit["u"]
    if u @ g_up < 0:
        u = -u
    axis_vs_gravity = float(np.degrees(np.arccos(np.clip(u @ g_up, -1, 1))))
    n = fit["n"]
    r_model = sum(n[k] * fit["radii"][k] for k in n) / sum(n.values())
    d_cam = manifest.get("camera_to_axis_m")
    if not d_cam or d_cam <= 0:
        raise JobError("manifest camera_to_axis_m is missing: the spin scale cannot be set")
    s = float(d_cam) / r_model
    h_cam = sum(n[k] * fit["heights"][k] for k in n) / sum(n.values())        # mean camera height along u, rel. to centre
    centre = fit["point"]
    zp = support_height(points, u, centre, r_model, min(fit["heights"].values()) - 0.02 * r_model) if len(points) else None
    H = manifest.get("camera_height_above_plane_m")
    z_phone = h_cam - float(H) / s if H is not None else None
    info = {"scale_method": "camera_to_axis_m / mean orbit radius (point-weighted over the turns)",
            "scale": s, "orbit_radius_model_units": r_model, "camera_to_axis_m": float(d_cam),
            "orbit_radii_model": fit["radii"], "turn_frames": n,
            "axis_fit": {"rms_radius_pct": round(100 * fit["rms_radius"] / r_model, 4),
                         "rms_height_pct": round(100 * fit["rms_height"] / r_model, 4),
                         "coverage_deg": round(fit["coverage_deg"], 1), "free_axis": fit["free_axis"],
                         "axis_vs_phone_gravity_deg": round(axis_vs_gravity, 3),
                         "gravity_spread_deg": round(g_spread, 3)}}
    # support plane height (model units along u, relative to the axis centre)
    plane_src, z0 = "none", z_phone
    if zp is not None:
        info["support_plane_data_height_above_phone_plane_mm"] = None if z_phone is None else round(1000 * s * (zp - z_phone), 2)
    if zp is not None and (z_phone is None or abs(s * (zp - z_phone)) <= PLANE_ACCEPT_M):
        z0, plane_src = zp, "sparse points (turntable top)"
    elif z_phone is not None:
        plane_src = "phone plane (camera_height_above_plane_m)"
    if z0 is None:
        raise JobError("no support plane: manifest camera_height_above_plane_m missing and no horizontal layer found")
    info["support_plane_source"] = plane_src
    if zp is not None and H is not None and (h_cam - zp) > 0:
        s_h = float(H) / (h_cam - zp)
        info["scale_check_from_camera_height"] = {"scale": s_h, "ratio_to_scale": round(s_h / s, 4)}
    e1v = C[0] - centre
    e1v = e1v - (e1v @ u) * u
    e1 = e1v / np.linalg.norm(e1v)
    T = np.vstack([e1, u, np.cross(e1, u)])
    O = centre + u * z0
    return {"s": s, "T": T, "O": O, "info": info}


def spin_entries(root: Path, manifest: dict, poses: list[dict], prefix: str = "") -> list[dict]:
    """One dict per spin image: db name, path, mask path, phone pose / intrinsics, turn label, size."""
    from PIL import Image
    labels = split_turns(poses)
    masks_dir = root / str(manifest.get("masks_dir") or "masks/")
    out = []
    for p, t in zip(poses, labels):
        base = Path(p["file"]).name
        path = root / "images" / base
        if not path.exists():
            raise JobError(f"spin image {p['file']} missing from the ZIP")
        with Image.open(path) as im:
            w, h = im.size
        mk = masks_dir / (base + ".png")
        out.append({"name": prefix + base, "path": path, "mask": mk if mk.exists() else None, "pose": p["pose"],
                    "intrinsics": p["intrinsics"], "turn": t, "w": w, "h": h, "file": p["file"]})
    return out


def object_manifest(manifest: dict) -> dict:
    """Manifest for measure_obj in the spin object frame: box centred on the axis at the support plane."""
    box = manifest.get("box")
    if not box:
        raise JobError("manifest box is missing")
    w, h, d = box["size"]
    diag = math.hypot(w, d)
    return {"box": {"center": [0.0, h / 2, 0.0], "size": [diag, h, diag], "yaw_deg": 0.0},
            "support_plane": {"normal": [0.0, 1.0, 0.0], "d": 0.0}}


def spin_model_to_object(colmap: Path, ws: Path, txt: Path, entries: list[dict], manifest: dict, ctx: Ctx,
                         log: Path) -> tuple[Path, dict, dict]:
    """Fit axis + scale on the spin model (TEXT dir `txt`), write the transformed model; returns (dir, frame, model)."""
    model = read_model_txt(txt)
    by_name = {e["name"]: e for e in entries}
    ids = sorted(model["images"])
    C = np.array([camera_centre(model["images"][i]) for i in ids])
    Rm = np.array([quat_wxyz_to_rot(model["images"][i]["q"]) for i in ids])
    lab = np.array([by_name[model["images"][i]["name"]]["turn"] for i in ids])
    grav = np.array([pose16_to_matrix(by_name[model["images"][i]["name"]]["pose"])[:3, :3].T @ np.array([0, 1.0, 0])
                     for i in ids])
    pts = np.array(list(model["points"].values())) if model["points"] else np.zeros((0, 3))
    fr = spin_frame(C, lab, Rm, grav, pts, manifest)
    out = ws / "sparse_object"
    write_similarity_model(model, out, fr["s"], fr["T"], fr["O"])
    return out, fr, model


# ----------------------------------------------------------------------------------------------- SPIN job
def prepare_spin(root: Path, manifest: dict, ws: Path, ctx: Ctx, notes: list) -> tuple[list[dict], dict, Path | None]:
    poses = load_poses(root / "poses.json")
    entries = spin_entries(root, manifest, poses)
    if len(entries) < 12:
        raise JobError(f"spin capture needs at least 12 frames, got {len(entries)}")
    mstats = refine_masks(entries, ws / "masks", ctx)
    if not any(e["mask"] for e in entries):
        notes.append("no masks in the ZIP: static background removed by the per-turn median only")
    return entries, mstats, ws / "masks"


def run_spin(root: Path, manifest: dict, outdir: Path, ctx: Ctx, tools: dict, t0: float) -> dict:
    colmap = tools["colmap"]
    ws = ctx.workdir / "colmap"
    ws.mkdir(exist_ok=True)
    db, log = ws / "database.db", ws / "log.txt"
    notes: list = []
    ctx.progress(0.03, "masks")
    entries, mstats, mask_dir = prepare_spin(root, manifest, ws, ctx, notes)
    poses = [{"file": e["file"], "name": e["name"], "pose": e["pose"], "intrinsics": e["intrinsics"]} for e in entries]
    matcher = extract_and_match(colmap, db, root / "images", mask_dir, poses, ctx, log, notes, 0.06, 0.2)
    txt, minfo = spin_mapper(colmap, ws, db, root / "images", [e["name"] for e in entries], ctx, log, 0.25)
    ctx.progress(0.45, "spin_frame")
    obj_model, fr, model = spin_model_to_object(colmap, ws, txt, entries, manifest, ctx, log)
    mark = log.stat().st_size
    run_cmd([colmap, "model_analyzer", "--path", obj_model], ctx, log)
    from .photogrammetry import sparse_stats
    sparse = sparse_stats(log.read_bytes()[mark:].decode("utf-8", "replace"))
    notes.append(f"spin: {len(entries)} frames, {len(set(e['turn'] for e in entries))} turn(s), no pose priors; "
                 f"scale {fr['s']:.5f} from camera_to_axis_m; support plane from {fr['info']['support_plane_source']}")
    outdir.mkdir(parents=True, exist_ok=True)
    level = 0 if str(manifest.get("quality") or "").upper() == "DETAILED" else LEVEL
    measures = dense_and_measure(tools, colmap, root / "images", obj_model, ws / "dense", outdir, object_manifest(manifest),
                                 level, ctx, log, notes, p0=0.5)
    info = fr["info"]
    return {"measures": measures,
            "stats": {"backend": "pc", "duration_ms": int((time.time() - t0) * 1000), "capture": "spin",
                      "images": len(entries), "sparse": sparse, "matcher": matcher, "mapper": minfo, "masks": mstats,
                      "scale": info, "frame": "object frame: +Y = rotation axis, origin on the axis at the support "
                      "plane, metres; the mesh is expressed in it (the box yaw about the axis is arbitrary)",
                      "versions": {**versions(), "colmap": str(colmap)}, "notes": notes}}


# ----------------------------------------------------------------------------------------------- HYBRID
def umeyama(src: np.ndarray, dst: np.ndarray) -> tuple[float, np.ndarray, np.ndarray]:
    """dst ~ s R src + t (least squares, Umeyama 1991)."""
    mu_s, mu_d = src.mean(0), dst.mean(0)
    xs, xd = src - mu_s, dst - mu_d
    U, S, Vt = np.linalg.svd(xd.T @ xs / len(src))
    D = np.eye(3)
    if np.linalg.det(U) * np.linalg.det(Vt) < 0:
        D[2, 2] = -1
    R = U @ D @ Vt
    var = (xs ** 2).sum() / len(src)
    s = float(np.trace(np.diag(S) @ D) / var)
    return s, R, mu_d - s * R @ mu_s


def ransac_similarity(src: np.ndarray, dst: np.ndarray, thr: float, iters: int = 3000, seed: int = 0):
    """Returns (s, R, t, inlier mask) or None."""
    n = len(src)
    if n < 4:
        return None
    rng = np.random.default_rng(seed)
    best, best_cnt = None, 0
    for _ in range(iters):
        idx = rng.choice(n, 4, replace=False)
        try:
            s, R, t = umeyama(src[idx], dst[idx])
        except np.linalg.LinAlgError:
            continue
        if not np.isfinite(s) or s <= 0:
            continue
        r = np.linalg.norm(s * src @ R.T + t - dst, axis=1)
        cnt = int((r < thr).sum())
        if cnt > best_cnt:
            best, best_cnt = (s, R, t), cnt
    if best is None or best_cnt < 6:
        return None
    s, R, t = best
    for _ in range(4):                                         # refit on the inliers, tightening the threshold
        r = np.linalg.norm(s * src @ R.T + t - dst, axis=1)
        inl = r < thr
        if inl.sum() < 6:
            break
        s, R, t = umeyama(src[inl], dst[inl])
        r = np.linalg.norm(s * src @ R.T + t - dst, axis=1)
        thr = max(2.5 * float(np.sqrt(np.mean(r[inl] ** 2))), 0.0005)
    r = np.linalg.norm(s * src @ R.T + t - dst, axis=1)
    return s, R, t, r < thr


def cross_correspondences(db: Path, spin: dict, walk: dict) -> tuple[np.ndarray, np.ndarray, int]:
    """3D-3D pairs (spin model point, walk model point) voted by the database's verified matches between spin and walk
    images. Returns (src xyz in the spin model, dst xyz in the walk model, number of image pairs used)."""
    con = sqlite3.connect(str(db))
    try:
        rows = con.execute("SELECT pair_id, rows, data FROM two_view_geometries WHERE rows > 0").fetchall()
    finally:
        con.close()
    votes: dict[tuple[int, int], int] = {}
    pairs = 0
    for pid, nrows, data in rows:
        i2 = pid % 2147483647
        i1 = pid // 2147483647
        for a_id, b_id, swap in ((i1, i2, False), (i2, i1, True)):
            if a_id in spin["images"] and b_id in walk["images"]:
                m = np.frombuffer(data, np.uint32).reshape(-1, 2)
                ka, kb = (m[:, 1], m[:, 0]) if swap else (m[:, 0], m[:, 1])
                pa = spin["images"][a_id]["pid"][ka]
                pb = walk["images"][b_id]["pid"][kb]
                ok = (pa >= 0) & (pb >= 0)
                for x, y in zip(pa[ok], pb[ok]):
                    votes[(int(x), int(y))] = votes.get((int(x), int(y)), 0) + 1
                pairs += 1
    best: dict[int, tuple[int, int]] = {}
    for (x, y), v in votes.items():
        if x in spin["points"] and y in walk["points"] and (x not in best or v > best[x][1]):
            best[x] = (y, v)
    keys = [x for x, (y, v) in best.items() if v >= 2]
    src = np.array([spin["points"][x] for x in keys]) if keys else np.zeros((0, 3))
    dst = np.array([walk["points"][best[x][0]] for x in keys]) if keys else np.zeros((0, 3))
    return src, dst, pairs


def spin_poses_in_world(model: dict, s: float, R: np.ndarray, t: np.ndarray) -> list[dict]:
    """Spin camera poses (COLMAP world->camera, spin model frame) -> metric world frame of X_w = s R X_m + t:
    R' = R_m R^T, t' = s t_m - R' t."""
    out = []
    for img in model["images"].values():
        Rm = quat_wxyz_to_rot(img["q"])
        Rn = Rm @ R.T
        tn = s * img["t"] - Rn @ t
        out.append({"name": img["name"], "q": rot_to_quat_wxyz(Rn), "t": tn})
    return out


def run_hybrid(root: Path, manifest: dict, outdir: Path, ctx: Ctx, tools: dict, t0: float) -> dict:
    colmap = tools["colmap"]
    ws = ctx.workdir / "colmap"
    ws.mkdir(exist_ok=True)
    db, log = ws / "database.db", ws / "log.txt"
    notes: list = []
    walk_meta = manifest.get("walk") or {}
    walk_poses_path = root / str(walk_meta.get("poses") or "walk/poses.json")
    if not walk_poses_path.exists():
        raise JobError("hybrid capture needs walk/poses.json (the walk-around keyframes with ARCore poses)")
    walk_poses = load_poses(walk_poses_path)
    walk_dir = root / str(walk_meta.get("dir") or "walk/")
    # ---- joint image folder: names spin/xxx.jpg, walk/xxx.jpg
    joint = ws / "joint"
    (joint / "spin").mkdir(parents=True, exist_ok=True)
    (joint / "walk").mkdir(parents=True, exist_ok=True)
    ctx.progress(0.02, "masks")
    spin_p = load_poses(root / "poses.json")
    entries = spin_entries(root, manifest, spin_p, prefix="spin/")
    if len(entries) < 12:
        raise JobError(f"spin part needs at least 12 frames, got {len(entries)}")
    for e in entries:
        shutil.copyfile(e["path"], joint / e["name"])
        e["path"] = joint / e["name"]
    mstats = refine_masks(entries, ws / "masks", ctx)
    wentries = []
    for p in walk_poses:
        f = Path(p["file"])
        src = walk_dir / f if (walk_dir / f).exists() else root / f
        if not src.exists():
            raise JobError(f"walk image {p['file']} missing from the ZIP")
        name = "walk/" + f.name
        shutil.copyfile(src, joint / name)
        from PIL import Image
        with Image.open(src) as im:         # COLMAP 4 fails on a missing mask once mask_path is set: all-white mask
            wm = ws / "masks" / (name + ".png")
            wm.parent.mkdir(parents=True, exist_ok=True)
            Image.new("L", im.size, 255).save(wm)
        wentries.append({"file": p["file"], "name": name, "pose": p["pose"], "intrinsics": p["intrinsics"]})
    poses_all = [{"file": e["file"], "name": e["name"], "pose": e["pose"], "intrinsics": e["intrinsics"]}
                 for e in entries] + wentries
    matcher = extract_and_match(colmap, db, joint, ws / "masks", poses_all, ctx, log, notes, 0.05, 0.2)
    from .photogrammetry import sparse_stats
    # ---- walk-around only: known poses, metric
    image_dir = joint
    ba_w, sparse_w = known_pose_model(colmap, ws, db, image_dir, wentries, ctx, log, tag="_walk", p0=0.25)
    level = 0 if str(manifest.get("quality") or "").upper() == "DETAILED" else LEVEL
    walk_measures = dense_and_measure(tools, colmap, image_dir, ba_w, ws / "dense_walk", None, manifest, level, ctx, log,
                                      notes, p0=0.35)
    # ---- spin SfM in the same database
    txt_s, minfo = spin_mapper(colmap, ws, db, image_dir, [e["name"] for e in entries], ctx, log, 0.5)
    _, fr, smodel = spin_model_to_object(colmap, ws, txt_s, entries, manifest, ctx, log)
    # ---- register spin model -> ARCore frame with shared matches
    ctx.progress(0.6, "register_spin")
    txt_w = to_txt(colmap, ba_w, ws / "txt_walk", ctx, log)
    wmodel = read_model_txt(txt_w)
    src, dst, npairs = cross_correspondences(db, smodel, wmodel)
    size = max(manifest["box"]["size"]) if manifest.get("box") else 0.2
    reg = ransac_similarity(src, dst, thr=0.05 * size) if len(src) >= 6 else None
    reg_info = {"correspondences": int(len(src)), "image_pairs": npairs}
    result_measures = dict(walk_measures)
    fused_ok = False
    sparse_f = sparse_w
    fused_measures = None
    if reg is None:
        notes.append("spin model could not be registered to the walk-around model (too few shared points): "
                     "walk-around result only")
    else:
        s, R, t, inl = reg
        res = np.linalg.norm(s * src[inl] @ R.T + t - dst[inl], axis=1)
        reg_info.update({"inliers": int(inl.sum()), "scale_spin_model_to_metres": s,
                         "rmse_mm": round(1000 * float(np.sqrt(np.mean(res ** 2))), 3),
                         "scale_ratio_vs_camera_to_axis": round(s / fr["s"], 4)})
        newposes = spin_poses_in_world(smodel, s, R, t)
        known = [{"name": p["name"], "q": p["q"], "t": p["t"], "file": p["name"]} for p in newposes]
        walk_cp = []
        from .posec import arcore_to_colmap
        for e in wentries:
            q, tt = arcore_to_colmap(e["pose"])
            walk_cp.append({"name": e["name"], "q": q, "t": tt, "file": e["name"]})
        allp = known + walk_cp
        # the spin frames that did not register keep no pose and drop out of the fused model
        ba_f, sparse_f = known_pose_model(colmap, ws, db, image_dir, allp, ctx, log, tag="_fused", p0=0.62,
                                          colmap_poses=True)
        outdir.mkdir(parents=True, exist_ok=True)
        fused_measures = dense_and_measure(tools, colmap, image_dir, ba_f, ws / "dense_fused", outdir, manifest, level,
                                           ctx, log, notes, p0=0.72)
        result_measures = dict(fused_measures)
        fused_ok = True
    if not fused_ok:
        outdir.mkdir(parents=True, exist_ok=True)
        from .photogrammetry import collect_outputs
        collect_outputs(ws / "dense_walk", outdir)
    measures = dict(result_measures)
    measures["walk_only"] = walk_measures
    if fused_measures is not None:
        measures["fused"] = fused_measures
    notes.append(f"hybrid: {len(wentries)} walk frames (known poses) + {len(entries)} spin frames; "
                 f"spin registered with {reg_info.get('inliers', 0)} shared points")
    return {"measures": measures,
            "stats": {"backend": "pc", "duration_ms": int((time.time() - t0) * 1000), "capture": "hybrid",
                      "images": len(wentries) + len(entries), "walk_images": len(wentries),
                      "spin_images": len(entries), "sparse": sparse_f, "sparse_walk_only": sparse_w,
                      "matcher": matcher, "mapper": minfo, "masks": mstats, "registration": reg_info,
                      "spin_scale_check": fr["info"], "fused": fused_ok,
                      "versions": {**versions(), "colmap": str(colmap)}, "notes": notes}}


def run(root: Path, manifest: dict, outdir: Path, ctx: Ctx, tools: dict, t0: float) -> dict:
    if manifest.get("capture") == "hybrid":
        return run_hybrid(root, manifest, outdir, ctx, tools, t0)
    return run_spin(root, manifest, outdir, ctx, tools, t0)
