"""Accuracy of the pipeline outputs against the synthetic ground truth (see render_scene.py)."""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np

HALF = 0.1
CUBE_LO = np.array([-HALF, 0.0, -HALF])
CUBE_HI = np.array([HALF, 0.2, HALF])


def dist_to_box_surface(p: np.ndarray, lo, hi) -> np.ndarray:
    """Unsigned distance (m) of points to the surface of an axis-aligned box (inside and outside)."""
    q = np.maximum(np.maximum(lo - p, p - hi), 0.0)
    out = np.linalg.norm(q, axis=1)
    inside = (out == 0)
    if inside.any():
        pi = p[inside]
        out[inside] = np.minimum(pi - lo, hi - pi).min(axis=1)
    return out


def dist_to_phone_truth(p: np.ndarray) -> np.ndarray:
    """Distance to cube surface UNION the 2 x 2 m ground (y = 0)."""
    d_cube = dist_to_box_surface(p, CUBE_LO, CUBE_HI)
    g = np.maximum(np.abs(p[:, [0, 2]]) - 1.0, 0.0)
    d_ground = np.sqrt(g[:, 0] ** 2 + g[:, 1] ** 2 + p[:, 1] ** 2)
    return np.minimum(d_cube, d_ground)


def sample_mesh(path: Path, n: int = 400_000, seed: int = 0) -> np.ndarray:
    import open3d as o3d
    m = o3d.io.read_triangle_mesh(str(path))
    if len(m.triangles) == 0:
        raise ValueError(f"{path}: mesh has no triangles")
    o3d.utility.random.seed(seed)
    return np.asarray(m.sample_points_uniformly(n).points)


def cube_truth_samples(n_per_face: int = 40_000, seed: int = 0) -> np.ndarray:
    """Points on the 5 cube faces that can be seen (top + 4 sides)."""
    rng = np.random.default_rng(seed)
    s = HALF * 2
    pts = []
    for axis, val in ((0, -HALF), (0, HALF), (2, -HALF), (2, HALF)):
        o = [i for i in range(3) if i != axis]
        p = np.zeros((n_per_face, 3))
        p[:, axis] = val
        p[:, o[0]] = rng.random(n_per_face) * s - HALF if o[0] != 1 else rng.random(n_per_face) * s
        p[:, o[1]] = rng.random(n_per_face) * s - HALF if o[1] != 1 else rng.random(n_per_face) * s
        pts.append(p)
    top = np.column_stack([rng.random(n_per_face) * s - HALF, np.full(n_per_face, 0.2),
                           rng.random(n_per_face) * s - HALF])
    pts.append(top)
    return np.vstack(pts)


def phone_geometry(mesh_obj: Path) -> dict:
    """One-sided (mesh -> truth) and reverse (truth -> mesh) distances in mm for the part of the mesh within
    30 cm (horizontal) of the cube axis, split into cube and ground parts."""
    return _phone_geometry(sample_mesh(mesh_obj))


