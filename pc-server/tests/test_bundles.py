"""Beam bundles: chunked upload, resume, sha256, zip-slip, retention; bundle_inspect on a synthetic bundle."""
import hashlib
import io
import json
import subprocess
import zipfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from armeasure_pc import bundle_inspect, bundles
from armeasure_pc.__main__ import main as cli_main
from armeasure_pc.app import create_app
from conftest import AUTH, TOKEN

T0 = 1_700_000_000_000


def jpeg(color=(200, 30, 30), size=(54, 120)) -> bytes:
    b = io.BytesIO()
    Image.new("RGB", size, color).save(b, "JPEG")
    return b.getvalue()


def timeline_lines(n_taps=5) -> str:
    ev = [{"seq": 1, "t_mono_ms": 10, "t_wall_ms": T0, "type": "session_start", "data": {"kind": "scan"}},
          {"seq": 2, "t_mono_ms": 20, "t_wall_ms": T0 + 100, "type": "mode_change", "data": {"mode": "SCAN"}}]
    for i in range(n_taps):
        ev.append({"seq": 3 + i, "t_mono_ms": 30 + i, "t_wall_ms": T0 + 1000 * (i + 1), "type": "tap",
                   "data": {"x": 10 * i, "y": 5, "target": "box"}})
    ev.append({"seq": 20, "t_mono_ms": 99, "t_wall_ms": T0 + 6200, "type": "fps", "data": {"fps": 29.5, "frames": 30}})
    ev.append({"seq": 21, "t_mono_ms": 100, "t_wall_ms": T0 + 6500, "type": "error",
               "data": {"message": "boom", "stack": "java.lang.IllegalStateException: boom\n\tat a.b(C.kt:1)"}})
    ev.append({"seq": 22, "t_mono_ms": 101, "t_wall_ms": T0 + 7000, "type": "analyze_result", "data": {"summary": "3 surfaces, 12 cm RMS"}})
    return "\n".join(json.dumps(e) for e in ev) + "\n"


def make_bundle_zip(extra: dict | None = None, session="20231114-221320-scan-beef", video: bytes | None = None) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps({"schema": 1, "session_id": session, "kind": "scan", "reason": "analyze_done",
                                                "app": "com.example.arruler", "commit": "abc1234", "device": "Google Pixel 11 Pro",
                                                "start_wall_ms": T0, "end_wall_ms": T0 + 7000, "event_total": 24,
                                                "snapshot_count": 4, "files": [], "skipped": []}))
        z.writestr("timeline.jsonl", timeline_lines())
        for i in range(4):
            z.writestr(f"snapshots/snap-{T0 + 500 + 1700 * i:013d}-{i}.jpg", jpeg((50 * i, 60, 200 - 40 * i)))
        if video is not None:
            z.writestr("recordings/arcore.mp4", video)
        for k, v in (extra or {}).items():
            z.writestr(k, v)
    return buf.getvalue()


def ffmpeg_video(tmp_path: Path, seconds=3) -> bytes:
    exe = bundle_inspect.ffmpeg_exe()
    assert exe, "imageio-ffmpeg must provide a bundled ffmpeg"
    out = tmp_path / "fake.mp4"
    r = subprocess.run([exe, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                        f"testsrc=duration={seconds}:size=320x240:rate=10", "-pix_fmt", "yuv420p", str(out)],
                       capture_output=True, timeout=120)
    assert r.returncode == 0, r.stderr.decode(errors="replace")
    return out.read_bytes()


@pytest.fixture()
def bc(tmp_path):
    app = create_app(tmp_path / "data", token=TOKEN)
    app.state.bundles.chunk_size = 1000
    with TestClient(app) as c:
        c.store = app.state.bundles
        c.data = tmp_path / "data"
        yield c


def upload(c, data: bytes, name="b.zip", skip=(), complete=True):
    sha = hashlib.sha256(data).hexdigest()
    r = c.post("/v1/dev/bundles", json={"name": name, "size": len(data), "sha256": sha}, headers=AUTH)
    assert r.status_code == 200, r.text
    j = r.json()
    cs = j["chunk_size"]
    for n in range(j["chunks"]):
        if n in skip or n in j["received"]:
            continue
        rr = c.put(f"/v1/dev/bundles/{j['id']}/chunks/{n}", content=data[n * cs:(n + 1) * cs], headers=AUTH)
        assert rr.status_code == 200, rr.text
    if not complete:
        return j
    return c.post(f"/v1/dev/bundles/{j['id']}/complete", headers=AUTH)


def test_chunked_upload_unpacks_into_date_and_session_folder(bc):
    data = make_bundle_zip()
    assert len(data) > 3000
    r = upload(bc, data)
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["state"] == "complete"
    dest = Path(j["path"])
    assert dest.parent.parent == bc.data / "devbundles" and dest.name == "20231114-221320-scan-beef"
    assert (dest / "manifest.json").is_file() and len(list((dest / "snapshots").glob("*.jpg"))) == 4
    assert not list((bc.data / "devbundles" / "incoming").glob("*"))      # incoming deleted on success


