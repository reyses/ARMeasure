"""End-to-end runs of the PHOTOGRAMMETRY and DRONE_PHOTOS jobs on synthetic scenes with known ground truth.

Real COLMAP + OpenMVS, through the real HTTP/job-queue code path (FastAPI TestClient -> JobManager worker).
Skipped unless  ARMEASURE_E2E=1  and every tool exists.  Slow: about 9 min per phone job, 7 min for the drone job on a
Ryzen 5 5600X (OpenMVS depth-maps run on the CPU because its CUDA kernels fail here, see README).

    $env:ARMEASURE_E2E = "1"; C:\\venvs\\armeasure-pc\\Scripts\\python.exe -m pytest -q -m gpu_e2e -s

The scenes are rendered once into pc-server/_e2e_data (override: ARMEASURE_E2E_DATA); delete that folder to re-render.
Every test writes its numbers to <data>/report_<name>.json.

Tolerances come from the measured runs of 2026-10-03 (README "Measured accuracy"):
  cube sides: measured +1.4 .. +1.8 mm (201.4-201.8 mm) -> +-3 mm;  volume +1.5 % -> +-3 %
  mesh->truth: measured rms 0.29 mm, p99 1.15 mm, max 2.0 mm -> rms < 0.75 mm, p99 < 3 mm
  drone: alignment rmse 3 mm with exact GPS; house extent within 1 %, height within 2 %, ground plane tilt < 0.1 deg
"""
import json
import os
import sys
import time
import zipfile
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "synth"))

from armeasure_pc.app import create_app  # noqa: E402
from armeasure_pc.processing import photogrammetry  # noqa: E402

TOKEN = "e" * 40
AUTH = {"Authorization": f"Bearer {TOKEN}"}
DATA = Path(os.environ.get("ARMEASURE_E2E_DATA", HERE.parent / "_e2e_data"))


def _tools_ok() -> bool:
    return all(v is not None for v in photogrammetry.detect_tools().values())


e2e = pytest.mark.skipif(os.environ.get("ARMEASURE_E2E") != "1" or not _tools_ok(),
                         reason="needs ARMEASURE_E2E=1 and COLMAP + OpenMVS under tools/")


def run_job(zip_path: Path, job_type: str, work: Path, timeout_s: float = 3600) -> dict:
    """POST the ZIP, poll until done, download the result ZIP. Returns result.json, the extracted folder and the
    seconds spent in every stage (first-seen times of the progress stage, polled every 0.5 s)."""
    app = create_app(work / "data", token=TOKEN, max_upload=4 * 1024 ** 3)
    t0 = time.time()
    with TestClient(app) as c:
        with open(zip_path, "rb") as f:
            r = c.post("/v1/jobs", headers=AUTH, data={"type": job_type.lower()}, files={"file": ("job.zip", f)})
        assert r.status_code == 202, r.text
        jid = r.json()["id"]
        seen, st = [], None
        while time.time() - t0 < timeout_s:
            st = c.get(f"/v1/jobs/{jid}", headers=AUTH).json()
            if not seen or seen[-1][1] != st["stage"]:
                seen.append((time.time(), st["stage"]))
            if st["state"] in ("done", "failed", "cancelled"):
                break
            time.sleep(0.5)
        wall = time.time() - t0
        assert st and st["state"] == "done", st
        rr = c.get(f"/v1/jobs/{jid}/result", headers=AUTH)
        assert rr.status_code == 200
    out = work / "result"
    out.mkdir(exist_ok=True)
    import io
    zipfile.ZipFile(io.BytesIO(rr.content)).extractall(out)
    stages = {}
    for (ta, name), (tb, _) in zip(seen, seen[1:] + [(time.time(), "")]):
        if name not in ("done", "queued", "starting"):
            stages[name] = stages.get(name, 0.0) + (tb - ta)
    return {"result": json.loads((out / "result.json").read_text()), "dir": out, "stages_s": stages, "wall_s": wall}


