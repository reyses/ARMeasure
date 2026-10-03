"""EXIF (exifread) + DJI XMP metadata of drone photos -> one row per image (images_meta.json)."""
from __future__ import annotations

import json
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

DJI_NS = "http://www.dji.com/drone-dji/1.0/"
XMP_FLOATS = {"GimbalPitchDegree": "gimbal_pitch_deg", "GimbalYawDegree": "gimbal_yaw_deg",
              "GimbalRollDegree": "gimbal_roll_deg", "FlightYawDegree": "flight_yaw_deg",
              "FlightPitchDegree": "flight_pitch_deg", "FlightRollDegree": "flight_roll_deg",
              "RelativeAltitude": "relative_altitude_m", "AbsoluteAltitude": "absolute_altitude_m",
              "CalibratedFocalLength": "calibrated_focal_px"}
XMP_GPS = {"GpsLatitude": "xmp_lat", "GpsLongitude": "xmp_lon"}


def _num(s) -> float | None:
    try:
        return float(str(s).strip())        # float() accepts a leading "+", as DJI writes "+3.40"
    except (TypeError, ValueError):
        return None


def parse_xmp(packet: bytes) -> dict:
    """Pick the drone-dji:* numeric fields out of an XMP packet (attribute or element form)."""
    if not packet or b"<!DOCTYPE" in packet or b"<!ENTITY" in packet:    # no entity tricks in untrusted files
        return {}
    try:
        root = ET.fromstring(packet)
    except ET.ParseError:
        return {}
    out: dict = {}
    pre = "{" + DJI_NS + "}"
    for el in root.iter():
        items = list(el.attrib.items())
        if el.tag.startswith(pre) and el.text and el.text.strip():
            items.append((el.tag, el.text))
        for k, v in items:
            if not k.startswith(pre):
                continue
            name = k[len(pre):]
            key = XMP_FLOATS.get(name) or XMP_GPS.get(name)
            if key and (x := _num(v)) is not None:
                out[key] = x
    return out


def read_xmp_packet(path: Path, limit: int = 1024 * 1024) -> bytes:
    with open(path, "rb") as f:
        head = f.read(limit)
    a = head.find(b"<x:xmpmeta")
    if a < 0:
        return b""
    end = b"</x:xmpmeta>"
    b = head.find(end, a)
    return head[a:b + len(end)] if b >= 0 else b""


def _ratio(v) -> float | None:
    if isinstance(v, (tuple, list)):
        v = v[0] if v else None
    try:
        return float(v.num) / float(v.den) if v.den else None
    except AttributeError:
        return _num(v)


def _dms(tag, ref) -> float | None:
    try:
        d, m, s = (_ratio(x) for x in tag.values)
        val = d + m / 60.0 + s / 3600.0
    except (TypeError, ValueError):
        return None
    return -val if str(ref).strip().upper() in ("S", "W") else val


def _val0(tags: dict, key: str):
    t = tags.get(key)
    if t is None:
        return None
    return t.values[0] if isinstance(t.values, list) and t.values else None


def read_image_meta(path: Path) -> dict:
    """Metadata row for one image; absent fields are None (a bad EXIF block never raises)."""
    import exifread
    from PIL import Image
    path = Path(path)
    row: dict = {"file": path.name, "width": None, "height": None, "make": None, "model": None,
                 "focal_length_mm": None, "focal_length_35mm": None, "focal_plane_x_resolution": None,
                 "focal_plane_resolution_unit": None, "datetime": None, "timestamp_s": None,
                 "lat": None, "lon": None, "alt_m": None}
    try:
        with open(path, "rb") as f:
            tags = exifread.process_file(f, details=False, extract_thumbnail=False)
    except Exception:  # noqa: BLE001
        tags = {}
    try:
        with Image.open(path) as im:
            row["width"], row["height"] = im.size
    except Exception:  # noqa: BLE001
        row["width"] = _num(_val0(tags, "EXIF ExifImageWidth"))
        row["height"] = _num(_val0(tags, "EXIF ExifImageLength"))
    for key, tag in (("make", "Image Make"), ("model", "Image Model")):
        t = tags.get(tag)
        row[key] = str(t.values).strip().strip("\x00") if t else None
    v = _val0(tags, "EXIF FocalLength")
    row["focal_length_mm"] = _ratio(v) if v is not None else None
    v = _val0(tags, "EXIF FocalLengthIn35mmFilm")
    row["focal_length_35mm"] = _num(v)
    v = _val0(tags, "EXIF FocalPlaneXResolution")
    row["focal_plane_x_resolution"] = _ratio(v) if v is not None else None
    v = _num(_val0(tags, "EXIF FocalPlaneResolutionUnit"))
    row["focal_plane_resolution_unit"] = int(v) if v is not None else None
    t = tags.get("EXIF DateTimeOriginal") or tags.get("Image DateTime")
    if t:
        try:
            dt = datetime.strptime(str(t.values).strip(), "%Y:%m:%d %H:%M:%S")
            sub = tags.get("EXIF SubSecTimeOriginal")
            s = str(sub.values).strip() if sub else ""
            row["datetime"] = dt.isoformat()
            row["timestamp_s"] = dt.timestamp() + (float("0." + s) if s.isdigit() else 0.0)
        except ValueError:
            pass
    lat, lon = tags.get("GPS GPSLatitude"), tags.get("GPS GPSLongitude")
    if lat and lon:
        row["lat"] = _dms(lat, tags["GPS GPSLatitudeRef"].values if "GPS GPSLatitudeRef" in tags else "N")
        row["lon"] = _dms(lon, tags["GPS GPSLongitudeRef"].values if "GPS GPSLongitudeRef" in tags else "E")
    alt = _val0(tags, "GPS GPSAltitude")
    if alt is not None:
        a = _ratio(alt)
        ref = _num(_val0(tags, "GPS GPSAltitudeRef"))
        row["alt_m"] = -a if (a is not None and ref == 1) else a
    x = parse_xmp(read_xmp_packet(path))
    for k in XMP_FLOATS.values():
        row[k] = x.get(k)
    if row["lat"] is None and "xmp_lat" in x and "xmp_lon" in x:
        row["lat"], row["lon"] = x["xmp_lat"], x["xmp_lon"]
    if row["alt_m"] is None:
        row["alt_m"] = x.get("absolute_altitude_m")
    return row


def build_table(paths: list[Path]) -> list[dict]:
    return [read_image_meta(p) for p in sorted(paths, key=lambda p: p.name)]


def write_table(rows: list[dict], dest: Path) -> None:
    dest.write_text(json.dumps({"schema": 1, "images": rows}, indent=1), encoding="utf-8")


def has_gps(row: dict) -> bool:
    return row.get("lat") is not None and row.get("lon") is not None and row.get("alt_m") is not None


def time_ordered(rows: list[dict]) -> bool:
    """True when sorting by file name also sorts by capture time (>= 90 % of images carry a time)."""
    ts = [r.get("timestamp_s") for r in rows]
    if len(rows) < 3 or sum(t is not None for t in ts) < 0.9 * len(rows):
        return False
    known = [t for t in ts if t is not None]
    return all(b >= a for a, b in zip(known, known[1:])) and known[-1] > known[0]