def test_resume_lists_received_chunks_and_a_lost_complete_can_be_retried(bc):
    data = make_bundle_zip()
    j = upload(bc, data, skip={1, 3}, complete=False)
    st = bc.get(f"/v1/dev/bundles/{j['id']}", headers=AUTH).json()
    assert st["state"] == "uploading" and 1 not in st["received"] and 0 in st["received"]
    assert bc.post(f"/v1/dev/bundles/{j['id']}/complete", headers=AUTH).status_code == 409
    # the same bundle created again resumes the same id with the same received list
    again = bc.post("/v1/dev/bundles", json={"name": "b.zip", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}, headers=AUTH).json()
    assert again["id"] == j["id"] and again["received"] == st["received"]
    r = upload(bc, data)
    assert r.json()["state"] == "complete"
    # complete answer lost: the phone asks again, nothing is re-uploaded
    assert bc.post(f"/v1/dev/bundles/{j['id']}/complete", headers=AUTH).json()["state"] == "complete"
    c2 = bc.post("/v1/dev/bundles", json={"name": "b.zip", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}, headers=AUTH).json()
    assert c2["state"] == "complete"
    assert bc.get(f"/v1/dev/bundles/{j['id']}", headers=AUTH).json()["state"] == "complete"


def test_sha_mismatch_is_422_and_discards_the_upload(bc):
    data = make_bundle_zip()
    j = bc.post("/v1/dev/bundles", json={"name": "b.zip", "size": len(data), "sha256": "0" * 64}, headers=AUTH).json()
    cs = j["chunk_size"]
    for n in range(j["chunks"]):
        assert bc.put(f"/v1/dev/bundles/{j['id']}/chunks/{n}", content=data[n * cs:(n + 1) * cs], headers=AUTH).status_code == 200
    r = bc.post(f"/v1/dev/bundles/{j['id']}/complete", headers=AUTH)
    assert r.status_code == 422 and "sha256" in r.json()["error"]["message"]
    assert bc.get(f"/v1/dev/bundles/{j['id']}", headers=AUTH).status_code == 404
    assert not list((bc.data / "devbundles").glob("2*/*"))


def test_validation_and_auth(bc):
    sha = "a" * 64
    assert bc.post("/v1/dev/bundles", json={"name": "x", "size": 10, "sha256": sha}).status_code == 401
    assert bc.put("/v1/dev/bundles/0123456789abcdef/chunks/0", content=b"x").status_code == 401
    assert bc.post("/v1/dev/bundles", json={"name": "x", "size": 0, "sha256": sha}, headers=AUTH).status_code == 400
    assert bc.post("/v1/dev/bundles", json={"name": "x", "size": 10, "sha256": "zz"}, headers=AUTH).status_code == 400
    assert bc.post("/v1/dev/bundles", json={"name": "x", "size": 5 * 1024 ** 3, "sha256": sha}, headers=AUTH).status_code == 413
    assert bc.post("/v1/dev/bundles", json={"name": "x"}, headers=AUTH).status_code == 400
    j = bc.post("/v1/dev/bundles", json={"name": "x", "size": 2500, "sha256": sha}, headers=AUTH).json()
    base = f"/v1/dev/bundles/{j['id']}/chunks"
    assert bc.put(f"{base}/0", content=b"x" * 999, headers=AUTH).status_code == 400          # wrong length
    assert bc.put(f"{base}/2", content=b"x" * 500, headers=AUTH).status_code == 200            # the short last chunk
    assert bc.put(f"{base}/3", content=b"x", headers=AUTH).status_code == 400                  # out of range
    assert bc.put(f"{base}/0", content=b"x" * 1001, headers=AUTH).status_code == 413           # over chunk size
    assert bc.get("/v1/dev/bundles/not-an-id", headers=AUTH).status_code == 404
    assert bc.get("/v1/dev/bundles/0123456789abcdef", headers=AUTH).status_code == 404
    assert bc.get("/v1/dev/bundles/..%2f..%2fconfig.json", headers=AUTH).status_code in (404, 400)


def test_default_chunk_is_8_mb_and_max_4_gb():
    from armeasure_pc import config
    assert config.BUNDLE_CHUNK_BYTES == 8 * 1024 * 1024
    assert config.BUNDLE_MAX_BYTES == 4 * 1024 ** 3 and config.BUNDLE_KEEP == 50


def test_zip_slip_and_symlinks_are_refused(bc, tmp_path):
    for evil in ("../escape.txt", "/abs.txt", "a/../../escape.txt", "C:/win.txt", "..\\back.txt"):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("manifest.json", json.dumps({"session_id": "evil"}))
            z.writestr(evil, "pwned")
        r = upload(bc, buf.getvalue(), name="evil.zip")
        assert r.status_code == 422, (evil, r.text)
    assert not (bc.data / "escape.txt").exists() and not (bc.data / "devbundles" / "escape.txt").exists()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        zi = zipfile.ZipInfo("link")
        zi.external_attr = (0o120777 << 16)
        z.writestr(zi, "/etc/passwd")
    assert upload(bc, buf.getvalue(), name="link.zip").status_code == 422
    assert not list((bc.data / "devbundles").glob("2*/*"))


