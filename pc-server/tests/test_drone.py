import io
import json
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image
from PIL.TiffImagePlugin import IFDRational

from armeasure_pc import __main__ as cli
from armeasure_pc.app import create_app
from armeasure_pc.processing import drone, dronemeta, geo, ortho, photogrammetry

from conftest import AUTH, TOKEN, make_zip, wait_done

XMP = (b'<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/">'
       b'<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
       b'<rdf:Description xmlns:drone-dji="http://www.dji.com/drone-dji/1.0/" '
       b'drone-dji:GimbalPitchDegree="-89.90" drone-dji:GimbalYawDegree="+12.30" '
       b'drone-dji:FlightYawDegree="+10.10" drone-dji:RelativeAltitude="+30.05" '
       b'drone-dji:AbsoluteAltitude="+123.45"/></rdf:RDF></x:xmpmeta><?xpacket end="w"?>')


def _dms(v):
    d = int(v)
    m = int((v - d) * 60)
    return (float(d), float(m), round((v - d - m / 60) * 3600, 4))


def make_jpeg(path: Path, lat=47.0, lon=8.0, alt=500.0, size=(64, 48), xmp=XMP, dt="2026:10:03 10:00:00",
              seed=0, plane_res=None) -> Path:
    rng = np.random.default_rng(seed)
    im = Image.fromarray(rng.integers(0, 255, (size[1], size[0], 3), dtype=np.uint8))
    ex = Image.Exif()
    ex[0x010F], ex[0x0110] = "DJI", "FC3582"
    sub = ex.get_ifd(0x8769)
    sub[0x920A] = IFDRational(672, 100)
    sub[0x9003] = dt
    if plane_res:
        sub[0xA20E], sub[0xA210] = IFDRational(int(plane_res * 1000), 1000), 4
    gps = ex.get_ifd(0x8825)
    gps[1], gps[2] = ("N" if lat >= 0 else "S"), _dms(abs(lat))
    gps[3], gps[4] = ("E" if lon >= 0 else "W"), _dms(abs(lon))
    gps[5], gps[6] = 0, alt
    im.save(path, "JPEG", exif=ex, xmp=xmp)
    return path


def make_folder(tmp_path, n=4, **kw) -> Path:
    d = tmp_path / "photos"
    d.mkdir()
    for i in range(n):
        make_jpeg(d / f"DJI_20261003100{i}00_{i:04d}_D.JPG", lat=47.0 + i * 1e-4, dt=f"2026:10:03 10:00:0{i}",
                  seed=i, **kw)
    return d


# ---------------------------------------------------------------- geodesy
def test_enu_known_points():
    org = (47.0, 8.0, 500.0)
    n = geo.geodetic_to_enu(47.0 + 1e-4, 8.0, 500.0, org)
    assert abs(n[1] - 11.1) < 0.1 and abs(n[0]) < 0.01 and abs(n[2]) < 0.01          # 1e-4 deg north ~ 11.1 m
    e = geo.geodetic_to_enu(47.0, 8.0 + 1e-4, 500.0, org)
    assert abs(e[0] - 11.132 * np.cos(np.radians(47.0))) < 0.02 and abs(e[1]) < 0.01  # east shrinks with cos(lat)
    u = geo.geodetic_to_enu(47.0, 8.0, 530.0, org)
    assert abs(u[2] - 30.0) < 1e-6
    assert np.allclose(geo.geodetic_to_enu(47.0, 8.0, 500.0, org), 0, atol=1e-6)
    ecef = geo.wgs84_to_ecef(0.0, 0.0, 0.0)
    assert abs(ecef[0] - 6378137.0) < 1e-6


def test_gsd_and_camera_constant():
    row = {"focal_length_mm": 6.72, "width": 4032}
    cam = geo.camera_from_meta(row)
    assert cam["source"].startswith("constant") and abs(cam["focal_px"] - 6.72 / 9.7 * 4032) < 1e-6
    g30 = geo.gsd_cm_per_px(30, 6.72, cam["pixel_pitch_mm"])
    assert abs(g30 - 1.0737) < 0.001                      # 30 m, 12 MP
    row2 = {**row, "focal_plane_x_resolution": 4032 / 9.68, "focal_plane_resolution_unit": 4}
    cam2 = geo.camera_from_meta(row2)
    assert cam2["source"] == "exif_focal_plane_resolution" and abs(cam2["sensor_width_mm"] - 9.68) < 1e-9
    assert geo.camera_from_meta({"width": 100}) is None