def _knn_dist(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Nearest-neighbour distance from each point of a to the set b (Open3D KD-tree; no scipy needed)."""
    import open3d as o3d
    pc = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(b))
    tree = o3d.geometry.KDTreeFlann(pc)
    out = np.empty(len(a))
    for i, p in enumerate(a):
        _, _, d2 = tree.search_knn_vector_3d(p, 1)
        out[i] = math.sqrt(d2[0])
    return out


def _stats(d: np.ndarray) -> dict:
    return {"n": int(len(d)), "mean_mm": round(float(d.mean() * 1000), 2),
            "rms_mm": round(float(np.sqrt((d ** 2).mean()) * 1000), 2),
            "p95_mm": round(float(np.percentile(d, 95) * 1000), 2),
            "p99_mm": round(float(np.percentile(d, 99) * 1000), 2),
            "max_mm": round(float(d.max() * 1000), 2)}


def _phone_geometry(P: np.ndarray) -> dict:
    near = P[(np.abs(P[:, 0]) < 0.3) & (np.abs(P[:, 2]) < 0.3) & (P[:, 1] > -0.05) & (P[:, 1] < 0.3)]
    d = dist_to_phone_truth(near)
    on_cube = near[(near[:, 1] > 0.01) & (np.abs(near[:, 0]) < 0.115) & (np.abs(near[:, 2]) < 0.115)]
    d_cube = dist_to_box_surface(on_cube, CUBE_LO, CUBE_HI)
    T = cube_truth_samples(8000)
    rev = _knn_dist(T, near)
    return {"mesh_to_truth_all_30cm": _stats(d), "mesh_to_truth_cube_only": _stats(d_cube),
            "truth_to_mesh_cube_faces": _stats(rev),
            "hausdorff_symmetric_cube_mm": round(float(max(d_cube.max(), rev.max()) * 1000), 2)}


# ---------------------------------------------------------------------------------------------- drone
def covered_area_m2(truth: dict, min_views: int = 3, step: float = 0.5) -> float:
    """Area (m2) of the 60 x 60 m ground seen by >= min_views of the true cameras (nadir/oblique pinhole)."""
    xs = np.arange(-30 + step / 2, 30, step)
    gx, gy = np.meshgrid(xs, xs)
    G = np.column_stack([gx.ravel(), gy.ravel(), np.zeros(gx.size)])
    # exclude ground hidden under the house footprint
    G = G[~((np.abs(G[:, 0]) < 5) & (np.abs(G[:, 1]) < 3))]
    cnt = np.zeros(len(G), int)
    from render_scene import drone_views
    for v in drone_views():
        M = v["c2w"]
        Xc = (G - M[:3, 3]) @ M[:3, :3]               # camera coords (OpenGL)
        z = -Xc[:, 2]
        ok = z > 0.1
        u = v["fx"] * Xc[:, 0] / np.where(ok, z, 1) + v["cx"]
        w = v["cy"] - v["fy"] * Xc[:, 1] / np.where(ok, z, 1)
        cnt += (ok & (u >= 0) & (u < v["w"]) & (w >= 0) & (w < v["h"])).astype(int)
    return float((cnt >= min_views).sum() * step * step + 10 * 6)   # + the 10 x 6 m house footprint


def drone_geometry(result_dir: Path, truth: dict) -> dict:
    """House extent, ground plane and scale vs truth from cloud_clean.ply (ENU, origin at the first image)."""
    import open3d as o3d
    pc = o3d.io.read_point_cloud(str(result_dir / "cloud_clean.ply"))
    xyz = np.asarray(pc.points)
    c0 = np.array(truth.get("gps_enu", truth["centres_enu"])[0])      # the result frame's origin is the first GPS tag
    lo, hi = np.array(truth["house_lo"]) - c0, np.array(truth["house_hi"]) - c0       # truth in the result frame
    ground_z_true = -c0[2]
    from armeasure_pc.processing.geo import fit_ground_plane
    plane = fit_ground_plane(xyz)
    n, d = plane
    cx, cy = ((lo + hi) / 2)[:2]
    gz_fit = float(-(n[0] * cx + n[1] * cy + d) / n[2])              # fitted ground height under the house
    near_house = xyz[(xyz[:, 0] > lo[0] - 4) & (xyz[:, 0] < hi[0] + 4) & (xyz[:, 1] > lo[1] - 4) &
                     (xyz[:, 1] < hi[1] + 4)]
    h = near_house[:, 2] - gz_fit
    roof = near_house[(h > 3.0) & (h < 5.0)]
    roof_h = float(np.median(roof[:, 2] - gz_fit)) if len(roof) else float("nan")
    walls = near_house[(h > 1.0) & (h < 3.0)]
    rr = near_house[(h > 3.0) & (h < 5.0)]
    # footprint extent from the roof points (the roof is the widest flat part at height 4 m)
    ext = {}
    if len(rr) > 100:
        for ax, name in ((0, "x"), (1, "y")):
            ext[name] = float(np.percentile(rr[:, ax], 99.5) - np.percentile(rr[:, ax], 0.5))
    gcen = None
    if "x" in ext:
        gcen = np.array([(np.percentile(rr[:, a], 99.5) + np.percentile(rr[:, a], 0.5)) / 2 for a in (0, 1)])
    true_cen = (lo + hi)[:2] / 2
    ratios = [ext["x"] / 10.0, ext["y"] / 6.0, roof_h / 4.0] if "x" in ext else [float("nan")]
    scale_err = (float(np.mean(ratios)) - 1.0) * 100.0
    return {"ground_z_true_m": round(ground_z_true, 3), "ground_z_fit_m": round(gz_fit, 3),
            "ground_plane_tilt_deg": round(math.degrees(math.acos(min(1.0, abs(n[2])))), 3),
            "house_height_m": round(roof_h, 3), "house_extent_xy_m": {k: round(v, 3) for k, v in ext.items()},
            "scale_error_pct": round(scale_err, 3), "house_centre_error_m": None if gcen is None else [round(float(a), 3) for a in gcen - true_cen],
            "n_cloud_points": int(len(xyz)), "n_roof_points": int(len(rr)), "n_wall_points": int(len(walls))}


if __name__ == "__main__":
    import sys
    print(json.dumps(_phone_geometry(sample_mesh(Path(sys.argv[1]))), indent=1))
