import sqlite3
import struct

import numpy as np

from armeasure_pc.processing.photogrammetry import write_text_model
from armeasure_pc.processing.posec import (arcore_to_colmap, project_arcore, project_colmap,
                                           quat_wxyz_to_rot, rot_to_quat_wxyz)

INTR = {"fx": 1500.0, "fy": 1490.0, "cx": 960.0, "cy": 540.0, "w": 1920, "h": 1080}


def look_at_arcore(eye, target, up=(0, 1, 0)):
    """ARCore camera->world, column-major 16 floats: camera looks along its -Z, +Y up, +X right."""
    eye, target = np.asarray(eye, float), np.asarray(target, float)
    fwd = target - eye
    fwd /= np.linalg.norm(fwd)
    z = -fwd                                  # camera +Z points backwards
    x = np.cross(up, z)
    x /= np.linalg.norm(x)
    y = np.cross(z, x)
    M = np.eye(4)
    M[:3, 0], M[:3, 1], M[:3, 2], M[:3, 3] = x, y, z, eye
    return M.flatten(order="F").tolist()


def test_pixels_agree_for_known_point():
    pose = look_at_arcore(eye=(1.0, 0.8, 2.0), target=(0.2, 0.1, -0.5))
    q, t = arcore_to_colmap(pose)
    for X in ([0.2, 0.1, -0.5], [0.3, 0.25, -0.4], [0.0, 0.0, -0.6], [0.45, 0.05, -0.55]):
        pa = project_arcore(pose, INTR, X)
        pc = project_colmap(q, t, INTR, X)
        assert np.allclose(pa, pc, atol=1e-6), (X, pa, pc)
    # the look-at target lies on the optical axis -> principal point
    assert np.allclose(project_colmap(q, t, INTR, [0.2, 0.1, -0.5]), [960, 540], atol=1e-6)


def test_up_is_up_in_the_image():
    """World +Y must go to a SMALLER row (image v grows downward); world +X (camera right) to a larger column."""
    pose = look_at_arcore((0, 0.5, 2.0), (0, 0.5, 0.0))
    q, t = arcore_to_colmap(pose)
    above = project_colmap(q, t, INTR, [0, 0.7, 0.0])
    right = project_colmap(q, t, INTR, [0.2, 0.5, 0.0])
    assert above[1] < 540 and right[0] > 960
    assert np.allclose(above, project_arcore(pose, INTR, [0, 0.7, 0.0]))


def test_rotation_is_proper_and_quaternion_roundtrips():
    rng = np.random.default_rng(3)
    for _ in range(50):
        eye, tgt = rng.uniform(-2, 2, 3), rng.uniform(-1, 1, 3)
        q, t = arcore_to_colmap(look_at_arcore(eye, tgt))
        R = quat_wxyz_to_rot(q)
        assert np.allclose(R @ R.T, np.eye(3), atol=1e-9) and np.isclose(np.linalg.det(R), 1.0)
        assert np.allclose(rot_to_quat_wxyz(R), q, atol=1e-9)
        assert np.allclose(-R.T @ t, eye, atol=1e-9)    # camera centre recovered


def test_colmap_text_model_matches(tmp_path):
    db = tmp_path / "d.db"
    con = sqlite3.connect(db)
    con.execute("CREATE TABLE cameras (camera_id INTEGER, model INTEGER, width INTEGER, height INTEGER,"
                " params BLOB, prior_focal_length INTEGER)")
    con.execute("CREATE TABLE images (image_id INTEGER, name TEXT, camera_id INTEGER)")
    con.execute("INSERT INTO cameras VALUES (1,1,1920,1080,?,1)", (struct.pack("<4d", 1500, 1490, 960, 540),))
    con.execute("INSERT INTO images VALUES (1,'a.jpg',1)")
    con.commit()
    con.close()
    pose = look_at_arcore((1, 1, 1), (0, 0, 0))
    n = write_text_model(tmp_path / "m", db, [{"file": "images/a.jpg", "pose": pose, "intrinsics": INTR}])
    assert n == 1
    assert "1 PINHOLE 1920 1080 1500.0 1490.0 960.0 540.0" in (tmp_path / "m" / "cameras.txt").read_text()
    lines = [x for x in (tmp_path / "m" / "images.txt").read_text().splitlines() if x and not x.startswith("#")]
    parts = lines[0].split()
    q, t = arcore_to_colmap(pose)
    assert np.allclose(np.array(parts[1:8], float), np.concatenate([q, t]))
    assert parts[8:] == ["1", "a.jpg"]