def test_hull_area_and_ground_plane():
    rng = np.random.default_rng(1)
    xy = rng.random((50000, 2)) * [20.0, 10.0]
    assert abs(geo.convex_hull_area(xy) - 200.0) < 1.0
    pts = np.column_stack([xy, rng.normal(0, 0.02, 50000)])
    tall = np.column_stack([rng.random((5000, 2)) * [5, 5] + 30, rng.random(5000) * 8 + 2])  # a tower off to the side
    m = drone.measure_cloud(np.vstack([pts, tall]))
    assert abs(m["ground_area_m2"] - 200.0) < 2.0 and m["extent_m"]["z"] > 9.9


def test_alignment_rmse_and_centres(tmp_path):
    (tmp_path / "images.txt").write_text("# header\n1 1 0 0 0 0 0 -5 1 a.jpg\n\n2 1 0 0 0 -1 0 -5 1 b.jpg\n\n")
    c = drone.read_centres(tmp_path)
    assert np.allclose(c["a.jpg"], [0, 0, 5]) and np.allclose(c["b.jpg"], [1, 0, 5])
    rmse, rh, n = drone.alignment_rmse(c, {"a.jpg": np.array([0, 0, 5.3]), "b.jpg": np.array([1, 0.4, 5.0])})
    assert n == 2 and abs(rmse - np.sqrt((0.09 + 0.16) / 2)) < 1e-9 and abs(rh - np.sqrt(0.08)) < 1e-9


# ---------------------------------------------------------------- EXIF / XMP
def test_exif_and_xmp_parsing(tmp_path):
    p = make_jpeg(tmp_path / "a.jpg", lat=-33.8568, lon=-70.5, alt=420.5, plane_res=4032 / 9.68)
    r = dronemeta.read_image_meta(p)
    assert (r["make"], r["model"]) == ("DJI", "FC3582") and (r["width"], r["height"]) == (64, 48)
    assert abs(r["lat"] + 33.8568) < 1e-4 and abs(r["lon"] + 70.5) < 1e-6      # S and W refs give negatives
    assert abs(r["alt_m"] - 420.5) < 1e-3 and abs(r["focal_length_mm"] - 6.72) < 1e-3
    assert abs(r["focal_plane_x_resolution"] - 4032 / 9.68) < 0.01 and r["focal_plane_resolution_unit"] == 4
    assert r["datetime"] == "2026-10-03T10:00:00"
    assert r["gimbal_pitch_deg"] == -89.9 and r["gimbal_yaw_deg"] == 12.3 and r["flight_yaw_deg"] == 10.1
    assert r["relative_altitude_m"] == 30.05 and r["absolute_altitude_m"] == 123.45
    assert dronemeta.has_gps(r)


def test_xmp_element_form_and_hostile_packets():
    el = (b'<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:drone-dji="http://www.dji.com/drone-dji/1.0/">'
          b'<drone-dji:RelativeAltitude>+12.5</drone-dji:RelativeAltitude></x:xmpmeta>')
    assert dronemeta.parse_xmp(el) == {"relative_altitude_m": 12.5}
    assert dronemeta.parse_xmp(b'<!DOCTYPE x [<!ENTITY a "b">]><x:xmpmeta xmlns:x="y"/>') == {}
    assert dronemeta.parse_xmp(b"<broken") == {} and dronemeta.parse_xmp(b"") == {}


def test_image_without_exif_and_table(tmp_path):
    Image.new("RGB", (10, 10)).save(tmp_path / "plain.jpg")
    r = dronemeta.read_image_meta(tmp_path / "plain.jpg")
    assert r["lat"] is None and r["width"] == 10 and not dronemeta.has_gps(r)
    d = make_folder(tmp_path)
    rows = dronemeta.build_table(drone.folder_images(d))
    dest = tmp_path / "images_meta.json"
    dronemeta.write_table(rows, dest)
    doc = json.loads(dest.read_text())
    assert doc["schema"] == 1 and len(doc["images"]) == 4 and doc["images"][0]["file"].startswith("DJI_")
    assert dronemeta.time_ordered(rows)
    assert not dronemeta.time_ordered(list(reversed(rows)))
    refs, org = drone.enu_references(rows)
    assert org[0] == 47.0 and abs(refs[rows[3]["file"]][1] - 33.3) < 0.1


