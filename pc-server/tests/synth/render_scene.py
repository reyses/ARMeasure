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


class Box:
    """Axis-aligned textured box; faces are keyed (axis, sign) with sign 0 = low face, 1 = high face."""

    def __init__(self, lo, hi, tex: dict):
        self.lo, self.hi, self.tex = np.asarray(lo, np.float64), np.asarray(hi, np.float64), tex


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
            with np.errstate(divide="ignore", invalid="ignore"):
                inv = 1.0 / d
            for bx in boxes:
                t1, t2 = (bx.lo - o) * inv, (bx.hi - o) * inv
                tmin, tmax = np.minimum(t1, t2), np.maximum(t1, t2)
                tmin = np.where(np.isnan(tmin), -np.inf, tmin)
                tmax = np.where(np.isnan(tmax), np.inf, tmax)
                ax = np.argmax(tmin, 1)
                tn = tmin[np.arange(n), ax]
                tf = tmax.min(1)
                hit = (tn <= tf) & (tf > 0) & (tn > 1e-6) & (tn < best_t)
                if not hit.any():
                    continue
                idx = np.flatnonzero(hit)
                best_t[idx] = tn[idx]
                p = o + d[idx] * tn[idx, None]
                a = ax[idx]
                sg = (d[idx, a] < 0).astype(int)       # ray going -axis hits the HIGH face
                for axis in range(3):
                    for sign in (0, 1):
                        m = (a == axis) & (sg == sign)
                        if not m.any():
                            continue
                        o1, o2 = [k for k in range(3) if k != axis]
                        pp = p[m]
                        uu = (pp[:, o1] - bx.lo[o1]) / (bx.hi[o1] - bx.lo[o1])
                        vv = (pp[:, o2] - bx.lo[o2]) / (bx.hi[o2] - bx.lo[o2])
                        col[idx[m]] = _lookup(bx.tex[(axis, sign)], uu, vv)
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


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["phone", "drone"])
    ap.add_argument("out")
    ap.add_argument("--jitter", action="store_true")
    ap.add_argument("--gps-noise", type=float, default=0.0)
    ap.add_argument("--fast", action="store_true")
    a = ap.parse_args()
    import time
    t = time.time()
    r = build_phone_job(Path(a.out), a.jitter, a.fast) if a.kind == "phone" else \
        build_drone_job(Path(a.out), a.gps_noise, a.fast)
    print("wrote", r["zip"], f"{time.time() - t:.1f} s")
