"""Pure-numpy geodesy and small geometry helpers for the drone pipeline (no GIS dependency)."""
from __future__ import annotations

import numpy as np

# WGS84 ellipsoid
WGS84_A = 6378137.0
WGS84_F = 1.0 / 298.257223563
WGS84_E2 = WGS84_F * (2.0 - WGS84_F)


def wgs84_to_ecef(lat_deg, lon_deg, h_m) -> np.ndarray:
    """Geodetic (deg, deg, m above the ellipsoid) -> ECEF metres. Accepts scalars or arrays; returns (...,3)."""
    lat, lon = np.radians(np.asarray(lat_deg, float)), np.radians(np.asarray(lon_deg, float))
    h = np.asarray(h_m, float)
    n = WGS84_A / np.sqrt(1.0 - WGS84_E2 * np.sin(lat) ** 2)
    x = (n + h) * np.cos(lat) * np.cos(lon)
    y = (n + h) * np.cos(lat) * np.sin(lon)
    z = (n * (1.0 - WGS84_E2) + h) * np.sin(lat)
    return np.stack([x, y, z], axis=-1)


def ecef_to_enu(xyz, lat0_deg: float, lon0_deg: float, h0_m: float) -> np.ndarray:
    """ECEF metres -> local East/North/Up metres at the origin (lat0, lon0, h0)."""
    lat, lon = np.radians(lat0_deg), np.radians(lon0_deg)
    o = wgs84_to_ecef(lat0_deg, lon0_deg, h0_m)
    d = np.asarray(xyz, float) - o
    r = np.array([[-np.sin(lon), np.cos(lon), 0.0],
                  [-np.sin(lat) * np.cos(lon), -np.sin(lat) * np.sin(lon), np.cos(lat)],
                  [np.cos(lat) * np.cos(lon), np.cos(lat) * np.sin(lon), np.sin(lat)]])
    return d @ r.T


def geodetic_to_enu(lat_deg, lon_deg, h_m, origin: tuple[float, float, float]) -> np.ndarray:
    return ecef_to_enu(wgs84_to_ecef(lat_deg, lon_deg, h_m), *origin)


# ---------------------------------------------------------------- camera / GSD
# UNVERIFIED: DJI Mini 3 Pro sensor width 9.7 mm (1/1.3" class; the research brief says 9.68 x 7.26 mm). Only used
# when EXIF FocalPlaneXResolution is absent.
MINI3PRO_SENSOR_WIDTH_MM = 9.7

_UNIT_MM = {2: 25.4, 3: 10.0, 4: 1.0, 5: 0.001}   # EXIF FocalPlaneResolutionUnit -> mm per unit


def camera_from_meta(m: dict) -> dict | None:
    """Pixel focal length and pixel pitch for one image's metadata row (original resolution).

    Returns {focal_px, sensor_width_mm, pixel_pitch_mm, source} or None when no focal length is known.
    The sensor width comes from EXIF FocalPlaneXResolution when present, else the (unverified) constant."""
    f_mm, w = m.get("focal_length_mm"), m.get("width")
    if not f_mm or not w:
        return None
    res, unit = m.get("focal_plane_x_resolution"), m.get("focal_plane_resolution_unit")
    if res and unit in _UNIT_MM and res > 0:
        px_per_mm = float(res) / _UNIT_MM[unit]
        sensor_w, src = w / px_per_mm, "exif_focal_plane_resolution"
    else:
        sensor_w, src = MINI3PRO_SENSOR_WIDTH_MM, "constant_9.7mm_unverified"
    return {"focal_px": float(f_mm) * w / sensor_w, "sensor_width_mm": sensor_w, "pixel_pitch_mm": sensor_w / w,
            "source": src}


def gsd_cm_per_px(altitude_m: float, focal_mm: float, pixel_pitch_mm: float) -> float:
    """GSD = H * pixel pitch / focal length, returned in cm per pixel (H in m, pitch and focal in mm)."""
    return 100.0 * altitude_m * pixel_pitch_mm / focal_mm


# ---------------------------------------------------------------- geometry
def convex_hull_area(xy: np.ndarray) -> float:
    """Area (same unit squared) of the 2D convex hull of (N,2) points; 0 for < 3 points."""
    p = np.unique(np.asarray(xy, float), axis=0)
    if len(p) < 3:
        return 0.0
    if len(p) > 20000:   # hull vertices are extremes in some direction: keep per-bin extremes along both axes
        keep = set()
        for ax in (0, 1):
            span = float(np.ptp(p[:, ax])) + 1e-12
            bins = np.minimum(((p[:, ax] - p[:, ax].min()) / span * 3000).astype(int), 2999)
            order = np.lexsort((p[:, 1 - ax], bins))
            b = bins[order]
            keep.update(order[np.r_[True, b[1:] != b[:-1]]].tolist())
            keep.update(order[np.r_[b[1:] != b[:-1], True]].tolist())
        p = p[sorted(keep)]
    pts = sorted(map(tuple, p))

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lo, up = [], []
    for q in pts:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], q) <= 0:
            lo.pop()
        lo.append(q)
    for q in reversed(pts):
        while len(up) >= 2 and cross(up[-2], up[-1], q) <= 0:
            up.pop()
        up.append(q)
    h = np.array(lo[:-1] + up[:-1])
    if len(h) < 3:
        return 0.0
    x, y = h[:, 0], h[:, 1]
    return float(abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))) / 2.0)


def fit_ground_plane(pts: np.ndarray, tol: float = 0.25, iters: int = 400, seed: int = 0):
    """RANSAC plane (n, d) with n.p + d = 0 and n pointing up (+z), fitted to the lowest 40 % of points by z.

    Returns None when no plane with a near-vertical normal exists."""
    rng = np.random.default_rng(seed)
    low = pts[pts[:, 2] <= np.percentile(pts[:, 2], 40)]
    if len(low) < 10:
        return None
    if len(low) > 60000:
        low = low[rng.choice(len(low), 60000, replace=False)]
    best, best_n = None, 0
    for _ in range(iters):
        s = low[rng.choice(len(low), 3, replace=False)]
        n = np.cross(s[1] - s[0], s[2] - s[0])
        k = np.linalg.norm(n)
        if k < 1e-9:
            continue
        n /= k
        if abs(n[2]) < 0.8:
            continue
        if n[2] < 0:
            n = -n
        d = -float(n @ s[0])
        cnt = int((np.abs(low @ n + d) < tol).sum())
        if cnt > best_n:
            best, best_n = (n, d), cnt
    if best is None:
        return None
    n, d = best                       # least-squares refinement on the inliers
    inl = low[np.abs(low @ n + d) < tol]
    c = inl.mean(axis=0)
    n2 = np.linalg.svd(inl - c)[2][-1]
    if n2[2] < 0:
        n2 = -n2
    return n2, -float(n2 @ c)