def test_select_images_dng_rules(tmp_path):
    f = [Path("DJI_1.JPG"), Path("DJI_1.DNG"), Path("DJI_2.jpg")]
    jpg, notes = drone.select_images(f)
    assert [p.name for p in jpg] == ["DJI_1.JPG", "DJI_2.jpg"] and "1 DNG" in notes[0]
    with pytest.raises(drone.JobError, match="DNG without"):
        drone.select_images([Path("DJI_9.DNG"), Path("DJI_1.JPG")])


# ---------------------------------------------------------------- API
@pytest.fixture()
def app_noworker(tmp_path):
    return create_app(tmp_path / "data", token=TOKEN, start_worker=False)


def jpgs_zip(tmp_path, n=3, manifest=None, prefix="images/"):
    files = {}
    for i in range(n):
        p = make_jpeg(tmp_path / f"i{i}.jpg", lat=47 + i * 1e-4, seed=i)
        files[f"{prefix}{p.name}"] = p.read_bytes()
    if manifest is not None:
        files["manifest.json"] = manifest
    return make_zip(files)


def post(c, z, type_="drone_photos"):
    return c.post("/v1/jobs", headers=AUTH, data={"type": type_}, files={"file": ("a.zip", z)})


def test_job_validation(app_noworker, tmp_path):
    with TestClient(app_noworker) as c:
        man = {"schema": 1, "job_type": "drone_photos", "quality": "FINE", "gcp": [{"x": 1}]}
        assert post(c, jpgs_zip(tmp_path, 3, man)).status_code == 202
        assert post(c, jpgs_zip(tmp_path, 3), "DRONE_PHOTOS").status_code == 202           # no manifest, upper case
        assert post(c, jpgs_zip(tmp_path, 3, {**man, "quality": "fine"})).status_code == 202
        assert post(c, jpgs_zip(tmp_path, 3, prefix="")).status_code == 202                # images at the ZIP root
        for bad in ({**man, "quality": "ULTRA"}, {**man, "schema": 2}, {**man, "job_type": "object_mesh"},
                    {**man, "gcp": "x"}):
            r = post(c, jpgs_zip(tmp_path, 3, bad))
            assert r.status_code == 422, bad
        r = post(c, jpgs_zip(tmp_path, 2, man))                                             # too few images
        assert r.status_code == 422 and "at least 3" in r.json()["error"]["message"]
        r = post(c, make_zip({"manifest.json": man, "a.txt": "x"}))
        assert r.status_code == 422
        assert len(list(c.app.state.jobs.root.iterdir())) == 4                              # rejects leave nothing


def _local(app, host, body, headers=AUTH):
    with TestClient(app, client=(host, 50000)) as c:
        return c.post("/v1/jobs/local", headers=headers, json=body)


def test_local_endpoint_localhost_only(app_noworker, tmp_path):
    d = make_folder(tmp_path)
    body = {"path": str(d), "type": "drone_photos", "quality": "QUICK", "name": "roof"}
    r = _local(app_noworker, "192.168.1.20", body)
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden"
    assert _local(app_noworker, "testclient", body).status_code == 403
    with TestClient(app_noworker, client=("127.0.0.1", 50000)) as c:                        # tunnel: loopback + proxy header
        r = c.post("/v1/jobs/local", headers={**AUTH, "CF-Connecting-IP": "1.2.3.4"}, json=body)
        assert r.status_code == 403
    assert _local(app_noworker, "127.0.0.1", body, headers={}).status_code == 401           # token still required
    r = _local(app_noworker, "127.0.0.1", body)
    assert r.status_code == 202
    jm = app_noworker.state.jobs
    meta = jm.get(r.json()["id"])
    assert meta["type"] == "DRONE_PHOTOS" and meta["state"] == "QUEUED" and meta["name"] == "roof"
    src = json.loads((jm.dir(meta["id"]) / "source.json").read_text())
    assert Path(src["path"]) == d.resolve() and src["quality"] == "QUICK"
    assert not (jm.dir(meta["id"]) / "upload.zip").exists() and len(list(d.iterdir())) == 4  # photos untouched
    for bad, code in (({**body, "path": str(tmp_path / "nope")}, 422), ({**body, "type": "scan_analyze"}, 400),
                      ({**body, "quality": "X"}, 422), ({"type": "drone_photos"}, 400)):
        assert _local(app_noworker, "127.0.0.1", bad).status_code == code, bad


