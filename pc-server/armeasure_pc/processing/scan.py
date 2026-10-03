"""SCAN_ANALYZE: room planes, outline, area / perimeter / height / volume."""
from __future__ import annotations

import json
import math
import time
from pathlib import Path

import numpy as np
import open3d as o3d

from .common import Ctx, JobError, load_cloud, read_manifest, safe_extract, versions, write_ply_points

DIST_THRESH = 0.02       # m, RANSAC inlier distance
MAX_PLANES = 16
HORIZ_COS = 0.9          # |n.y| above -> floor/ceiling candidate
WALL_COS = 0.2           # |n.y| below -> wall


def _refine_plane(pts: np.ndarray) -> tuple[np.ndarray, float]:
    c = pts.mean(axis=0)
    _, _, vt = np.linalg.svd(pts - c, full_matrices=False)
    n = vt[2]
    return n, -float(n @ c)


def extract_planes(pcd: o3d.geometry.PointCloud, ctx: Ctx | None = None) -> list[dict]:
    """Iterative RANSAC: segment_plane, refine by SVD, remove inliers, repeat."""
    rest = pcd
    min_inl = max(300, int(0.015 * len(pcd.points)))
    planes = []
    for _ in range(MAX_PLANES):
        if len(rest.points) < min_inl:
            break
        model, inl = rest.segment_plane(DIST_THRESH, 3, 1500)
        if len(inl) < min_inl:
            break
        pts = np.asarray(rest.points)
        n0 = np.array(model[:3]) / np.linalg.norm(model[:3])
        n, d = _refine_plane(pts[inl])
        if n @ n0 < 0:
            n, d = -n, -d
        mask = np.abs(pts @ n + d) < DIST_THRESH
        planes.append({"normal": n, "d": d, "points": pts[mask]})
        rest = rest.select_by_index(np.flatnonzero(mask).tolist(), invert=True)
        if ctx:
            ctx.check()
    return planes


def _wall_line(w: dict) -> tuple[np.ndarray, float]:
    """Wall plane as a 2D line n.p + off = 0 in (x, z); returns unit n and signed offset at y=0."""
    n = w["normal"]
    k = np.linalg.norm(n[[0, 2]])
    return n[[0, 2]] / k, float(w["d"] / k)


def classify(planes: list[dict], cloud: np.ndarray) -> dict:
    centroid = cloud.mean(axis=0)
    med_y = float(np.median(cloud[:, 1]))
    for p in planes:
        p["abs_ny"] = abs(float(p["normal"][1]))
        p["kind"] = "other"
    horiz = [p for p in planes if p["abs_ny"] > HORIZ_COS]
    for p in horiz:
        n = p["normal"]
        p["kind"] = "horizontal"
        p["y_at_c"] = float(-(n[0] * centroid[0] + n[2] * centroid[2] + p["d"]) / n[1])
    below = [p for p in horiz if p["y_at_c"] < med_y]
    above = [p for p in horiz if p["y_at_c"] >= med_y]
    floor = max(below, key=lambda p: len(p["points"])) if below else None
    ceiling = max(above, key=lambda p: len(p["points"])) if above else None
    if floor:
        floor["kind"] = "floor"
    if ceiling:
        ceiling["kind"] = "ceiling"
    merged: list[dict] = []
    for w in (p for p in planes if p["abs_ny"] < WALL_COS):
        w["kind"] = "wall"
        n2, off = _wall_line(w)
        dup = None
        for m in merged:
            mn, moff = _wall_line(m)
            if n2 @ mn > math.cos(math.radians(10)) and abs(moff - off) < 0.05:
                dup = m
                break
        if dup is None:
            merged.append(w)
        else:  # same wall found twice
            dup["points"] = np.vstack([dup["points"], w["points"]])
            w["kind"] = "wall_duplicate"
    return {"floor": floor, "ceiling": ceiling, "walls": merged, "centroid": centroid}


