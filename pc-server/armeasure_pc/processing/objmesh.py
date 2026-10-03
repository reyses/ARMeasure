"""OBJECT_MESH: crop to the oriented box, drop the support plane, cluster, mesh, measure.

Resolution is driven by manifest["voxel_mm"] (5 mm QUICK, 3 mm FINE for ~200 mm objects):
  * voxel size for downsampling, radius-outlier radius = 3 x voxel, DBSCAN eps = 4 x voxel
  * support-plane removal margin = 1.6 x voxel
  * Poisson depth 9 (voxel >= 4 mm) or 10 (voxel < 4 mm)

Box convention: world = Ry(yaw_deg) @ local + center, Ry the right-handed rotation about +Y.
Support plane: n.p + d = 0 (any sign; flipped so the box centre is on the positive side).
"""
from __future__ import annotations

import json
import math
import time
from pathlib import Path

import numpy as np
import open3d as o3d

from .common import Ctx, JobError, load_cloud, read_manifest, safe_extract, versions, write_ply_points

BOX_PAD_M = 0.02
SUPPORT_MARGIN_VOXELS = 1.6
DENSITY_QUANTILE = 0.05


def yaw_matrix(yaw_deg: float) -> np.ndarray:
    a = math.radians(yaw_deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def poisson_depth(voxel: float) -> int:
    return 10 if voxel < 0.004 else 9


def box_mask(xyz: np.ndarray, box: dict, pad: float = BOX_PAD_M) -> np.ndarray:
    c = np.asarray(box["center"], float)
    half = np.asarray(box["size"], float) / 2 + pad
    local = (xyz - c) @ yaw_matrix(float(box.get("yaw_deg", 0.0)))   # R^T (p - c)
    return (np.abs(local) <= half).all(axis=1)


def crop_to_box(xyz: np.ndarray, box: dict, pad: float = BOX_PAD_M) -> np.ndarray:
    return xyz[box_mask(xyz, box, pad)]


def support_signed_distance(xyz: np.ndarray, plane: dict, center: np.ndarray) -> tuple[np.ndarray, np.ndarray, float]:
    n = np.asarray(plane["normal"], float)
    k = np.linalg.norm(n)
    n, d = n / k, float(plane["d"]) / k
    if center @ n + d < 0:
        n, d = -n, -d
    return xyz @ n + d, n, d


def mesh_poisson(pcd: o3d.geometry.PointCloud, depth: int):
    mesh, dens = o3d.geometry.TriangleMesh.create_from_point_cloud_poisson(pcd, depth=depth)
    dens = np.asarray(dens)
    if len(dens):
        mesh.remove_vertices_by_mask(dens < np.quantile(dens, DENSITY_QUANTILE))
    bb = pcd.get_axis_aligned_bounding_box()
    pad = 0.01
    mesh = mesh.crop(o3d.geometry.AxisAlignedBoundingBox(bb.get_min_bound() - pad, bb.get_max_bound() + pad))
    return mesh


def mesh_ball_pivot(pcd: o3d.geometry.PointCloud, voxel: float):
    d = np.asarray(pcd.compute_nearest_neighbor_distance())
    r = float(np.mean(d)) if len(d) else voxel
    radii = o3d.utility.DoubleVector([r * 1.5, r * 2.5, r * 4.0, r * 7.0])
    return o3d.geometry.TriangleMesh.create_from_point_cloud_ball_pivoting(pcd, radii)


def _largest_component(mesh: o3d.geometry.TriangleMesh) -> o3d.geometry.TriangleMesh:
    if len(mesh.triangles) == 0:
        return mesh
    idx, counts, _ = mesh.cluster_connected_triangles()
    idx, counts = np.asarray(idx), np.asarray(counts)
    mesh.remove_triangles_by_mask(idx != int(np.argmax(counts)))
    mesh.remove_unreferenced_vertices()
    return mesh


def _obb(points: np.ndarray) -> dict:
    p = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(points))
    b = p.get_minimal_oriented_bounding_box()
    return {"center": [float(x) for x in b.center], "extent": [float(x) for x in b.extent],
            "R": np.asarray(b.R).tolist()}


def hull_volume(points: np.ndarray) -> float:
    p = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(points))
    hull, _ = p.compute_convex_hull()
    return float(hull.get_volume())


def denoise(points: np.ndarray, radius: float) -> np.ndarray:
    """Replace every point by the mean of its neighbours within `radius` (flat faces lose their sensor noise;
    face extremes stay put, only edges and corners round off by a few mm)."""
    pc = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(points))
    tree = o3d.geometry.KDTreeFlann(pc)
    out = np.empty_like(points)
    for i, p in enumerate(points):
        _, idx, _ = tree.search_radius_vector_3d(p, radius)
        out[i] = points[idx].mean(axis=0)
    return out