def test_tools_missing_fails_cleanly(client, tmp_path, monkeypatch):
    monkeypatch.setattr(photogrammetry, "find_tool", lambda name: None)
    r = post(client, jpgs_zip(tmp_path, 3))
    assert r.status_code == 202
    st = wait_done(client, r.json()["id"])
    assert st["state"] == "failed"
    assert "Photogrammetry tools missing" in st["error"] and "INSTALL_PHOTOGRAMMETRY.md" in st["error"]


def test_local_job_tools_missing_and_cli(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(photogrammetry, "find_tool", lambda name: None)
    d = make_folder(tmp_path)
    data = tmp_path / "cli-data"
    assert cli.main(["import-drone", str(d), "--data-dir", str(data), "--name", "x"]) == 1
    assert "Photogrammetry tools missing" in capsys.readouterr().err
    assert cli.main(["import-drone", str(d), "--data-dir", str(data), "--quality", "ULTRA"]) == 2
    empty = tmp_path / "empty"
    empty.mkdir()
    assert cli.main(["import-drone", str(empty), "--data-dir", str(data)]) == 2


# ---------------------------------------------------------------- ortho
def test_ortho_rasteriser_known_scale():
    gx, gy = np.meshgrid(np.arange(0, 20.0001, 0.05), np.arange(0, 12.0001, 0.05))   # 20 m x 12 m plane
    xyz = np.column_stack([gx.ravel(), gy.ravel(), np.zeros(gx.size)])
    rgb = np.zeros((len(xyz), 3), np.uint8)
    rgb[:, 0] = (gx.ravel() / 20.0 * 200 + 20).astype(np.uint8)                     # red encodes east
    rgb[:, 1] = (gy.ravel() / 12.0 * 200 + 20).astype(np.uint8)                     # green encodes north
    img, info = ortho.render_ortho(xyz, rgb, 0.05)
    assert abs(info["pixel_size_m"] - 0.05) < 1e-12
    assert info["width_px"] == 401 and info["height_px"] == 241 and info["filled_fraction"] == 1.0
    assert img[120, 0, 0] < img[120, 400, 0]                                          # east is right
    assert img[0, 200, 1] > img[240, 200, 1]                                          # north is up
    assert abs(int(img[120, 200, 0]) - (0.5 * 200 + 20)) <= 2                         # 10 m east = column 200
    out, bar = ortho.draw_scale_bar(img, info["pixel_size_m"])
    assert bar["length_m"] == 5.0 and bar["length_px"] == 100                         # 5 m = 100 px at 5 cm/px
    y = bar["y_px"]
    x0, x1 = bar["x_px"]
    assert (out[y, x0 + 1:x1] == 0).all() and out.shape == img.shape
    assert tuple(out[y, x1 + 3]) != (0, 0, 0)                                          # the bar stops where it says
    assert ortho.pick_bar_length(400 * 0.05) == 5.0


def test_ortho_resolution_cap_and_highest_point_wins():
    xyz = np.array([[0, 0, 0], [0, 0, 5], [10000.0, 5000.0, 0]])
    rgb = np.array([[255, 0, 0], [0, 0, 255], [0, 255, 0]], np.uint8)
    img, info = ortho.render_ortho(xyz, rgb, 0.01, max_px=4096, fill_iters=0)
    assert max(img.shape[:2]) <= 4096 and info["pixel_size_m"] > 2.4
    assert tuple(img[-1, 0]) == (0, 0, 255)                                            # the higher point covers the lower
