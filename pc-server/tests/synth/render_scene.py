"""Synthetic scenes with known ground truth for the end-to-end photogrammetry tests.

Renderer: numpy ray caster against axis-aligned textured boxes (exact perspective, nearest hit = z-buffer, per-pixel
bilinear texture lookup, 2x2 supersampling).  Open3D's OffscreenRenderer was not used: it needs a GL context and a
ray caster is exact for boxes anyway.

PHONE  (PHOTOGRAMMETRY job): 200 mm cube on a 2 x 2 m textured ground, world Y up (ARCore), 60 views on two orbits,
        1280 x 960, fx = fy = 1000 (optional +-1 % per-image focal jitter).
DRONE  (DRONE_PHOTOS job): 10 x 6 x 4 m 'house' on a 60 x 60 m ground, ENU (z up), 40 views at 30 m altitude in a
        5 x 8 snake grid (75 % overlap, the 12 views around the house are oblique), JPEGs with EXIF GPS (WGS84 around
        lat 33.0 lon -117.0), DJI-like XMP, FocalLength 6.72 mm, Make DJI / Model FC3170.

CLI:  python render_scene.py phone|drone OUT_DIR [--jitter] [--gps-noise M] [--fast]
Each writes the job ZIP (phone_job.zip / drone_job.zip) and truth.json next to it.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import zipfile
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from PIL.TiffImagePlugin import IFDRational

# ----------------------------------------------------------------------------------------------- textures
def make_texture(rng: np.random.Generator, size: int, min_cells: int = 4, max_cells: int | None = None,
                 n_markers: int = 40, n_text: int = 25, detail: int = 1) -> np.ndarray:
    """Random high-frequency colour texture (multi-octave noise + text + binary markers), (size,size,3) uint8."""
    max_cells = max_cells or size // 3
    acc = np.zeros((size, size, 3), np.float32)
    cells, wsum = min_cells, 0.0
    while cells <= max_cells:
        g = rng.integers(0, 256, (cells, cells, 3), dtype=np.uint8)
        up = np.asarray(Image.fromarray(g).resize((size, size), Image.BICUBIC), np.float32)
        w = 1.0 if cells > 32 else 0.6
        acc += w * up
        wsum += w
        cells *= 2
    img = Image.fromarray(np.clip(acc / wsum, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    for _ in range(n_markers):                      # random binary markers (6x6 cells)
        s = int(rng.integers(size // (40 * detail), size // (14 * detail)))
        x, y = int(rng.integers(0, size - s)), int(rng.integers(0, size - s))
        bits = rng.integers(0, 2, (6, 6))
        c = s / 6.0
        d.rectangle([x - 2, y - 2, x + s + 2, y + s + 2], fill=(255, 255, 255))
        for i in range(6):
            for j in range(6):
                if bits[i, j]:
                    d.rectangle([x + j * c, y + i * c, x + (j + 1) * c, y + (i + 1) * c], fill=(0, 0, 0))
    for k in range(n_text):
        try:
            font = ImageFont.load_default(size=int(rng.integers(size // (40 * detail), size // (12 * detail))))
        except TypeError:
            font = ImageFont.load_default()
        txt = "".join(chr(int(rng.integers(65, 91))) for _ in range(int(rng.integers(3, 7)))) + str(k)
        col = tuple(int(v) for v in rng.integers(0, 256, 3))
        d.text((int(rng.integers(0, size * 3 // 4)), int(rng.integers(0, size * 3 // 4))), txt, fill=col, font=font)
    return np.asarray(img)


def _yaw_rot(yaw_rad: float) -> np.ndarray:
    """Right-handed rotation about +Y (same convention as armeasure_pc.processing.objmesh.yaw_matrix)."""
    c, s = math.cos(yaw_rad), math.sin(yaw_rad)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


class Box:
    """Textured box, axis-aligned in its own frame; faces are keyed (axis, sign) with sign 0 = low face, 1 = high face.
    `yaw` (rad) turns the whole box about the world +Y axis through the origin (a turntable): world = Ry(yaw) @ local."""

    def __init__(self, lo, hi, tex: dict, yaw: float = 0.0):
        self.lo, self.hi, self.tex, self.yaw = np.asarray(lo, np.float64), np.asarray(hi, np.float64), tex, yaw

    def intersect(self, o: np.ndarray, d: np.ndarray, best_t: np.ndarray):
        """Nearest hits closer than best_t: returns (indices, t, colours) or None."""
        if self.yaw:
            r = _yaw_rot(self.yaw)
            o, d = o @ r, d @ r                          # world -> local (row vectors: v @ R = R^T v)
        n = len(d)
        with np.errstate(divide="ignore", invalid="ignore"):
            inv = 1.0 / d
        t1, t2 = (self.lo - o) * inv, (self.hi - o) * inv
        tmin, tmax = np.minimum(t1, t2), np.maximum(t1, t2)
        tmin = np.where(np.isnan(tmin), -np.inf, tmin)
        tmax = np.where(np.isnan(tmax), np.inf, tmax)
        ax = np.argmax(tmin, 1)
        tn = tmin[np.arange(n), ax]
        tf = tmax.min(1)
        hit = (tn <= tf) & (tf > 0) & (tn > 1e-6) & (tn < best_t)
        if not hit.any():
            return None
        idx = np.flatnonzero(hit)
        p = o + d[idx] * tn[idx, None]
        a = ax[idx]
        sg = (d[idx, a] < 0).astype(int)                 # ray going -axis hits the HIGH face
        col = np.empty((len(idx), 3), np.float32)
        for axis in range(3):
            for sign in (0, 1):
                m = (a == axis) & (sg == sign)
                if not m.any():
                    continue
                o1, o2 = [k for k in range(3) if k != axis]
                pp = p[m]
                uu = (pp[:, o1] - self.lo[o1]) / (self.hi[o1] - self.lo[o1])
                vv = (pp[:, o2] - self.lo[o2]) / (self.hi[o2] - self.lo[o2])
                col[m] = _lookup(self.tex[(axis, sign)], uu, vv)
        return idx, tn[idx], col


class Disc:
    """Textured turntable: vertical cylinder (axis = world Y), radius `r`, from y0 to y1; the top cap carries `tex`,
    the side is plain grey. `yaw` turns it about +Y like Box."""

    def __init__(self, r: float, y0: float, y1: float, tex: np.ndarray, yaw: float = 0.0):
        self.r, self.y0, self.y1, self.tex, self.yaw = r, y0, y1, tex, yaw

    def intersect(self, o: np.ndarray, d: np.ndarray, best_t: np.ndarray):
        if self.yaw:
            rot = _yaw_rot(self.yaw)
            o, d = o @ rot, d @ rot
        n = len(d)
        t = np.full(n, np.inf)
        cap = np.zeros(n, bool)
        with np.errstate(divide="ignore", invalid="ignore"):
            tc = (self.y1 - o[1]) / d[:, 1]
            px, pz = o[0] + d[:, 0] * tc, o[2] + d[:, 2] * tc
            okc = (tc > 1e-6) & (d[:, 1] < 0) & (px * px + pz * pz <= self.r ** 2)
            t[okc], cap[okc] = tc[okc], True
            a = d[:, 0] ** 2 + d[:, 2] ** 2
            b = 2 * (o[0] * d[:, 0] + o[2] * d[:, 2])
            c = o[0] ** 2 + o[2] ** 2 - self.r ** 2
            disc = b * b - 4 * a * c
            sq = np.sqrt(np.where(disc >= 0, disc, np.nan))
            for ts in ((-b - sq) / (2 * a), (-b + sq) / (2 * a)):
                y = o[1] + d[:, 1] * ts
                oks = (ts > 1e-6) & (y >= self.y0) & (y <= self.y1) & (ts < t)
                t[oks], cap[oks] = ts[oks], False
        hit = np.isfinite(t) & (t < best_t)
        if not hit.any():
            return None
        idx = np.flatnonzero(hit)
        col = np.full((len(idx), 3), 110.0, np.float32)
        ci = cap[idx]
        if ci.any():
            p = o + d[idx[ci]] * t[idx[ci], None]
            col[ci] = _lookup(self.tex, (p[:, 0] / self.r + 1) / 2, (p[:, 2] / self.r + 1) / 2)
        return idx, t[idx], col


def _lookup(tex: np.ndarray, u: np.ndarray, v: np.ndarray) -> np.ndarray:
    h, w = tex.shape[:2]
    x = np.clip(u, 0, 0.999999) * (w - 1)
    y = np.clip(v, 0, 0.999999) * (h - 1)
    x0, y0 = x.astype(np.int64), y.astype(np.int64)
    fx, fy = (x - x0)[:, None].astype(np.float32), (y - y0)[:, None].astype(np.float32)
    x1, y1 = np.minimum(x0 + 1, w - 1), np.minimum(y0 + 1, h - 1)
    t = tex
    a = t[y0, x0].astype(np.float32) * (1 - fx) + t[y0, x1].astype(np.float32) * fx
    b = t[y1, x0].astype(np.float32) * (1 - fx) + t[y1, x1].astype(np.float32) * fx
    return a * (1 - fy) + b * fy


def render(boxes: list[Box], fx: float, fy: float, cx: float, cy: float, W: int, H: int, c2w: np.ndarray,
           ss: int = 2, bg=(150, 160, 175), noise: float = 1.0, seed: int = 0) -> np.ndarray:
    """c2w: 4x4 camera->world, OpenGL camera (+X right, +Y up, looks along -Z). Pixel centres at +0.5 (COLMAP)."""
    R, o = c2w[:3, :3], c2w[:3, 3]
    out = np.zeros((H * W, 3), np.float32)
    jj, ii = np.meshgrid(np.arange(W), np.arange(H))
    ii, jj = ii.ravel(), jj.ravel()
    offs = [((a + 0.5) / ss, (b + 0.5) / ss) for a in range(ss) for b in range(ss)]
    chunk = 400_000
    for du, dv in offs:
        for s in range(0, H * W, chunk):
            u, v = jj[s:s + chunk] + du, ii[s:s + chunk] + dv
            dc = np.stack([(u - cx) / fx, -(v - cy) / fy, -np.ones_like(u)], 1)
            d = dc @ R.T
            d /= np.linalg.norm(d, axis=1, keepdims=True)
            n = len(d)
            best_t = np.full(n, np.inf)
            col = np.empty((n, 3), np.float32)
            col[:] = bg
            for bx in boxes:
                h = bx.intersect(o, d, best_t)
                if h is None:
                    continue
                idx, tt, cc = h
                best_t[idx] = tt
                col[idx] = cc
            out[s:s + chunk] += col
    out /= len(offs)
    if noise > 0:
        out += np.random.default_rng(seed).normal(0, noise, out.shape).astype(np.float32)
    return np.clip(out, 0, 255).astype(np.uint8).reshape(H, W, 3)


def lookat_c2w(eye, target, up) -> np.ndarray:
    eye, target, up = (np.asarray(v, float) for v in (eye, target, up))
    z = eye - target
    z /= np.linalg.norm(z)
    x = np.cross(up, z)
    x /= np.linalg.norm(x)
    y = np.cross(z, x)
    M = np.eye(4)
    M[:3, 0], M[:3, 1], M[:3, 2], M[:3, 3] = x, y, z, eye
    return M


# ----------------------------------------------------------------------------------------------- scenes
CUBE_SIDE = 0.2


def phone_scene(seed: int = 1) -> list[Box]:
    rng = np.random.default_rng(seed)
    cube_tex = {(a, s): make_texture(rng, 1024, min_cells=4, max_cells=256) for a in range(3) for s in range(2)}
    ground = make_texture(rng, 2048, min_cells=4, max_cells=512, n_markers=120, n_text=60)
    gt = {(1, 1): ground, (1, 0): ground[:64, :64].copy(), (0, 0): ground[:64, :64].copy(),
          (0, 1): ground[:64, :64].copy(), (2, 0): ground[:64, :64].copy(), (2, 1): ground[:64, :64].copy()}
    h = CUBE_SIDE / 2
    return [Box([-h, 0, -h], [h, CUBE_SIDE, h], cube_tex), Box([-1, -0.02, -1], [1, 0, 1], gt)]


def phone_views(n_per_orbit: int = 30, radius: float = 0.45, jitter: bool = False, seed: int = 2) -> list[dict]:
    rng = np.random.default_rng(seed)
    target = np.array([0.0, CUBE_SIDE / 2, 0.0])
    views = []
    for oi, hgt in enumerate((CUBE_SIDE + 0.15, CUBE_SIDE + 0.35)):
        for k in range(n_per_orbit):
            th = 2 * math.pi * (k + 0.5 * oi) / n_per_orbit
            eye = np.array([radius * math.cos(th), hgt, radius * math.sin(th)])
            f = 1000.0 * (1 + rng.uniform(-0.01, 0.01)) if jitter else 1000.0
            views.append({"c2w": lookat_c2w(eye, target, (0, 1, 0)), "fx": f, "fy": f, "cx": 640.0, "cy": 480.0,
                          "w": 1280, "h": 960})
    return views


def drone_scene(seed: int = 3) -> list[Box]:
    rng = np.random.default_rng(seed)
    wall = {(a, s): make_texture(rng, 1536, min_cells=4, max_cells=384, n_markers=60, n_text=30)
            for a in range(3) for s in range(2)}
    ground = make_texture(rng, 4096, min_cells=8, max_cells=2048, n_markers=2500, n_text=2500, detail=4)
    small = ground[:64, :64].copy()
    gt = {(2, 1): ground, (2, 0): small, (0, 0): small, (0, 1): small, (1, 0): small, (1, 1): small}
    return [Box([-5, -3, 0], [5, 3, 4], wall), Box([-30, -30, -0.05], [30, 30, 0], gt)]


DRONE_W, DRONE_H = 1600, 1200
FOCAL_MM, SENSOR_W_MM = 6.72, 9.7          # SENSOR_W_MM equals geo.MINI3PRO_SENSOR_WIDTH_MM (EXIF has no plane res.)
DRONE_FX = FOCAL_MM * DRONE_W / SENSOR_W_MM
ALT_M = 30.0


def drone_views() -> list[dict]:
    """5 columns (east) x 8 rows (north) snake grid; 75 % overlap: footprint 43 x 32 m -> spacing 10.8 / 8 m."""
    xs = (np.arange(5) - 2) * 10.8
    ys = (np.arange(8) - 3.5) * 8.0
    views, order = [], 0
    for r, y in enumerate(ys):
        cols = range(5) if r % 2 == 0 else range(4, -1, -1)
        for c in cols:
            x = xs[c]
            eye = np.array([x, y, ALT_M])
            oblique = abs(x) <= 11 and abs(y) <= 12.5          # the 12 cameras nearest the house look at it
            if oblique:
                c2w = lookat_c2w(eye, (0, 0, 2.0), (0, 0, 1))
            else:
                c2w = np.eye(4)
                c2w[:3, 0], c2w[:3, 1], c2w[:3, 2], c2w[:3, 3] = (1, 0, 0), (0, 1, 0), (0, 0, 1), eye   # nadir, up = N
            order += 1
            views.append({"c2w": c2w, "fx": DRONE_FX, "fy": DRONE_FX, "cx": DRONE_W / 2, "cy": DRONE_H / 2,
                          "w": DRONE_W, "h": DRONE_H, "oblique": oblique, "name": f"DJI_{order:04d}.JPG"})
    return views


# ----------------------------------------------------------------------------------------------- workers
_SCENE: dict = {}


def _scene(kind: str):
    if kind not in _SCENE:
        _SCENE[kind] = phone_scene() if kind == "phone" else drone_scene()
    return _SCENE[kind]


def _render_one(args):
    kind, i, view, ss = args
    img = render(_scene(kind), view["fx"], view["fy"], view["cx"], view["cy"], view["w"], view["h"], view["c2w"],
                 ss=ss, seed=i)
    return i, img


def render_all(kind: str, views: list[dict], ss: int = 2, workers: int | None = None):
    workers = workers or max(1, min(10, (os.cpu_count() or 4) - 2))
    with ProcessPoolExecutor(workers) as ex:
        res = dict(ex.map(_render_one, [(kind, i, v, ss) for i, v in enumerate(views)]))
    return [res[i] for i in range(len(views))]


# ----------------------------------------------------------------------------------------------- phone job
def build_phone_job(out_dir: Path, jitter: bool = False, fast: bool = False) -> dict:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    views = phone_views(jitter=jitter)
    imgs = render_all("phone", views, ss=1 if fast else 2)
    zpath = out_dir / ("phone_job_jitter.zip" if jitter else "phone_job.zip")
    items = []
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:
        for i, (v, im) in enumerate(zip(views, imgs), 1):
            name = f"images/{i:06d}.jpg"
            import io
            b = io.BytesIO()
            Image.fromarray(im).save(b, "JPEG", quality=92)
            z.writestr(name, b.getvalue())
            items.append({"file": name, "timestamp_ns": 1_000_000_000 * i, "pose": [float(x) for x in
                          v["c2w"].flatten(order="F")], "fx": v["fx"], "fy": v["fy"], "cx": v["cx"], "cy": v["cy"],
                          "width": v["w"], "height": v["h"]})
        z.writestr("poses.json", json.dumps({"schema": 1, "images": items}))
        manifest = {"schema": 1, "job_type": "photogrammetry", "units": "meters", "quality": "FINE",
                    "image_count": len(items),
                    "coordinates": {"frame": "ARCore world", "units": "meters", "up": "+Y"},
                    "box": {"center": [0.0, CUBE_SIDE / 2, 0.0], "size": [CUBE_SIDE] * 3, "yaw_deg": 0.0},
                    "support_plane": {"normal": [0.0, 1.0, 0.0], "d": 0.0}}
        z.writestr("manifest.json", json.dumps(manifest))
    truth = {"cube_side_m": CUBE_SIDE, "cube_volume_m3": CUBE_SIDE ** 3, "cube_lo": [-0.1, 0, -0.1],
             "cube_hi": [0.1, 0.2, 0.1], "n_images": len(items), "jitter": jitter}
    (out_dir / "phone_truth.json").write_text(json.dumps(truth))
    return {"zip": zpath, **truth}


# ----------------------------------------------------------------------------------------------- drone job
ORIGIN_LAT, ORIGIN_LON, ORIGIN_ALT = 33.0, -117.0, 100.0   # ENU origin: ground level, 100 m above the ellipsoid


def enu_to_geodetic(enu: np.ndarray, lat0=ORIGIN_LAT, lon0=ORIGIN_LON, h0=ORIGIN_ALT):
    """Inverse of armeasure_pc.processing.geo (ECEF round trip, Bowring iterations)."""
    from armeasure_pc.processing.geo import WGS84_A, WGS84_E2, wgs84_to_ecef
    lat, lon = math.radians(lat0), math.radians(lon0)
    r = np.array([[-math.sin(lon), math.cos(lon), 0.0],
                  [-math.sin(lat) * math.cos(lon), -math.sin(lat) * math.sin(lon), math.cos(lat)],
                  [math.cos(lat) * math.cos(lon), math.cos(lat) * math.sin(lon), math.sin(lat)]])
    xyz = wgs84_to_ecef(lat0, lon0, h0) + np.asarray(enu, float) @ r        # r orthonormal: d = enu @ r
    x, y, z = xyz
    lo = math.atan2(y, x)
    p = math.hypot(x, y)
    la = math.atan2(z, p * (1 - WGS84_E2))
    for _ in range(8):
        n = WGS84_A / math.sqrt(1 - WGS84_E2 * math.sin(la) ** 2)
        h = p / math.cos(la) - n
        la = math.atan2(z, p * (1 - WGS84_E2 * n / (n + h)))
    n = WGS84_A / math.sqrt(1 - WGS84_E2 * math.sin(la) ** 2)
    h = p / math.cos(la) - n
    return math.degrees(la), math.degrees(lo), h


def _rat(v: float, den: int = 1_000_000) -> IFDRational:
    return IFDRational(int(round(v * den)), den)


def _dms(v: float):
    v = abs(v)
    d = int(v)
    m = int((v - d) * 60)
    s = (v - d - m / 60.0) * 3600.0
    return (IFDRational(d, 1), IFDRational(m, 1), _rat(s))


def drone_xmp(rel_alt: float, pitch: float, yaw: float, abs_alt: float) -> bytes:
    return ('<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/">'
            '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
            '<rdf:Description xmlns:drone-dji="http://www.dji.com/drone-dji/1.0/" '
            f'drone-dji:GimbalPitchDegree="{pitch:+.2f}" drone-dji:GimbalYawDegree="{yaw:+.2f}" '
            f'drone-dji:FlightYawDegree="{yaw:+.2f}" drone-dji:RelativeAltitude="{rel_alt:+.3f}" '
            f'drone-dji:AbsoluteAltitude="{abs_alt:+.3f}"/></rdf:RDF></x:xmpmeta><?xpacket end="w"?>').encode()


def build_drone_job(out_dir: Path, gps_noise_m: float = 0.0, fast: bool = False, seed: int = 5) -> dict:
    out_dir = Path(out_dir)
    img_dir = out_dir / "drone_photos"
    img_dir.mkdir(parents=True, exist_ok=True)
    views = drone_views()
    imgs = render_all("drone", views, ss=1 if fast else 2)
    rng = np.random.default_rng(seed)
    centres, gps_enu = [], []
    for i, (v, im) in enumerate(zip(views, imgs)):
        c = v["c2w"][:3, 3].copy()
        centres.append(c)
        g = c + (rng.normal(0, [gps_noise_m, gps_noise_m, 2 * gps_noise_m]) if gps_noise_m else 0.0)
        gps_enu.append(g)
        lat, lon, alt = enu_to_geodetic(g)
        fwd = -v["c2w"][:3, 2]
        pitch = math.degrees(math.asin(np.clip(fwd[2], -1, 1)))
        yaw = math.degrees(math.atan2(fwd[0], fwd[1])) if abs(fwd[2]) < 0.999 else 0.0
        ex = Image.Exif()
        ex[0x010F], ex[0x0110] = "DJI", "FC3170"
        sub = ex.get_ifd(0x8769)
        sub[0x920A] = IFDRational(672, 100)
        sub[0x9003] = f"2026:10:03 10:{i * 2 // 60:02d}:{i * 2 % 60:02d}"
        gp = ex.get_ifd(0x8825)
        gp[1], gp[2] = ("N" if lat >= 0 else "S"), _dms(lat)
        gp[3], gp[4] = ("E" if lon >= 0 else "W"), _dms(lon)
        gp[5], gp[6] = 0, _rat(alt, 1000)
        Image.fromarray(im).save(img_dir / v["name"], "JPEG", quality=92, exif=ex,
                                 xmp=drone_xmp(float(c[2]), pitch, yaw, alt))
    zpath = out_dir / "drone_job.zip"
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:
        for v in views:
            z.write(img_dir / v["name"], v["name"])
        z.writestr("manifest.json", json.dumps({"schema": 1, "job_type": "drone_photos", "quality": "FINE"}))
    truth = {"house_lo": [-5, -3, 0], "house_hi": [5, 3, 4], "house_extent_m": [10, 6, 4],
             "ground_xy_m": [60, 60], "ground_area_m2": 3600.0, "n_images": len(views),
             "centres_enu": [c.tolist() for c in centres], "names": [v["name"] for v in views],
             "gps_enu": [g.tolist() for g in gps_enu], "fx_px": DRONE_FX, "w": DRONE_W, "h": DRONE_H, "gps_noise_m": gps_noise_m}
    (out_dir / "drone_truth.json").write_text(json.dumps(truth))
    return {"zip": zpath, "folder": img_dir, **truth}


# ----------------------------------------------------------------------------------------------- spin / hybrid job
# Render frame: Y up, turntable axis = the Y axis through the origin, disc top = the support plane y = 0. The phone
# camera is STATIC (0.40 m from the axis); the cube + disc turn by theta about the axis while the ground stays put, so
# the static ground is in every image (that is why the job carries object masks).  ARCore frame = render frame moved by
# a yaw of 37 degrees and a shift (so nothing in the job is axis-aligned at the origin).
SPIN_DIST_M = 0.40
SPIN_ELEV_DEG = (15.0, 40.0)             # camera elevation above the horizontal as seen from the cube centre
SPIN_N, SPIN_STEP_DEG = 36, 10.0         # frames per turn, degrees between frames
DISC_R, DISC_T = 0.22, 0.02
G_YAW_DEG = 37.0
G_SHIFT = np.array([0.35, 0.85, -0.60])


def g_matrix() -> np.ndarray:
    M = np.eye(4)
    M[:3, :3] = _yaw_rot(math.radians(G_YAW_DEG))
    M[:3, 3] = G_SHIFT
    return M


def spin_textures(seed: int = 7):
    rng = np.random.default_rng(seed)
    cube = {(a, s): make_texture(rng, 1024, min_cells=4, max_cells=256) for a in range(3) for s in range(2)}
    disc = make_texture(rng, 1024, min_cells=4, max_cells=256, n_markers=60, n_text=40)
    ground = make_texture(rng, 2048, min_cells=4, max_cells=512, n_markers=120, n_text=60)
    return cube, disc, ground


def spin_boxes(tex, theta_rad: float) -> list:
    cube, disc, ground = tex
    small = ground[:64, :64].copy()
    gt = {(1, 1): ground, (1, 0): small, (0, 0): small, (0, 1): small, (2, 0): small, (2, 1): small}
    h = CUBE_SIDE / 2
    return [Box([-h, 0, -h], [h, CUBE_SIDE, h], cube, yaw=theta_rad),
            Disc(DISC_R, -DISC_T, 0.0, disc, yaw=theta_rad),
            Box([-1.5, -0.05, -1.5], [1.5, -DISC_T, 1.5], gt)]


def spin_views(turn_offsets_deg=(0.0, 5.0)) -> list[dict]:
    """72 spin views: two turns of 36 frames, static camera per turn at 15 and 40 degrees elevation."""
    target = np.array([0.0, CUBE_SIDE / 2, 0.0])
    views = []
    for ti, (el, off) in enumerate(zip(SPIN_ELEV_DEG, turn_offsets_deg)):
        eye = np.array([SPIN_DIST_M, CUBE_SIDE / 2 + SPIN_DIST_M * math.tan(math.radians(el)), 0.0])
        c2w = lookat_c2w(eye, target, (0, 1, 0))
        for k in range(SPIN_N):
            views.append({"c2w": c2w, "fx": 1000.0, "fy": 1000.0, "cx": 640.0, "cy": 480.0, "w": 1280, "h": 960,
                          "theta": math.radians(off + k * SPIN_STEP_DEG), "turn": ti})
    return views


def walk_views(n_ring: int = 12, radius: float = 0.5, elev_deg=(25.0, 50.0)) -> list[dict]:
    """24 walk-around views of the (not turning) object: two rings, 30 degrees apart in azimuth."""
    target = np.array([0.0, CUBE_SIDE / 2, 0.0])
    views = []
    for ri, el in enumerate(elev_deg):
        for k in range(n_ring):
            az = 2 * math.pi * (k + 0.5 * ri) / n_ring
            eye = target + radius * np.array([math.cos(az) * math.cos(math.radians(el)), math.sin(math.radians(el)),
                                              math.sin(az) * math.cos(math.radians(el))])
            views.append({"c2w": lookat_c2w(eye, target, (0, 1, 0)), "fx": 1000.0, "fy": 1000.0, "cx": 640.0,
                          "cy": 480.0, "w": 1280, "h": 960, "theta": 0.0, "turn": -1})
    return views


def _convex_hull(pts: np.ndarray) -> np.ndarray:
    p = sorted(map(tuple, pts))
    if len(p) < 3:
        return np.array(p)

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    h = []
    for q in p:
        while len(h) >= 2 and cross(h[-2], h[-1], q) <= 0:
            h.pop()
        h.append(q)
    lower = len(h) + 1
    for q in reversed(p[:-1]):
        while len(h) >= lower and cross(h[-2], h[-1], q) <= 0:
            h.pop()
        h.append(q)
    return np.array(h[:-1])


def box_mask_png(box: dict, pose_c2w: np.ndarray, v: dict, pad: float = 0.02) -> np.ndarray:
    """Python port of the phone's BoxMask: the 8 corners of the box (base-centred, grown by `pad`) projected with the
    keyframe pose, convex hull rasterised at pixel centres; 255 inside, 0 outside. `box` is the manifest box
    (volume centre, size [w, h, d], yaw_deg)."""
    cx, cy, cz = box["center"]
    w, h, d = box["size"]
    base_y = cy - h / 2
    yaw = math.radians(box.get("yaw_deg", 0.0))
    c, s = math.cos(yaw), math.sin(yaw)
    corners = []
    for i in range(8):
        lx = w / 2 + pad if i & 1 else -w / 2 - pad
        ly = h + pad if i & 2 else -pad
        lz = d / 2 + pad if i & 4 else -d / 2 - pad
        corners.append([cx + lx * c + lz * s, base_y + ly, cz - lx * s + lz * c])
    inv = np.linalg.inv(pose_c2w)
    pts = []
    for X in corners:
        xc = inv @ np.append(X, 1.0)
        depth = -xc[2]
        if depth > 0.02:
            pts.append([v["fx"] * xc[0] / depth + v["cx"], v["cy"] - v["fy"] * xc[1] / depth])
    hull = _convex_hull(np.array(pts))
    m = np.zeros((v["h"], v["w"]), np.uint8)
    if len(hull) < 3:
        return m
    x0, x1 = max(0, int(np.floor(hull[:, 0].min()))), min(v["w"] - 1, int(np.ceil(hull[:, 0].max())))
    y0, y1 = max(0, int(np.floor(hull[:, 1].min()))), min(v["h"] - 1, int(np.ceil(hull[:, 1].max())))
    xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
    inside = np.ones(xs.shape, bool)
    for i in range(len(hull)):
        a, b = hull[i], hull[(i + 1) % len(hull)]
        inside &= (b[0] - a[0]) * (ys - a[1]) - (b[1] - a[1]) * (xs - a[0]) >= 0
    m[y0:y1 + 1, x0:x1 + 1] = inside.astype(np.uint8) * 255
    return m


def _render_spin_one(args):
    i, view, ss = args
    if "tex" not in _SCENE:
        _SCENE["tex"] = spin_textures()
    img = render(spin_boxes(_SCENE["tex"], view["theta"]), view["fx"], view["fy"], view["cx"], view["cy"], view["w"],
                 view["h"], view["c2w"], ss=ss, seed=1000 + i)
    return i, img


def _jpeg(im: np.ndarray) -> bytes:
    import io
    b = io.BytesIO()
    Image.fromarray(im).save(b, "JPEG", quality=92)
    return b.getvalue()


def _poses_json(views: list[dict], rng, noise_m: float, noise_deg: float, first: int = 1, prefix="images/") -> dict:
    """poses.json in the ARCore frame; static spin cameras carry small tracking noise (position, rotation)."""
    G = g_matrix()
    items = []
    last = {}
    for i, v in enumerate(views, first):
        M = G @ v["c2w"]
        if noise_m > 0:
            key = v["turn"]
            if key not in last or rng.random() < 0.3:                  # tracking noise changes slowly
                ax = rng.normal(size=3)
                ax /= np.linalg.norm(ax)
                a = math.radians(rng.normal(0, noise_deg))
                K = np.array([[0, -ax[2], ax[1]], [ax[2], 0, -ax[0]], [-ax[1], ax[0], 0]])
                last[key] = (rng.normal(0, noise_m, 3), np.eye(3) + math.sin(a) * K + (1 - math.cos(a)) * K @ K)
            dt, dR = last[key]
            M = M.copy()
            M[:3, :3] = dR @ M[:3, :3]
            M[:3, 3] = M[:3, 3] + dt
        items.append({"file": f"{prefix}{i:06d}.jpg", "timestamp_ns": 1_000_000_000 * i,
                      "pose": [float(x) for x in M.flatten(order="F")], "fx": v["fx"], "fy": v["fy"],
                      "cx": v["cx"], "cy": v["cy"], "width": v["w"], "height": v["h"]})
    return {"schema": 1, "images": items}


def build_spin_job(out_dir: Path, fast: bool = False, seed: int = 11) -> dict:
    """Writes spin_job.zip (capture=spin: 2 x 36 static-camera frames + masks) and hybrid_job.zip (the same plus
    cloud.ply and 24 walk-around frames with true poses), plus spin_truth.json."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    spin, walk = spin_views(), walk_views()
    allv = spin + walk
    workers = max(1, min(10, (os.cpu_count() or 4) - 2))
    with ProcessPoolExecutor(workers) as ex:
        res = dict(ex.map(_render_spin_one, [(i, v, 1 if fast else 2) for i, v in enumerate(allv)]))
    imgs = [res[i] for i in range(len(allv))]
    rng = np.random.default_rng(seed)
    G = g_matrix()
    spin_poses = _poses_json(spin, rng, noise_m=0.002, noise_deg=0.1)
    walk_poses = _poses_json(walk, rng, noise_m=0.0, noise_deg=0.0)
    box = {"center": [float(x) for x in G[:3, 3] + [0, CUBE_SIDE / 2, 0]], "size": [CUBE_SIDE] * 3,
           "yaw_deg": G_YAW_DEG}
    plane = {"normal": [0.0, 1.0, 0.0], "d": float(-G_SHIFT[1])}
    centres = np.array([np.array(_pose_m(it["pose"]))[:3, 3] for it in spin_poses["images"]])
    mean_c = centres.mean(axis=0)
    base = G[:3, 3]
    manifest = {"schema": 1, "job_type": "photogrammetry", "units": "meters", "quality": "FINE",
                "image_count": len(spin), "coordinates": {"frame": "ARCore world", "units": "meters", "up": "+Y"},
                "capture": "spin", "camera_static": True, "box": box, "support_plane": plane,
                "rotation_axis": {"point": [float(x) for x in base], "direction": [0.0, 1.0, 0.0]},
                "camera_to_axis_m": float(math.hypot(mean_c[0] - base[0], mean_c[2] - base[2])),
                "camera_height_above_plane_m": float(mean_c[1] - G_SHIFT[1]),
                "masks_dir": "masks/", "mask_padding_m": 0.02,
                "files": [f"images/{i:06d}.jpg" for i in range(1, len(spin) + 1)]}
    # cloud.ply of the (static) object + support: noisy samples of the surface, ARCore frame
    from armeasure_pc.processing.common import write_ply_points
    crng = np.random.default_rng(seed + 1)
    h = CUBE_SIDE / 2
    pts = []
    for axis, val in ((0, -h), (0, h), (2, -h), (2, h)):
        o = [k for k in range(3) if k != axis]
        p = np.zeros((1200, 3))
        p[:, axis] = val
        p[:, o[0]] = crng.uniform(-h, h, 1200) if o[0] != 1 else crng.uniform(0, CUBE_SIDE, 1200)
        p[:, o[1]] = crng.uniform(-h, h, 1200) if o[1] != 1 else crng.uniform(0, CUBE_SIDE, 1200)
        pts.append(p)
    pts.append(np.column_stack([crng.uniform(-h, h, 1200), np.full(1200, CUBE_SIDE), crng.uniform(-h, h, 1200)]))
    gx = crng.uniform(-0.3, 0.3, (3000, 2))
    pts.append(np.column_stack([gx[:, 0], np.zeros(3000), gx[:, 1]]))
    P = np.vstack(pts) + crng.normal(0, 0.003, (sum(len(q) for q in pts), 3))
    P = P @ G[:3, :3].T + G[:3, 3]
    with tempfile_dir() as td:
        write_ply_points(td / "cloud.ply", P)
        cloud_bytes = (td / "cloud.ply").read_bytes()
    paths = {}
    for kind in ("spin", "hybrid"):
        zpath = out_dir / f"{kind}_job.zip"
        paths[kind] = zpath
        man = dict(manifest, capture=kind)
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:
            if kind == "hybrid":
                man["walk"] = {"dir": "walk/", "poses": "walk/poses.json", "images_dir": "walk/images/",
                               "image_count": len(walk)}
                man["files"] = man["files"] + ["cloud.ply"]
            z.writestr("manifest.json", json.dumps(man))
            z.writestr("poses.json", json.dumps(spin_poses))
            for i, (v, it) in enumerate(zip(spin, spin_poses["images"]), 1):
                z.writestr(it["file"], _jpeg(imgs[i - 1]))
                m = box_mask_png(box, _pose_m(it["pose"]), v)
                import io
                b = io.BytesIO()
                Image.fromarray(m, "L").save(b, "PNG")
                z.writestr(f"masks/{Path(it['file']).name}.png", b.getvalue())
            if kind == "hybrid":
                z.writestr("cloud.ply", cloud_bytes)
                z.writestr("walk/poses.json", json.dumps(walk_poses))      # file names relative to walk/ (as the phone)
                for i, it in enumerate(walk_poses["images"], 1):
                    z.writestr("walk/" + it["file"], _jpeg(imgs[len(spin) + i - 1]))
    truth = {"cube_side_m": CUBE_SIDE, "cube_volume_m3": CUBE_SIDE ** 3, "n_spin": len(spin), "n_walk": len(walk),
             "camera_dist_m": SPIN_DIST_M, "elev_deg": list(SPIN_ELEV_DEG), "step_deg": SPIN_STEP_DEG,
             "g_yaw_deg": G_YAW_DEG, "g_shift": G_SHIFT.tolist(), "box": box, "support_plane": plane,
             "camera_to_axis_m": manifest["camera_to_axis_m"],
             "camera_height_above_plane_m": manifest["camera_height_above_plane_m"]}
    (out_dir / "spin_truth.json").write_text(json.dumps(truth))
    return {"zips": paths, **truth}


def _pose_m(p16) -> np.ndarray:
    return np.asarray(p16, float).reshape(4, 4, order="F")


class tempfile_dir:
    def __enter__(self):
        import tempfile
        self._d = tempfile.TemporaryDirectory()
        return Path(self._d.name)

    def __exit__(self, *a):
        self._d.cleanup()


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["phone", "drone", "spin"])
    ap.add_argument("out")
    ap.add_argument("--jitter", action="store_true")
    ap.add_argument("--gps-noise", type=float, default=0.0)
    ap.add_argument("--fast", action="store_true")
    a = ap.parse_args()
    import time
    t = time.time()
    if a.kind == "spin":
        r = build_spin_job(Path(a.out), a.fast)
        print("wrote", *r["zips"].values(), f"{time.time() - t:.1f} s")
    else:
        r = build_phone_job(Path(a.out), a.jitter, a.fast) if a.kind == "phone" else \
            build_drone_job(Path(a.out), a.gps_noise, a.fast)
        print("wrote", r["zip"], f"{time.time() - t:.1f} s")