def measure(mesh: o3d.geometry.TriangleMesh, pts: np.ndarray, n_sup: np.ndarray, d_sup: float,
            voxel: float = 0.005) -> dict:
    """Dimensions and hull volume from DENOISED points (raw extremes are inflated by sensor noise: with 4 mm
    noise a 200 mm cube measures ~230 mm raw); raw-point extents give the upper end of the range.
    voxel=0 skips denoising (photogrammetry meshes are already smooth)."""
    sm = denoise(pts, 5 * voxel) if voxel > 0 else pts
    obb_s, obb_r = _obb(sm), _obb(pts)
    dims_s = sorted(obb_s["extent"], reverse=True)
    dims_r = sorted(obb_r["extent"], reverse=True)
    vhull_s, vhull_r = hull_volume(sm), hull_volume(pts)
    out = {
        "basis": "denoised_points" if voxel > 0 else "mesh_vertices",
        "obb": obb_s,
        "dims_m": [round(x, 5) for x in dims_s],
        "height_above_support_m": round(float((sm @ n_sup + d_sup).max()), 5),
        "convex_hull_volume_m3": round(vhull_s, 7),
        "watertight": False,
    }
    vol_mesh = None
    if len(mesh.triangles) and mesh.is_watertight():
        out["watertight"] = True
        vol_mesh = float(mesh.get_volume())
        out["mesh_volume_m3"] = round(vol_mesh, 7)
    trust_mesh = vol_mesh is not None and 0.5 * vhull_s <= vol_mesh <= 1.15 * vhull_s
    rec_vol = vol_mesh if trust_mesh else vhull_s
    out["recommended"] = {"dims_m": out["dims_m"], "volume_m3": round(rec_vol, 7),
                          "volume_basis": "mesh" if trust_mesh else "convex_hull"}
    vols = [rec_vol, vhull_s] + ([vol_mesh] if trust_mesh else []) + ([vhull_r] if voxel > 0 else [])
    out["range"] = {"dims_min_m": [round(min(a, b), 5) for a, b in zip(dims_s, dims_r)],
                    "dims_max_m": [round(max(a, b), 5) for a, b in zip(dims_s, dims_r)],
                    "volume_min_m3": round(min(vols), 7), "volume_max_m3": round(max(vols), 7)}
    out["raw_points_obb_dims_m"] = [round(x, 5) for x in dims_r]
    return out


def process_cloud(xyz: np.ndarray, manifest: dict, ctx: Ctx | None = None):
    voxel = float(manifest.get("voxel_mm", 5)) / 1000.0
    box, plane = manifest.get("box"), manifest.get("support_plane")
    if ctx:
        ctx.progress(0.12, "crop_box")
    pts = xyz
    if box:      # optional: the phone may already send only the isolated object
        pts = crop_to_box(xyz, box)
        if len(pts) < 200:
            raise JobError(f"only {len(pts)} points inside the box - rescan closer or enlarge the box")
    n_sup = np.array([0.0, 1.0, 0.0])
    d_sup = 0.0
    margin = SUPPORT_MARGIN_VOXELS * voxel
    if plane:
        ref = np.asarray(box["center"], float) if box else pts.mean(axis=0)
        sd, n_sup, d_sup = support_signed_distance(pts, plane, ref)
        pts = pts[sd > margin]
        if len(pts) < 200:
            raise JobError("almost nothing is left above the support plane")
    if ctx:
        ctx.progress(0.25, "outliers")
    pcd = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(pts)).voxel_down_sample(voxel)
    pcd, _ = pcd.remove_radius_outlier(nb_points=6, radius=3 * voxel)
    if len(pcd.points) > 50:
        pcd, _ = pcd.remove_statistical_outlier(nb_neighbors=20, std_ratio=2.5)
    if len(pcd.points) < 100:
        raise JobError("too few points left after outlier removal")
    if ctx:
        ctx.progress(0.35, "cluster")
    labels = np.asarray(pcd.cluster_dbscan(eps=4 * voxel, min_points=10))
    if labels.size == 0 or labels.max() < 0:
        raise JobError("no object cluster found")
    big = int(np.argmax(np.bincount(labels[labels >= 0])))
    pcd = pcd.select_by_index(np.flatnonzero(labels == big).tolist())
    P = np.asarray(pcd.points)
    if not plane:
        d_sup = -float(P[:, 1].min())     # no support plane sent: gravity-up, lowest point = the table
    if plane:  # close the unseen bottom: footprint = lowest layer projected onto the support plane
        sd = P @ n_sup + d_sup
        low = P[sd < margin + 3 * voxel]
        foot = low - np.outer(low @ n_sup + d_sup, n_sup)
        pcd = pcd + o3d.geometry.PointCloud(o3d.utility.Vector3dVector(foot))
        pcd = pcd.voxel_down_sample(voxel)
        P = np.asarray(pcd.points)
    if ctx:
        ctx.progress(0.5, "normals")
    pcd.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=5 * voxel, max_nn=30))
    cen = P.mean(axis=0)
    nrm = np.asarray(pcd.normals)
    flip = ((P - cen) * nrm).sum(axis=1) < 0      # orient outward from the centroid (compact objects)
    nrm[flip] *= -1
    pcd.normals = o3d.utility.Vector3dVector(nrm)
    if ctx:
        ctx.progress(0.6, "poisson")
    method = "poisson"
    mesh = None
    try:
        mesh = _largest_component(mesh_poisson(pcd, poisson_depth(voxel)))
    except Exception:  # noqa: BLE001 - Poisson can fail on degenerate input
        mesh = None
    if mesh is None or len(mesh.triangles) < 100:
        if ctx:
            ctx.progress(0.8, "ball_pivoting")
        method = "ball_pivoting"
        mesh = _largest_component(mesh_ball_pivot(pcd, voxel))
    mesh.remove_degenerate_triangles()
    mesh.remove_duplicated_vertices()
    mesh.remove_non_manifold_edges()
    mesh.compute_vertex_normals()
    if ctx:
        ctx.progress(0.88, "measure")
    m = measure(mesh, P, n_sup, d_sup, voxel)
    m["mesh_method"] = method
    m["mesh_triangles"] = int(len(mesh.triangles))
    m["voxel_mm"] = voxel * 1000
    m["poisson_depth"] = poisson_depth(voxel)
    return m, mesh, pcd


