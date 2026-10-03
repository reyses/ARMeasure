import json

import numpy as np

from conftest import AUTH, box_surface, make_zip, ply_bytes, result_zip, wait_done

MAN = {"schema": 1, "job_type": "scan_analyze", "units": "meters"}


def err_code(r):
    return r.json()["error"]["code"]


def post(client, z, type_="scan_analyze", headers=AUTH):
    return client.post("/v1/jobs", headers=headers, data={"type": type_}, files={"file": ("a.zip", z)})


def test_auth(client):
    r = client.get("/v1/ping")
    assert r.status_code == 401 and err_code(r) == "unauthorized"
    assert client.get("/v1/ping", headers={"Authorization": "Bearer nope"}).status_code == 401
    assert client.get("/v1/ping", headers={"Authorization": "Basic " + "t" * 40}).status_code == 401
    r = client.get("/v1/ping", headers=AUTH)
    assert r.status_code == 200
    body = r.json()
    assert {"name", "version", "gpu", "api_version", "max_upload_bytes"} <= set(body)
    assert body["api_version"] == 1 and body["max_upload_bytes"] == 5 * 1024 * 1024
    assert isinstance(body["cuda"], bool) and isinstance(body["jobs_running"], int)
    assert client.get("/v1/jobs/abc").status_code == 401
    assert post(client, b"x", headers={}).status_code == 401


def test_bad_requests_and_error_envelope(client):
    r = client.get("/v1/jobs/deadbeef", headers=AUTH)
    assert r.status_code == 404 and err_code(r) == "not_found"
    z = make_zip({"manifest.json": MAN, "cloud.ply": b"x"})
    assert post(client, z, "bogus").status_code == 400
    r = client.post("/v1/jobs", headers=AUTH, files={"file": ("a.zip", z)})          # no type
    assert r.status_code == 400 and err_code(r) == "bad_request"
    r = client.post("/v1/jobs", headers=AUTH, data={"type": "scan_analyze"})          # no file
    assert r.status_code == 400
    r = post(client, b"not a zip")
    assert r.status_code == 415 and err_code(r) == "unsupported_media"
    r = post(client, make_zip({"cloud.ply": b"x"}))                                    # no manifest
    assert r.status_code == 422 and err_code(r) == "invalid_package"
    r = post(client, make_zip({"manifest.json": {**MAN, "schema": 2}, "cloud.ply": b"x"}))
    assert r.status_code == 422
    r = post(client, make_zip({"manifest.json": {**MAN, "job_type": "object_mesh"}, "cloud.ply": b"x"}))
    assert r.status_code == 422
    r = post(client, make_zip({"manifest.json": MAN}))                                 # no cloud
    assert r.status_code == 422
    assert list(client.app.state.jobs.root.iterdir()) == []                            # nothing left behind


def test_unknown_quality_and_uppercase_type_accepted(client):
    jm = client.app.state.jobs
    jm.stop()
    z = make_zip({"manifest.json": {**MAN, "quality": "FUTURE_MODE"}, "cloud.ply": b"x"})
    assert post(client, z, "SCAN_ANALYZE").status_code == 202


def test_upload_size_limit(client):
    big = b"\0" * (6 * 1024 * 1024)   # the fixture limit is 5 MiB
    r = post(client, big)
    assert r.status_code == 413 and err_code(r) == "too_large"
    assert list(client.app.state.jobs.root.iterdir()) == []


def test_result_409_before_done_and_delete(client):
    jm = client.app.state.jobs
    jm.stop()                       # park the worker so the job stays queued
    r = post(client, make_zip({"manifest.json": MAN, "cloud.ply": b"x"}))
    assert r.status_code == 202
    jid = r.json()["id"]
    st = client.get(f"/v1/jobs/{jid}", headers=AUTH).json()
    assert st["state"] == "queued" and st["position"] >= 1 and "error" not in st
    r = client.get(f"/v1/jobs/{jid}/result", headers=AUTH)
    assert r.status_code == 409 and err_code(r) == "not_ready"
    assert client.delete(f"/v1/jobs/{jid}", headers=AUTH).status_code == 204
    assert client.get(f"/v1/jobs/{jid}", headers=AUTH).status_code == 404
    assert client.delete(f"/v1/jobs/{jid}", headers=AUTH).status_code == 204     # idempotent


def test_failed_job_reports_error(client):
    r = post(client, make_zip({"manifest.json": MAN, "cloud.ply": b"not a ply"}))
    st = wait_done(client, r.json()["id"])
    assert st["state"] == "failed" and "PLY" in st["error"]


def test_photogrammetry_fails_clearly_without_tools(client, monkeypatch):
    from armeasure_pc.processing import photogrammetry as pg
    monkeypatch.setattr(pg, "find_tool", lambda name: None)
    man = {"schema": 1, "job_type": "photogrammetry"}
    z = make_zip({"manifest.json": man, "poses.json": {"schema": 1, "images": []}, "images/000001.jpg": b"x"})
    r = post(client, z, "photogrammetry")
    st = wait_done(client, r.json()["id"])
    assert st["state"] == "failed"
    assert "colmap.exe" in st["error"] and "INSTALL_PHOTOGRAMMETRY.md" in st["error"]


