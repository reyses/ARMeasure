"""Top-down orthographic render of a coloured point set (numpy z-buffer) with a scale bar.

APPROXIMATION ONLY: a nadir splat of the dense cloud / mesh vertices, not a survey-grade orthomosaic (no
seam-line blending, no true-ortho occlusion handling beyond "highest point wins")."""
from __future__ import annotations

import numpy as np

NICE = [0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000]


def render_ortho(xyz: np.ndarray, rgb: np.ndarray, gsd_m: float, max_px: int = 4096,
                 fill_iters: int = 6) -> tuple[np.ndarray, dict]:
    """Rasterise points (N,3 ENU metres, z up) looking down; north is up, east is right.

    Pixel size = gsd_m, enlarged when the scene would exceed max_px on its long side.
    Returns (H,W,3 uint8 image without scale bar, info{pixel_size_m, x0, y1, width_px, height_px})."""
    if len(xyz) == 0:
        raise ValueError("no points to render")
    x0, y0 = float(xyz[:, 0].min()), float(xyz[:, 1].min())
    x1, y1 = float(xyz[:, 0].max()), float(xyz[:, 1].max())
    px = max(float(gsd_m), max(x1 - x0, y1 - y0) / (max_px - 1), 1e-4)
    w, h = int((x1 - x0) / px) + 1, int((y1 - y0) / px) + 1
    col = np.minimum(((xyz[:, 0] - x0) / px).astype(np.int64), w - 1)
    row = np.minimum(((y1 - xyz[:, 1]) / px).astype(np.int64), h - 1)
    pix = row * w + col
    order = np.lexsort((xyz[:, 2], pix))           # per pixel ascending z; the last entry of a group wins
    p = pix[order]
    last = np.r_[p[1:] != p[:-1], True]
    sel = order[last]
    img = np.zeros((h * w, 3), np.uint8)
    filled = np.zeros(h * w, bool)
    img[pix[sel]] = np.asarray(rgb, np.uint8)[sel]
    filled[pix[sel]] = True
    img, filled = img.reshape(h, w, 3), filled.reshape(h, w)
    for _ in range(fill_iters):                     # close small holes with the mean of filled 8-neighbours
        if filled.all():
            break
        acc, cnt = np.zeros((h, w, 3), np.float64), np.zeros((h, w), np.float64)
        f3 = img.astype(np.float64) * filled[..., None]
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dy == 0 and dx == 0:
                    continue
                acc += np.roll(np.roll(f3, dy, 0), dx, 1)
                cnt += np.roll(np.roll(filled, dy, 0), dx, 1)
        new = (~filled) & (cnt >= 3)
        img[new] = (acc[new] / cnt[new][:, None]).astype(np.uint8)
        filled = filled | new
    return img, {"pixel_size_m": px, "x0": x0, "y1": y1, "width_px": w, "height_px": h,
                 "filled_fraction": float(filled.mean())}


def pick_bar_length(width_m: float) -> float:
    """Largest 'nice' length (1-2-5 series) not above about 25 % of the image width."""
    target = width_m * 0.25
    ok = [n for n in NICE if n <= target]
    return float(ok[-1] if ok else NICE[0])


def draw_scale_bar(img: np.ndarray, pixel_size_m: float) -> tuple[np.ndarray, dict]:
    """Burn a black/white scale bar with a label into the lower-left corner. Returns (image, bar info)."""
    from PIL import Image, ImageDraw, ImageFont
    h, w = img.shape[:2]
    length_m = pick_bar_length(w * pixel_size_m)
    bar_px = max(int(round(length_m / pixel_size_m)), 2)
    pad = max(8, int(min(w, h) * 0.02))
    bh = max(6, int(min(w, h) * 0.01))
    pil = Image.fromarray(img)
    d = ImageDraw.Draw(pil)
    try:
        font = ImageFont.load_default(size=max(14, int(min(w, h) * 0.025)))
    except TypeError:
        font = ImageFont.load_default()
    label = f"{length_m:g} m"
    tb = d.textbbox((0, 0), label, font=font)
    box_w = max(bar_px, tb[2] - tb[0]) + 2 * pad
    box_h = bh + (tb[3] - tb[1]) + 3 * pad
    bx0, by1 = pad, h - pad
    bx1, by0 = min(bx0 + box_w, w), max(by1 - box_h, 0)
    d.rectangle([bx0, by0, bx1, by1], fill=(255, 255, 255))
    x_bar0, y_bar1 = bx0 + pad, by1 - pad
    d.rectangle([x_bar0, y_bar1 - bh, x_bar0 + bar_px, y_bar1], fill=(0, 0, 0))
    d.text((x_bar0, by0 + pad // 2), label, fill=(0, 0, 0), font=font)
    out = np.asarray(pil)
    return out, {"length_m": length_m, "length_px": bar_px, "x_px": [x_bar0, x_bar0 + bar_px], "y_px": y_bar1 - bh // 2}