def to_protocol(m: dict) -> dict:
    """Internal measures -> docs/PROCESSING_PROTOCOL.md measures."""
    R = np.asarray(m["obb"]["R"])
    ext = np.asarray(m["obb"]["extent"])
    up = np.array([0.0, 1.0, 0.0])
    ax = int(np.argmax(np.abs(R.T @ up)))
    if abs(float((R.T @ up)[ax])) > 0.95:          # box axis ~ gravity: that extent is the height
        horiz = sorted([float(e) for i, e in enumerate(ext) if i != ax], reverse=True)
        length, width, height = horiz[0], horiz[1], float(ext[ax])
    else:
        length, width, height = sorted((float(e) for e in ext), reverse=True)
    rec, rng_ = m["recommended"]["volume_m3"], m["range"]
    variants = {"bounding_box": round(float(np.prod(ext)), 7), "convex_hull": m["convex_hull_volume_m3"]}
    if "mesh_volume_m3" in m:
        variants["mesh"] = m["mesh_volume_m3"]
    return {"object_dims": {"length_m": round(length, 5), "width_m": round(width, 5),
                            "height_m": round(float(m["height_above_support_m"]) if m["height_above_support_m"] > 0
                                              else height, 5)},
            "volume_m3": {"low": rng_["volume_min_m3"], "high": rng_["volume_max_m3"], "recommended": rec},
            "volume_variants_m3": variants}


def run(upload_zip: Path, outdir: Path, ctx: Ctx) -> dict:
    t0 = time.time()
    ctx.progress(0.02, "unpack")
    root = ctx.workdir / "in"
    safe_extract(upload_zip, root)
    man = read_manifest(root)
    ctx.progress(0.08, "load_cloud")
    xyz = load_cloud(root)
    m, mesh, pcd = process_cloud(xyz, man, ctx)
    ctx.progress(0.94, "write_results")
    outdir.mkdir(parents=True, exist_ok=True)
    o3d.io.write_triangle_mesh(str(outdir / "mesh.ply"), mesh)
    o3d.io.write_triangle_mesh(str(outdir / "mesh.obj"), mesh)
    write_ply_points(outdir / "cloud_clean.ply", np.asarray(pcd.points))
    notes = [f"mesh method: {m['mesh_method']}, {m['mesh_triangles']} triangles, voxel {m['voxel_mm']:g} mm, "
             f"poisson depth {m['poisson_depth']}, measures basis: {m['basis']}, watertight: {m['watertight']}"]
    return {"measures": to_protocol(m),
            "stats": {"backend": "pc", "duration_ms": int((time.time() - t0) * 1000), "versions": versions(),
                      "notes": notes, "quality": man.get("quality"), "voxel_mm": m["voxel_mm"],
                      "poisson_depth": m["poisson_depth"]}}