def room_outline(walls: list[dict], centroid: np.ndarray, y_mid: float, shift: float = 0.0):
    """Intersect wall lines on the floor plane, ordered by angle of the outward normal.

    shift > 0 moves every wall inward by that many metres (used for the low/high bounds).
    Returns (vertices, edges) where edges[i] = (wall, start_vertex_index, end_vertex_index) or None
    when two neighbouring walls are parallel."""
    lines = []
    for w in walls:
        n = w["normal"]
        k = np.linalg.norm(n[[0, 2]])
        nxz, d = n[[0, 2]] / k, (w["d"] + n[1] * y_mid) / k
        if nxz @ centroid[[0, 2]] + d < 0:   # orient inward
            nxz, d = -nxz, -d
        lines.append((nxz, d - shift, w))
    lines.sort(key=lambda l: math.atan2(-l[0][1], -l[0][0]))
    m = len(lines)
    verts, ok = [], True
    for i in range(m):
        n1, d1, _ = lines[i]
        n2, d2, _ = lines[(i + 1) % m]
        a = np.array([n1, n2])
        if abs(np.linalg.det(a)) < 1e-3:
            ok = False
            continue
        verts.append(np.linalg.solve(a, -np.array([d1, d2])))
    edges = None
    if ok:   # vertex i joins line i and i+1, so line i runs from vertex i-1 to vertex i
        edges = [(lines[i][2], (i - 1) % m, i) for i in range(m)]
    return np.array(verts), edges


def polygon_area(v: np.ndarray) -> float:
    x, z = v[:, 0], v[:, 1]
    return 0.5 * abs(float(np.dot(x, np.roll(z, -1)) - np.dot(z, np.roll(x, -1))))


def _hull_outline(pts_xz: np.ndarray) -> np.ndarray:
    h = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(
        np.column_stack([pts_xz[:, 0], np.zeros(len(pts_xz)), pts_xz[:, 1]])))
    hull, _ = h.compute_convex_hull()
    hv = np.asarray(hull.vertices)[:, [0, 2]]
    ang = np.arctan2(hv[:, 1] - hv[:, 1].mean(), hv[:, 0] - hv[:, 0].mean())
    return hv[np.argsort(ang)]


def _est(low: float, high: float, rec: float, nd: int = 4) -> dict:
    lo, hi = min(low, high, rec), max(low, high, rec)
    return {"low": round(lo, nd), "high": round(hi, nd), "recommended": round(rec, nd)}


BOUND_M = 0.01   # low/high: every wall and the floor/ceiling shifted by +-1 cm (typical plane-fit tolerance)