def test_keeps_only_the_newest_n_bundles(bc):
    bc.store.keep = 3
    import os
    import time
    paths = []
    for i in range(5):
        r = upload(bc, make_bundle_zip(session=f"s{i}", extra={"pad.bin": bytes([i]) * 50}))
        assert r.status_code == 200
        p = Path(r.json()["path"])
        t = time.time() - 1000 + i
        os.utime(p, (t, t))
        paths.append(p)
    bc.store.prune()
    left = sorted(p.name for p in bc.store.sessions())
    assert left == ["s2", "s3", "s4"]
    assert bc.store.latest().name == "s4"


def test_session_folder_names_are_sanitised_and_unique(bc):
    a = upload(bc, make_bundle_zip(session="../../evil name"))
    b = upload(bc, make_bundle_zip(session="../../evil name", extra={"x.txt": "2"}))
    pa, pb = Path(a.json()["path"]), Path(b.json()["path"])
    assert pa != pb and pa.parent == pb.parent and pa.parent.parent == bc.data / "devbundles"
    assert ".." not in pa.name and "/" not in pa.name


# ---------------------------------------------------------------- inspector
def unpack(tmp_path, data: bytes) -> Path:
    d = tmp_path / "bundle"
    zp = tmp_path / "b.zip"
    zp.write_bytes(data)
    bundles.safe_extract(zp, d, 10 ** 9)
    return d


def test_inspect_writes_summary_contact_sheet_frames_and_timeline(tmp_path):
    d = unpack(tmp_path, make_bundle_zip(video=ffmpeg_video(tmp_path, 3), extra={"crash.txt": "crash at x\nthread main\nboom"}))
    out = bundle_inspect.inspect_bundle(d)
    summary = (d / "summary.md").read_text(encoding="utf-8")
    assert "20231114-221320-scan-beef" in summary and "Google Pixel 11 Pro" in summary and "abc1234" in summary
    assert "| tap | 5 |" in summary and "IllegalStateException: boom" in summary and "3 surfaces, 12 cm RMS" in summary
    assert "crash at x" in summary and "Last 50 events" in summary and "fps: min 29.5" in summary
    sheet = Image.open(d / "contact_sheet.jpg")
    assert sheet.width == 6 * 270 and sheet.height > 100
    frames = sorted((d / "video_frames").glob("arcore_*.jpg"))
    assert 2 <= len(frames) <= 4, frames       # 3 s of video at 1 frame/s
    assert Image.open(frames[0]).width == 640
    assert "video_frames/arcore_NNNN.jpg" in summary
    page = (d / "timeline.html").read_text(encoding="utf-8")
    assert page.count('class="tick"') == 10 and "snapshots/snap-" in page and "IllegalStateException" in page
    assert set(out) >= {"summary", "timeline", "contact_sheet", "video_frames"}


def test_nearest_snapshot_and_missing_pieces(tmp_path):
    snaps = [(100, Path("a")), (200, Path("b")), (900, Path("c"))]
    assert bundle_inspect.nearest_snapshot(snaps, 140)[1].name == "a"
    assert bundle_inspect.nearest_snapshot(snaps, 160)[1].name == "b"
    assert bundle_inspect.nearest_snapshot(snaps, 5000)[1].name == "c"
    assert bundle_inspect.nearest_snapshot([], 1) is None
    d = tmp_path / "empty"
    d.mkdir()
    out = bundle_inspect.inspect_bundle(d)           # nothing in it: still writes readable files
    assert "no manifest" in (d / "summary.md").read_text(encoding="utf-8") and "contact_sheet" not in out
    with pytest.raises(FileNotFoundError):
        bundle_inspect.inspect_bundle(tmp_path / "nope")


def test_inspect_survives_a_garbled_timeline_and_non_video(tmp_path):
    d = unpack(tmp_path, make_bundle_zip(video=b"this is not an mp4"))
    (d / "timeline.jsonl").write_text('{"seq":1,"t_wall_ms":5,"type":"tap","data":{}}\nnot json\n\n{"x":1}\n', encoding="utf-8")
    bundle_inspect.inspect_bundle(d)
    s = (d / "summary.md").read_text(encoding="utf-8")
    assert "| tap | 1 |" in s and "ffmpeg produced no frames" in s


def test_cli_inspect_latest(bc, tmp_path, capsys):
    r = upload(bc, make_bundle_zip())
    assert r.status_code == 200
    assert cli_main(["inspect-bundle", "latest", "--data-dir", str(bc.data), "--no-video"]) == 0
    dest = Path(r.json()["path"])
    assert (dest / "summary.md").is_file() and (dest / "timeline.html").is_file()
    assert "summary" in capsys.readouterr().out
    assert cli_main(["inspect-bundle", str(tmp_path / "missing")]) == 2
