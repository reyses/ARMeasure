"""Beam bundle inspector (docs/BEAM.md): turns an unpacked bundle folder into things a developer (or an AI) can read.

    python -m armeasure_pc inspect-bundle <folder|latest> [--no-video]

Writes into the bundle folder:
    summary.md          manifest, timeline digest (counts, last 50 events), errors with stacks, analyze results
    contact_sheet.jpg   grid of the snapshots with timestamps
    video_frames/       1 frame per second of the ARCore recording (arcore_NNNN.jpg) and the screen recording (screen_NNNN.jpg)
    timeline.html       events on a time axis, with the nearest snapshot thumbnail beside each event
"""
from __future__ import annotations

import bisect
import html
import json
import re
import shutil
import subprocess
from pathlib import Path

SNAP_RE = re.compile(r"snap-(\d+)-(\d+)\.jpg$")
QUIET_TYPES = ("fps", "thermal", "frame")
MAX_FRAMES = 900          # per video: 15 minutes at 1 frame/s


# ---------------------------------------------------------------- loading
def read_manifest(d: Path) -> dict:
    try:
        return json.loads((d / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def read_timeline(d: Path) -> list[dict]:
    out: list[dict] = []
    try:
        with open(d / "timeline.jsonl", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    e = json.loads(line)
                except ValueError:
                    continue
                if isinstance(e, dict) and "type" in e:
                    out.append(e)
    except OSError:
        pass
    return out


def list_snapshots(d: Path) -> list[tuple[int, Path]]:
    """[(wall_ms, path)] sorted by time; files not named snap-<wall>-<mono>.jpg are ignored."""
    out = []
    for p in (d / "snapshots").glob("*.jpg") if (d / "snapshots").is_dir() else []:
        m = SNAP_RE.search(p.name)
        if m:
            out.append((int(m.group(1)), p))
    return sorted(out)


def nearest_snapshot(snaps: list[tuple[int, Path]], wall_ms: int) -> tuple[int, Path] | None:
    if not snaps:
        return None
    times = [t for t, _ in snaps]
    i = bisect.bisect_left(times, wall_ms)
    cands = [snaps[j] for j in (i - 1, i) if 0 <= j < len(snaps)]
    return min(cands, key=lambda s: abs(s[0] - wall_ms))


# ---------------------------------------------------------------- formatting helpers
def fmt_clock(wall_ms: int) -> str:
    from datetime import datetime, timezone
    return datetime.fromtimestamp(wall_ms / 1000, timezone.utc).strftime("%H:%M:%S.%f")[:-3] + "Z"


def compact(data, limit: int = 220) -> str:
    s = json.dumps(data, ensure_ascii=False, separators=(",", ":")) if data not in (None, {}) else ""
    return s if len(s) <= limit else s[: limit - 3] + "..."


def event_line(e: dict, t0: int) -> str:
    rel = (e.get("t_wall_ms", t0) - t0) / 1000
    return f"+{rel:8.2f} s  #{e.get('seq', '?'):<5} {e['type']:<16} {compact(e.get('data'))}"


# ---------------------------------------------------------------- summary.md
def build_summary(d: Path, manifest: dict, events: list[dict], snaps, frames: dict[str, int], notes: list[str]) -> str:
    L: list[str] = []
    L.append(f"# Beam bundle {manifest.get('session_id') or d.name}\n")
    if manifest:
        L.append("| field | value |\n|---|---|")
        for k in ("kind", "label", "reason", "app", "version", "commit", "device", "android_api"):
            L.append(f"| {k} | {manifest.get(k, '')} |")
        s, e = manifest.get("start_wall_ms"), manifest.get("end_wall_ms")
        if isinstance(s, int) and isinstance(e, int):
            L.append(f"| start (UTC) | {fmt_clock(s)} |\n| end (UTC) | {fmt_clock(e)} |\n| duration | {(e - s) / 1000:.1f} s |")
        L.append(f"| events | {manifest.get('event_total', len(events))} |\n| snapshots | {manifest.get('snapshot_count', len(snaps))} |")
        L.append("")
    else:
        L.append("(no manifest.json in this folder)\n")
    reason = manifest.get("reason")
    if reason == "crash":
        L.append("**The previous run died without closing this session (crash or kill).**\n")

    files = manifest.get("files") or []
    if files:
        L.append("## Files\n")
        by_role: dict[str, list] = {}
        for f in files:
            by_role.setdefault(f.get("role", "?"), []).append(f)
        for role, fs in sorted(by_role.items()):
            total = sum(int(f.get("bytes", 0)) for f in fs)
            L.append(f"- {role}: {len(fs)} file(s), {total / 1024 / 1024:.2f} MB" + (f" ({fs[0]['name']})" if len(fs) == 1 else ""))
        L.append("")
    for sk in manifest.get("skipped") or []:
        L.append(f"- SKIPPED {sk.get('name')} ({int(sk.get('bytes', 0)) / 1024 / 1024:.1f} MB): {sk.get('why')}")
    if manifest.get("skipped"):
        L.append("")

    t0 = events[0].get("t_wall_ms", 0) if events else 0
    L.append("## Timeline digest\n")
    if not events:
        L.append("(no events)\n")
    else:
        counts: dict[str, int] = {}
        for e in events:
            counts[e["type"]] = counts.get(e["type"], 0) + 1
        span = (events[-1].get("t_wall_ms", t0) - t0) / 1000
        L.append(f"{len(events)} events over {span:.1f} s (clock: wall ms from the phone; first event {fmt_clock(t0)}).\n")
        L.append("| type | count |\n|---|---|")
        for k, v in sorted(counts.items(), key=lambda kv: -kv[1]):
            L.append(f"| {k} | {v} |")
        L.append("")
        fps = [e["data"].get("fps") for e in events if e["type"] == "fps" and isinstance(e.get("data"), dict) and isinstance(e["data"].get("fps"), (int, float))]
        if fps:
            L.append(f"fps: min {min(fps):.1f}, mean {sum(fps) / len(fps):.1f}, max {max(fps):.1f} over {len(fps)} s of samples.")
        th = [e["data"].get("status") for e in events if e["type"] == "thermal" and isinstance(e.get("data"), dict)]
        if th:
            L.append(f"thermal status seen: {sorted(set(th))} (0 none ... 6 shutdown).")
        modes = [e["data"].get("mode") for e in events if e["type"] == "mode_change" and isinstance(e.get("data"), dict)]
        if modes:
            L.append("modes: " + " -> ".join(str(m) for m in modes[:40]))
        L.append("")

        L.append("## Errors\n")
        errs = [e for e in events if e["type"] == "error"]
        if not errs:
            L.append("none\n")
        for e in errs:
            data = e.get("data") or {}
            L.append(f"### {event_line(e, t0).split('  ', 1)[0].strip()} {data.get('message', '')}\n")
            if data.get("stack"):
                L.append("```\n" + str(data["stack"]).rstrip() + "\n```\n")
        if (d / "crash.txt").is_file():
            L.append("### crash.txt (from the previous run)\n\n```\n" + (d / "crash.txt").read_text(encoding="utf-8", errors="replace").rstrip()[:12000] + "\n```\n")

        L.append("## Analyze results and exports\n")
        res = [e for e in events if e["type"] in ("analyze_result", "export", "gpu_gate", "capture_start", "capture_stop")]
        if not res:
            L.append("none\n")
        for e in res:
            L.append("- " + event_line(e, t0))
        L.append("")

        L.append("## Last 50 events\n")
        L.append("```")
        tail = [e for e in events if e["type"] not in QUIET_TYPES][-50:]
        L.extend(event_line(e, t0) for e in tail)
        L.append("```\n")

    L.append("## Visuals\n")
    L.append(f"- snapshots: {len(snaps)} (contact_sheet.jpg, timeline.html)")
    for tag, n in frames.items():
        L.append(f"- video_frames/{tag}_NNNN.jpg: {n} frame(s), 1 per second")
    for n in notes:
        L.append(f"- note: {n}")
    for name in ("diagnostics.txt", "logcat.txt"):
        if (d / name).is_file():
            L.append(f"- {name}: {(d / name).stat().st_size / 1024:.0f} KB")
    L.append("")
    return "\n".join(L)


# ---------------------------------------------------------------- contact sheet
def build_contact_sheet(d: Path, snaps, events: list[dict], out: Path, max_tiles: int = 60, cols: int = 6, tile_w: int = 270) -> bool:
    if not snaps:
        return False
    from PIL import Image, ImageDraw
    if len(snaps) > max_tiles:
        step = len(snaps) / max_tiles
        snaps = [snaps[int(i * step)] for i in range(max_tiles)]
    t0 = snaps[0][0]
    tiles = []
    for wall, p in snaps:
        try:
            with Image.open(p) as im:
                im = im.convert("RGB")
                h = max(1, round(im.height * tile_w / im.width))
                tiles.append((wall, im.resize((tile_w, h))))
        except OSError:
            continue
    if not tiles:
        return False
    th = max(t.height for _, t in tiles)
    label_h = 18
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tile_w, rows * (th + label_h)), (24, 24, 24))
    dr = ImageDraw.Draw(sheet)
    for i, (wall, im) in enumerate(tiles):
        x, y = (i % cols) * tile_w, (i // cols) * (th + label_h)
        sheet.paste(im, (x, y + label_h))
        dr.text((x + 3, y + 3), f"+{(wall - t0) / 1000:.1f}s  {fmt_clock(wall)}", fill=(230, 230, 230))
    sheet.save(out, "JPEG", quality=85)
    return True


# ---------------------------------------------------------------- video frames
def ffmpeg_exe() -> str | None:
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:  # noqa: BLE001
        return shutil.which("ffmpeg")


def extract_frames(video: Path, out_dir: Path, tag: str, ffmpeg: str, timeout: int = 900) -> int:
    """1 frame per second, max MAX_FRAMES, 640 px wide, written as <tag>_NNNN.jpg. Returns the number of frames."""
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob(f"{tag}_*.jpg"):
        old.unlink()
    cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(video), "-map", "0:v:0", "-vf", "fps=1,scale=640:-2",
           "-frames:v", str(MAX_FRAMES), "-q:v", "4", str(out_dir / f"{tag}_%04d.jpg")]
    try:
        subprocess.run(cmd, capture_output=True, timeout=timeout, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.TimeoutExpired):
        pass
    return len(list(out_dir.glob(f"{tag}_*.jpg")))


# ---------------------------------------------------------------- timeline.html
COLORS = {"error": "#d33", "tap": "#27a", "hit_quality": "#8a2", "box_placed": "#a62", "box_moved": "#a62", "box_resized": "#a62",
          "analyze_result": "#639", "export": "#639", "capture_start": "#093", "capture_stop": "#093", "mode_change": "#c80",
          "screen_change": "#c80", "tracking_state": "#06a", "gpu_gate": "#888", "app_start": "#555", "app_stop": "#555"}


def build_timeline_html(d: Path, manifest: dict, events: list[dict], snaps, max_rows: int = 2500) -> str:
    esc = html.escape
    if not events:
        return "<!doctype html><meta charset=utf-8><title>Beam timeline</title><body>No events.</body>"
    t0 = events[0].get("t_wall_ms", 0)
    t1 = max(events[-1].get("t_wall_ms", t0), t0 + 1)
    rows = events if len(events) <= max_rows else [e for e in events if e["type"] not in QUIET_TYPES][:max_rows]
    ticks, trs = [], []
    for i, e in enumerate(rows):
        w = e.get("t_wall_ms", t0)
        pct = (w - t0) / (t1 - t0) * 100
        col = COLORS.get(e["type"], "#999")
        ticks.append(f'<a class="tick" data-t="{esc(e["type"])}" href="#r{i}" style="left:{pct:.3f}%;background:{col}" title="{esc(e["type"])} +{(w - t0) / 1000:.2f}s"></a>')
        ns = nearest_snapshot(snaps, w)
        img = ""
        if ns:
            rel = ns[1].relative_to(d).as_posix()
            img = f'<a href="{esc(rel)}"><img loading="lazy" src="{esc(rel)}" width="110"></a><div class="dt">{(ns[0] - w) / 1000:+.1f}s</div>'
        trs.append(f'<tr id="r{i}" data-t="{esc(e["type"])}"><td class="t">+{(w - t0) / 1000:.2f}s</td>'
                   f'<td><span class="chip" style="background:{col}">{esc(e["type"])}</span></td>'
                   f'<td class="d">{esc(compact(e.get("data"), 600))}</td><td class="im">{img}</td></tr>')
    types = sorted({e["type"] for e in rows})
    boxes = "".join(f'<label><input type="checkbox" data-f="{esc(t)}" {"" if t in QUIET_TYPES else "checked"}> {esc(t)}</label> ' for t in types)
    title = esc(str(manifest.get("session_id") or d.name))
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Beam timeline {title}</title>
<style>
body{{font:13px/1.4 system-ui,sans-serif;margin:12px;background:#fafafa;color:#222}}
@media (prefers-color-scheme:dark){{body{{background:#181818;color:#ddd}}}}
.axis{{position:relative;height:34px;border-bottom:2px solid #888;margin:8px 0 14px}}
.tick{{position:absolute;bottom:0;width:3px;height:26px;opacity:.85}}
table{{border-collapse:collapse;width:100%}} td{{vertical-align:top;padding:3px 6px;border-bottom:1px solid #8884}}
.t{{white-space:nowrap;font-variant-numeric:tabular-nums}} .d{{font-family:ui-monospace,monospace;word-break:break-all}}
.chip{{color:#fff;padding:1px 6px;border-radius:8px;font-size:12px}} .dt{{font-size:11px;opacity:.7}}
tr.hide{{display:none}} tr:target{{outline:2px solid #fc0}}
</style></head><body>
<h3>Beam timeline {title}</h3>
<div>{esc(str(manifest.get('kind', '')))} {esc(str(manifest.get('device', '')))} commit {esc(str(manifest.get('commit', '')))} - {len(events)} events over {(t1 - t0) / 1000:.1f} s, {len(snaps)} snapshots.</div>
<div class="axis">{''.join(ticks)}</div>
<div>{boxes}</div>
<table>{''.join(trs)}</table>
<script>
function apply(){{var on={{}};document.querySelectorAll('input[data-f]').forEach(function(b){{on[b.dataset.f]=b.checked}});
document.querySelectorAll('tr[data-t]').forEach(function(r){{r.classList.toggle('hide',!on[r.dataset.t])}});
document.querySelectorAll('a.tick').forEach(function(a){{a.style.display=on[a.dataset.t]?'':'none'}});}}
document.querySelectorAll('input[data-f]').forEach(function(b){{b.addEventListener('change',apply)}});apply();
</script></body></html>
"""


# ---------------------------------------------------------------- entry
def inspect_bundle(d: Path, video: bool = True) -> dict:
    """Writes summary.md, contact_sheet.jpg, video_frames/ and timeline.html into [d]; returns what was written."""
    d = Path(d)
    if not d.is_dir():
        raise FileNotFoundError(f"not a bundle folder: {d}")
    manifest = read_manifest(d)
    events = read_timeline(d)
    snaps = list_snapshots(d)
    notes: list[str] = []
    frames: dict[str, int] = {}
    if video:
        videos = [("arcore", p) for p in sorted((d / "recordings").glob("*.mp4"))] if (d / "recordings").is_dir() else []
        videos += [("screen", p) for p in sorted((d / "screen").glob("*.mp4"))] if (d / "screen").is_dir() else []
        if videos:
            ff = ffmpeg_exe()
            if ff is None:
                notes.append("no ffmpeg available (pip install imageio-ffmpeg): video frames were not extracted")
            else:
                for tag, p in videos:
                    n = extract_frames(p, d / "video_frames", tag, ff)
                    frames[tag] = frames.get(tag, 0) + n
                    if n == 0:
                        notes.append(f"ffmpeg produced no frames from {p.name}")
    out = {"summary": d / "summary.md", "timeline": d / "timeline.html"}
    (d / "timeline.html").write_text(build_timeline_html(d, manifest, events, snaps), encoding="utf-8")
    if build_contact_sheet(d, snaps, events, d / "contact_sheet.jpg"):
        out["contact_sheet"] = d / "contact_sheet.jpg"
    (d / "summary.md").write_text(build_summary(d, manifest, events, snaps, frames, notes), encoding="utf-8")
    if frames:
        out["video_frames"] = d / "video_frames"
    return out