def analyze_cloud(xyz: np.ndarray, ctx: Ctx | None = None):
    """Returns (measures, planes_doc, notes, cleaned point cloud) in docs/PROCESSING_PROTOCOL.md shape."""
    pcd = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(xyz))
    if ctx:
        ctx.progress(0.15, "outlier_removal")
    pcd = pcd.voxel_down_sample(0.005)
    pcd, _ = pcd.remove_statistical_outlier(nb_neighbors=20, std_ratio=2.0)
    work = pcd.voxel_down_sample(0.01)
    if ctx:
        ctx.progress(0.35, "plane_ransac")
    planes = extract_planes(work, ctx)
    if not planes:
        raise JobError("no planes found in the scan - is the cloud too sparse?")
    wpts = np.asarray(work.points)
    cl = classify(planes, wpts)
    floor, ceiling, walls, c = cl["floor"], cl["ceiling"], cl["walls"], cl["centroid"]
    if ctx:
        ctx.progress(0.75, "room_geometry")
    notes: list[str] = []
    if floor and ceiling:
        fy, cy = floor["y_at_c"], ceiling["y_at_c"]
    else:
        fy, cy = float(np.percentile(wpts[:, 1], 1)), float(np.percentile(wpts[:, 1], 99))
        notes.append("floor and/or ceiling plane not found; height from the 1st-99th percentile of y")
    height = cy - fy
    ymid = 0.5 * (fy + cy)
    edges = None
    verts = np.zeros((0, 2))
    if len(walls) >= 3:
        verts, edges = room_outline(walls, c, ymid)
    if len(verts) < 3:
        verts, edges = _hull_outline(wpts[:, [0, 2]]), None
        notes.append("fewer than 3 usable walls; outline is the convex hull of the points (rough)")

    def area_per(v):
        return polygon_area(v), float(np.linalg.norm(v - np.roll(v, -1, axis=0), axis=1).sum())

    area, per = area_per(verts)
    lo_a, lo_p, hi_a, hi_p = area, per, area, per
    if edges is not None:
        vin, _ = room_outline(walls, c, ymid, shift=BOUND_M)
        vout, _ = room_outline(walls, c, ymid, shift=-BOUND_M)
        if len(vin) >= 3 and len(vout) >= 3:
            lo_a, lo_p = area_per(vin)
            hi_a, hi_p = area_per(vout)
    h_lo, h_hi = height - BOUND_M, height + BOUND_M
    measures = {
        "area_m2": _est(lo_a, hi_a, area),
        "perimeter_m": _est(lo_p, hi_p, per),
        "height_m": _est(h_lo, h_hi, height),
        "volume_m3": _est(lo_a * h_lo, hi_a * h_hi, area * height),
        "volume_variants_m3": {"bounding_box": round(float(np.ptp(verts[:, 0]) * np.ptp(verts[:, 1]) * height), 4),
                               "polygon": round(area * height, 4)},
        "wall_count": len(walls),
    }
    wall_edge = {id(w): (a_, b_) for w, a_, b_ in edges} if edges else {}
    plist = []
    for p in planes:
        kind = {"floor": "FLOOR", "ceiling": "CEILING", "wall": "WALL"}.get(p["kind"], "OTHER")
        outline3d: list = []
        if p is floor or p is ceiling:
            y = fy if p is floor else cy
            outline3d = [[round(float(x), 4), round(float(y), 4), round(float(z), 4)] for x, z in verts]
        elif id(p) in wall_edge:
            a_, b_ = wall_edge[id(p)]
            (x1, z1), (x2, z2) = verts[a_], verts[b_]
            outline3d = [[round(float(x), 4), round(float(y), 4), round(float(z), 4)]
                         for x, y, z in ((x1, fy, z1), (x2, fy, z2), (x2, cy, z2), (x1, cy, z1))]
        plist.append({"kind": kind, "normal": [round(float(x), 5) for x in p["normal"]],
                      "d": round(float(p["d"]), 5), "centroid": [round(float(x), 4) for x in p["points"].mean(axis=0)],
                      "inliers": int(len(p["points"])), "outline_3d": outline3d})
    return measures, {"schema": 1, "planes": plist}, notes, pcd


def run(upload_zip: Path, outdir: Path, ctx: Ctx) -> dict:
    t0 = time.time()
    ctx.progress(0.02, "unpack")
    root = ctx.workdir / "in"
    safe_extract(upload_zip, root)
    read_manifest(root)
    ctx.progress(0.08, "load_cloud")
    xyz = load_cloud(root)
    measures, planes_doc, notes, clean = analyze_cloud(xyz, ctx)
    ctx.progress(0.9, "write_results")
    outdir.mkdir(parents=True, exist_ok=True)
    (outdir / "planes.json").write_text(json.dumps(planes_doc, indent=1), encoding="utf-8")
    write_ply_points(outdir / "cloud_clean.ply", np.asarray(clean.points))
    return {"measures": measures,
            "stats": {"backend": "pc", "duration_ms": int((time.time() - t0) * 1000), "versions": versions(),
                      "notes": notes, "input_points": int(len(xyz))}}
