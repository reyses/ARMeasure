import io
import json
import sys
import time
import zipfile
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from armeasure_pc.app import create_app  # noqa: E402
from armeasure_pc.processing.common import write_ply_points  # noqa: E402

TOKEN = "t" * 40
AUTH = {"Authorization": f"Bearer {TOKEN}"}


@pytest.fixture()
def client(tmp_path):
    app = create_app(tmp_path / "data", token=TOKEN, max_upload=5 * 1024 * 1024)
    with TestClient(app) as c:
        yield c


def make_zip(files: dict) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for k, v in files.items():
            z.writestr(k, v if isinstance(v, (bytes, str)) else json.dumps(v))
    return buf.getvalue()


def ply_bytes(xyz, tmp_path) -> bytes:
    p = tmp_path / "c.ply"
    write_ply_points(p, xyz)
    return p.read_bytes()


def box_surface(rng, size, n_per_m2, faces="all", noise=0.0, origin=(0, 0, 0)):
    """Random points on the faces of an axis-aligned box [origin, origin + size] (x, y, z).

    faces="no_bottom" omits the y = origin face (an object resting on a table)."""
    dims = size
    pts = []

    def face(axis, val):
        o = [i for i in range(3) if i != axis]
        n = int(n_per_m2 * dims[o[0]] * dims[o[1]])
        p = np.zeros((n, 3))
        p[:, axis] = val
        p[:, o[0]] = rng.random(n) * dims[o[0]]
        p[:, o[1]] = rng.random(n) * dims[o[1]]
        return p

    for axis in range(3):
        if not (faces == "no_bottom" and axis == 1):
            pts.append(face(axis, 0.0))
        pts.append(face(axis, dims[axis]))
    P = np.vstack(pts) + np.asarray(origin)
    return P + rng.normal(0, noise, P.shape) if noise else P


def wait_done(client, job_id, timeout=120):
    t0 = time.time()
    st = None
    while time.time() - t0 < timeout:
        st = client.get(f"/v1/jobs/{job_id}", headers=AUTH).json()
        if st["state"] in ("done", "failed", "cancelled"):
            return st
        time.sleep(0.2)
    raise TimeoutError(st)


def result_zip(client, job_id):
    r = client.get(f"/v1/jobs/{job_id}/result", headers=AUTH)
    assert r.status_code == 200
    return zipfile.ZipFile(io.BytesIO(r.content))
