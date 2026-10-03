"""photogrammetry.measure_obj on a synthetic textured-mesh stand-in: a 200 mm cube standing on a dense floor."""
import json
import math

import numpy as np
import open3d as o3d
import pytest

from armeasure_pc.processing import photogrammetry as pg
from armeasure_pc.processing.objmesh import to_protocol


def cube_on_floor(tmp_path, floor_y=0.0, noise=0.0002):
    rng = np.random.default_rng(0)
    cube = o3d.geometry.TriangleMesh.create_box(0.2, 0.2, 0.2).subdivide_midpoint(5)
    cube.translate((-0.1, floor_y, -0.1))
    v = np.asarray(cube.vertices) + rng.normal(0, noise, (len(cube.vertices), 3))
    cube.vertices = o3d.utility.Vector3dVector(v)
    n = 160
    xs = np.linspace(-0.3, 0.3, n)
    gx, gz = np.meshgrid(xs, xs)
    gv = np.column_stack([gx.ravel(), np.full(gx.size, floor_y) + rng.normal(0, noise, gx.size), gz.ravel()])
    idx = np.arange(n * n).reshape(n, n)
    tri = np.concatenate([np.stack([idx[:-1, :-1].ravel(), idx[1:, :-1].ravel(), idx[:-1, 1:].ravel()], 1),
                          np.stack([idx[1:, :-1].ravel(), idx[1:, 1:].ravel(), idx[:-1, 1:].ravel()], 1)])
    floor = o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(gv), o3d.utility.Vector3iVector(tri))
    mesh = cube + floor
    p = tmp_path / "mesh.obj"
    o3d.io.write_triangle_mesh(str(p), mesh)
    return p


MAN = {"box": {"center": [0.0, 0.1, 0.0], "size": [0.2, 0.2, 0.2], "yaw_deg": 0.0}}


@pytest.mark.parametrize("plane", [
    {"normal": [0, 1, 0], "d": 0.0},
    {"normal": [0, 1, 0], "d": 0.008},                       # phone plane 8 mm inside the object
    {"normal": [math.sin(math.radians(2)), math.cos(math.radians(2)), 0], "d": -0.01},   # tilted and 10 mm low
    None,                                                    # no plane in the manifest
])
def test_cube_on_floor_measures_200mm(tmp_path, plane):
    man = dict(MAN)
    if plane:
        man["support_plane"] = plane
    m = to_protocol(pg.measure_obj(cube_on_floor(tmp_path), man))
    d = m["object_dims"]
    for k in ("length_m", "width_m", "height_m"):
        assert abs(d[k] - 0.2) < 0.002, (k, d)
    assert abs(m["volume_m3"]["recommended"] / 0.008 - 1) < 0.02


def test_sparse_stats_parser():
    txt = "Registered images: 40\nPoints: 1234\nMean reprojection error: 0.399614px\n"
    assert pg.sparse_stats(txt) == {"registered_images": 40, "points": 1234, "mean_reprojection_error_px": 0.399614}