def test_scan_analyze_room_end_to_end(client, tmp_path):
    rng = np.random.default_rng(1)
    # 4 m (x) by 5 m (z) by 2.5 m (y, up) room with 5 mm noise plus stray outliers
    room = box_surface(rng, (4.0, 2.5, 5.0), 400, noise=0.005, origin=(1.0, 0.0, -2.0))
    outliers = rng.uniform([-1, -1, -5], [7, 4, 5], (60, 3))
    xyz = np.vstack([room, outliers])
    z = make_zip({"manifest.json": MAN, "cloud.ply": ply_bytes(xyz, tmp_path)})
    r = post(client, z)
    assert r.status_code == 202
    jid = r.json()["id"]
    st = wait_done(client, jid)
    assert st["state"] == "done", st
    assert st["progress"] == 1.0
    zf = result_zip(client, jid)
    assert set(zf.namelist()) == {"result.json", "planes.json", "cloud_clean.ply"}
    res = json.loads(zf.read("result.json"))
    assert res["schema"] == 1 and res["job_type"] == "scan_analyze"
    assert set(res["files"]) == {"planes.json", "cloud_clean.ply"}
    m = res["measures"]
    assert abs(m["area_m2"]["recommended"] - 20.0) < 0.3, m
    assert abs(m["height_m"]["recommended"] - 2.5) < 0.03, m
    assert abs(m["volume_m3"]["recommended"] - 50.0) < 1.5, m
    assert abs(m["perimeter_m"]["recommended"] - 18.0) < 0.3, m
    for k in ("area_m2", "perimeter_m", "height_m", "volume_m3"):
        assert m[k]["low"] <= m[k]["recommended"] <= m[k]["high"]
    assert m["wall_count"] == 4
    assert res["stats"]["backend"] == "pc" and "open3d" in res["stats"]["versions"]
    planes = json.loads(zf.read("planes.json"))
    assert planes["schema"] == 1
    kinds = [p["kind"] for p in planes["planes"]]
    assert kinds.count("WALL") == 4 and "FLOOR" in kinds and "CEILING" in kinds
    for p in planes["planes"]:
        if p["kind"] in ("WALL", "FLOOR", "CEILING"):
            assert len(p["outline_3d"]) >= 3


def _object_job(client, tmp_path, size, voxel_mm, noise, quality):
    rng = np.random.default_rng(7)
    sx, sy, sz = size
    org = (0.3, 0.0, -0.2)
    obj = box_surface(rng, size, 40000, faces="no_bottom", noise=noise, origin=org)
    n = 60000    # table top (support plane y = 0) around the object
    table = np.column_stack([rng.uniform(-0.3, 0.9, n), rng.normal(0, noise, n), rng.uniform(-0.8, 0.4, n)])
    stray = rng.uniform([-0.3, 0.02, -0.8], [0.9, 0.6, 0.4], (40, 3))
    xyz = np.vstack([obj, table, stray])
    center = [org[0] + sx / 2, sy / 2, org[2] + sz / 2]
    manifest = {"schema": 1, "job_type": "object_mesh", "units": "meters", "quality": quality,
                "voxel_mm": voxel_mm,
                "box": {"center": center, "yaw_deg": 0.0, "size": [sx, sy, sz]},
                "support_plane": {"normal": [0, 1, 0], "d": 0.0}}
    z = make_zip({"manifest.json": manifest, "cloud.ply": ply_bytes(xyz, tmp_path)})
    r = post(client, z, "object_mesh")
    jid = r.json()["id"]
    st = wait_done(client, jid, 300)
    assert st["state"] == "done", st
    zf = result_zip(client, jid)
    assert set(zf.namelist()) == {"result.json", "mesh.ply", "mesh.obj", "cloud_clean.ply"}
    r_ = json.loads(zf.read("result.json")); print("RESULT", json.dumps(r_["measures"])); return r_


def test_object_mesh_box_on_plane(client, tmp_path):
    res = _object_job(client, tmp_path, (0.5, 0.4, 0.3), 5, 0.002, "QUICK")
    m = res["measures"]
    d = m["object_dims"]
    # footprint 0.5 x 0.3 (x, z), height 0.4 (y up)
    for got, want in zip((d["length_m"], d["width_m"], d["height_m"]), (0.5, 0.3, 0.4)):
        assert abs(got - want) < 0.015, m
    hull = m["volume_variants_m3"]["convex_hull"]
    assert abs(hull - 0.06) / 0.06 < 0.05, m
    assert res["stats"]["quality"] == "QUICK" and res["stats"]["poisson_depth"] == 9


def test_object_mesh_cube_200mm_fine(client, tmp_path):
    res = _object_job(client, tmp_path, (0.2, 0.2, 0.2), 3, 0.004, "FINE")
    m = res["measures"]
    assert res["stats"]["poisson_depth"] == 10 and res["stats"]["voxel_mm"] == 3
    for k in ("length_m", "width_m", "height_m"):
        assert abs(m["object_dims"][k] - 0.2) < 0.008, m
    assert abs(m["volume_variants_m3"]["convex_hull"] - 0.008) / 0.008 < 0.08, m