@pytest.mark.gpu_e2e
@e2e
@pytest.mark.parametrize("jitter", [False, True], ids=["shared_intrinsics", "per_image_focal_jitter"])
def test_phone_cube_e2e(tmp_path, jitter):
    import accuracy
    import render_scene
    zp = DATA / ("phone_job_jitter.zip" if jitter else "phone_job.zip")
    if not zp.exists():
        render_scene.build_phone_job(DATA, jitter=jitter)
    run = run_job(zp, "PHOTOGRAMMETRY", tmp_path)
    res = run["result"]
    m = res["measures"]
    dims_mm = [round(m["object_dims"][k] * 1000, 2) for k in ("length_m", "width_m", "height_m")]
    vol = m["volume_m3"]["recommended"]
    geo = accuracy.phone_geometry(run["dir"] / "mesh.obj")
    sparse = res["stats"].get("sparse", {})
    report = {"dims_mm": dims_mm, "volume_m3": vol, "volume_error_pct": round((vol / 0.008 - 1) * 100, 3),
              "side_errors_mm": [round(d - 200.0, 2) for d in dims_mm], "geometry": geo, "sparse": sparse,
              "stages_s": {k: round(v, 1) for k, v in run["stages_s"].items()}, "wall_s": round(run["wall_s"], 1),
              "notes": res["stats"]["notes"]}
    (DATA / f"report_phone{'_jitter' if jitter else ''}.json").write_text(json.dumps(report, indent=1))
    print(json.dumps(report, indent=1))
    for d in dims_mm:
        assert abs(d - 200.0) <= 3.0, dims_mm
    assert abs(vol / 0.008 - 1) <= 0.03, vol
    assert sparse.get("registered_images") == 60
    assert sparse.get("mean_reprojection_error_px", 9) < 1.0
    assert geo["mesh_to_truth_cube_only"]["rms_mm"] < 0.75
    assert geo["mesh_to_truth_cube_only"]["p99_mm"] < 3.0
    assert set(res["files"]) == {"mesh.obj", "texture.png"}


def _drone_case(tmp_path, name: str, gps_noise: float):
    import accuracy
    import render_scene
    d = DATA / name
    zp = d / "drone_job.zip"
    if not zp.exists():
        render_scene.build_drone_job(d, gps_noise_m=gps_noise)
    truth = json.loads((d / "drone_truth.json").read_text())
    run = run_job(zp, "DRONE_PHOTOS", tmp_path)
    res = run["result"]
    geo = accuracy.drone_geometry(run["dir"], truth)
    cover = accuracy.covered_area_m2(truth, 3)
    m = res["measures"]
    report = {"measures": m, "geometry": geo, "true_ground_area_m2": cover,
              "ground_area_error_pct": round((m["ground_area_m2"] / cover - 1) * 100, 2),
              "alignment": res["stats"]["alignment"], "stages_s": {k: round(v, 1) for k, v in run["stages_s"].items()},
              "wall_s": round(run["wall_s"], 1), "notes": res["stats"]["notes"]}
    (DATA / f"report_{name}.json").write_text(json.dumps(report, indent=1))
    print(json.dumps(report, indent=1))
    return run, m, geo, cover


@pytest.mark.gpu_e2e
@e2e
def test_drone_house_e2e(tmp_path):
    run, m, geo, cover = _drone_case(tmp_path, "drone", 0.0)
    assert m["images_used"] == 40 and m["images_registered"] == 40
    assert m["alignment_rmse_m"] < 0.05                       # measured 0.003 m with exact GPS
    assert abs(geo["house_extent_xy_m"]["x"] / 10.0 - 1) < 0.01
    assert abs(geo["house_extent_xy_m"]["y"] / 6.0 - 1) < 0.015
    assert abs(geo["house_height_m"] / 4.0 - 1) < 0.02
    assert abs(geo["scale_error_pct"]) < 1.0
    assert geo["ground_plane_tilt_deg"] < 0.1
    assert abs(m["ground_area_m2"] / cover - 1) < 0.03        # measured +0.7 %
    assert abs(m["gsd_cm_per_px_estimate"] - 2.7065) < 0.01
    assert {"cloud_clean.ply", "mesh.obj", "texture.png", "ortho.png", "images_meta.json"} <= set(run["result"]["files"])


@pytest.mark.gpu_e2e
@e2e
def test_drone_house_e2e_noisy_gps(tmp_path):
    """GPS with 0.5 m (horizontal) / 1 m (vertical) noise per image, as a consumer receiver would give."""
    run, m, geo, cover = _drone_case(tmp_path, "drone_noisy", 0.5)
    assert m["images_registered"] == 40
    assert 0.1 < m["alignment_rmse_m"] < 2.0                  # the GPS noise itself, not a model error
    assert abs(geo["scale_error_pct"]) < 2.0                  # 'relative accuracy 1-3 %' claim of the README
    assert geo["ground_plane_tilt_deg"] < 1.0
