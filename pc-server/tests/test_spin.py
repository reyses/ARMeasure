"""SPIN / HYBRID helpers without COLMAP or OpenMVS: axis fit, scale + frame, turns, static-background masks, Umeyama."""
import math
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from armeasure_pc.processing import spin
from armeasure_pc.processing.posec import FLIP, quat_wxyz_to_rot, rot_to_quat_wxyz


def lookat_c2w_arcore(eye, target, up=(0, 1, 0)):
    eye, target = np.asarray(eye, float), np.asarray(target, float)
    z = eye - target
    z /= np.linalg.norm(z)
    x = np.cross(up, z)
    x /= np.linalg.norm(x)
    y = np.cross(z, x)
    M = np.eye(4)
    M[:3, 0], M[:3, 1], M[:3, 2], M[:3, 3] = x, y, z, eye
    return M


def rand_rot(rng):
    q = rng.normal(size=4)
    return quat_wxyz_to_rot(q / np.linalg.norm(q))


def orbit_model(rng, scale=3.7, elevs=(15, 40), n=36, dist=0.40, step_deg=10.0):
    """Cameras of a static phone seen from a turning object, written in an arbitrary model frame (scale 3.7,
    random rotation and shift). Returns arrays in that frame plus the ARCore poses (static per turn, in a frame of
    their own, gravity = +Y)."""
    Rg = rand_rot(rng)
    tg = rng.normal(size=3) * 2
    C, Rm, lab, grav = [], [], [], []
    for ti, el in enumerate(elevs):
        eye0 = np.array([dist, 0.1 + dist * math.tan(math.radians(el)), 0.0])
        M0 = lookat_c2w_arcore(eye0, (0, 0.1, 0))                 # ARCore pose, camera fixed
        for k in range(n):
            a = math.radians(k * step_deg)
            Ry = np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])
            # object turns by +a  <=>  camera turns by -a about the axis in the object frame
            Mo = np.eye(4)
            Mo[:3, :3] = Ry.T @ M0[:3, :3]
            Mo[:3, 3] = Ry.T @ M0[:3, 3]
            # object frame -> model frame: X_m = scale * Rg X_o + tg
            R_c2w_m = Rg @ Mo[:3, :3]
            c_m = scale * Rg @ Mo[:3, 3] + tg
            R_w2c = FLIP @ R_c2w_m.T                              # COLMAP world->camera
            C.append(c_m)
            Rm.append(R_w2c)
            lab.append(ti)
            grav.append(M0[:3, :3].T @ np.array([0, 1.0, 0]))
    return np.array(C), np.array(Rm), np.array(lab), np.array(grav), (scale, Rg, tg)


def test_axis_fit_recovers_common_axis_and_radii():
    rng = np.random.default_rng(1)
    C, Rm, lab, grav, (scale, Rg, tg) = orbit_model(rng)
    C = C + rng.normal(0, 1e-3, C.shape)
    g_up = np.mean([Rm[i].T @ FLIP @ grav[i] for i in range(len(C))], axis=0)
    g_up /= np.linalg.norm(g_up)
    fit = spin.fit_rotation_axis(C, lab, g_up)
    true_axis = Rg @ np.array([0, 1.0, 0])
    assert np.degrees(np.arccos(abs(fit["u"] @ true_axis))) < 0.1
    assert fit["u"] @ true_axis > 0
    for r in fit["radii"].values():
        assert abs(r / scale - 0.40) < 0.002
    assert fit["coverage_deg"] > 340


def test_axis_fit_short_arc_falls_back_to_gravity_axis():
    rng = np.random.default_rng(2)
    C, Rm, lab, grav, (scale, Rg, tg) = orbit_model(rng, elevs=(25,), n=12, step_deg=10.0)   # 110 degree arc
    g_up = np.mean([Rm[i].T @ FLIP @ grav[i] for i in range(len(C))], axis=0)
    g_up /= np.linalg.norm(g_up)
    fit = spin.fit_rotation_axis(C, lab, g_up, free_axis=False)
    assert abs(fit["radii"][0] / scale - 0.40) < 0.003


def test_spin_frame_scale_axis_and_plane():
    rng = np.random.default_rng(3)
    C, Rm, lab, grav, (scale, Rg, tg) = orbit_model(rng)
    # sparse points: turntable top (y = 0 in the object frame, 0.15 m disc) and a cube top (y = 0.2)
    ang, rad = rng.uniform(0, 2 * np.pi, 3000), 0.15 * np.sqrt(rng.uniform(0, 1, 3000))
    disc = np.column_stack([rad * np.cos(ang), np.zeros(3000), rad * np.sin(ang)])
    top = np.column_stack([rng.uniform(-0.1, 0.1, 1500), np.full(1500, 0.2), rng.uniform(-0.1, 0.1, 1500)])
    pts_o = np.vstack([disc, top])
    pts_m = scale * pts_o @ Rg.T + tg
    man = {"camera_to_axis_m": 0.40, "camera_height_above_plane_m": 0.1 + 0.4 * (math.tan(math.radians(15)) + math.tan(math.radians(40))) / 2}
    fr = spin.spin_frame(C, lab, Rm, grav, pts_m, man)
    assert abs(fr["s"] * scale - 1.0) < 0.003                      # model units -> metres
    X = np.array([fr["s"] * fr["T"] @ (c - fr["O"]) for c in C])
    assert np.allclose(np.hypot(X[:, 0], X[:, 2]), 0.40, atol=0.002)
    assert np.allclose(X[lab == 0, 1], 0.1 + 0.4 * math.tan(math.radians(15)), atol=0.003)
    P = np.array([fr["s"] * fr["T"] @ (p - fr["O"]) for p in pts_m[:3000]])
    assert abs(np.median(P[:, 1])) < 0.002                          # disc top at y = 0
    assert abs(np.linalg.det(fr["T"]) - 1) < 1e-9
    assert fr["info"]["support_plane_source"].startswith("sparse")


def test_spin_frame_without_distance_is_an_error():
    rng = np.random.default_rng(4)
    C, Rm, lab, grav, _ = orbit_model(rng)
    with pytest.raises(spin.JobError):
        spin.spin_frame(C, lab, Rm, grav, np.zeros((0, 3)), {"camera_height_above_plane_m": 0.3})


def pose16(M):
    return [float(x) for x in M.flatten(order="F")]


def test_split_turns_by_phone_pose():
    base = lookat_c2w_arcore((0.4, 0.2, 0), (0, 0.1, 0))
    up = lookat_c2w_arcore((0.4, 0.45, 0), (0, 0.1, 0))
    rng = np.random.default_rng(0)
    poses = []
    for M in (base, up):
        for _ in range(10):
            N = M.copy()
            N[:3, 3] += rng.normal(0, 0.002, 3)
            poses.append({"pose": pose16(N)})
    assert spin.split_turns(poses) == [0] * 10 + [1] * 10


def test_umeyama_and_ransac_with_outliers():
    rng = np.random.default_rng(5)
    src = rng.normal(size=(200, 3)) * 0.1
    R = rand_rot(rng)
    s, t = 0.27, np.array([1.0, -2.0, 0.5])
    dst = s * src @ R.T + t + rng.normal(0, 0.0005, src.shape)
    dst[:60] += rng.normal(0, 0.2, (60, 3))                         # 30 % outliers
    s2, R2, t2, inl = spin.ransac_similarity(src, dst, thr=0.01)
    assert abs(s2 / s - 1) < 0.01 and np.allclose(R2, R, atol=0.01) and np.allclose(t2, t, atol=0.002)
    assert inl[60:].mean() > 0.95 and inl[:60].mean() < 0.2


def test_spin_pose_conversion_matches_point_transform():
    rng = np.random.default_rng(6)
    Rm = rand_rot(rng)
    tm = rng.normal(size=3)
    img = {"images": {1: {"name": "a.jpg", "q": rot_to_quat_wxyz(Rm), "t": tm}}}
    s, R, t = 0.3, rand_rot(rng), rng.normal(size=3)
    out = spin.spin_poses_in_world(img, s, R, t)[0]
    Rn, tn = quat_wxyz_to_rot(out["q"]), out["t"]
    X_m = rng.normal(size=3)
    X_w = s * R @ X_m + t
    # camera coordinates: metric model camera = s * (Rm X_m + tm)
    assert np.allclose(Rn @ X_w + tn, s * (Rm @ X_m + tm), atol=1e-9)


def test_refine_masks_removes_static_background(tmp_path):
    """A textured static background plus a square of changing texture inside the phone mask: only the square stays."""
    rng = np.random.default_rng(7)
    bg = rng.integers(0, 256, (240, 320), dtype=np.uint8)
    entries = []
    for i in range(12):
        im = bg.copy()
        im[80:160, 100:220] = rng.integers(0, 256, (80, 120), dtype=np.uint8)       # the 'object': new texture each frame
        p = tmp_path / f"{i:03d}.jpg"
        Image.fromarray(im).convert("RGB").save(p, quality=98)
        m = np.zeros((240, 320), np.uint8)
        m[40:200, 60:260] = 255                                                       # phone box mask, ring around the object
        Image.fromarray(m).save(tmp_path / f"{i:03d}.jpg.png")
        entries.append({"name": p.name, "path": p, "mask": tmp_path / f"{i:03d}.jpg.png", "turn": 0, "w": 320, "h": 240})
    from armeasure_pc.processing.common import Ctx
    stats = spin.refine_masks(entries, tmp_path / "out", Ctx(tmp_path))
    out = np.asarray(Image.open(tmp_path / "out" / "003.jpg.png")) > 0
    assert out[90:150, 110:210].mean() > 0.97                                        # object kept
    assert out[40:70, 60:90].mean() < 0.05 and out[170:200, 230:260].mean() < 0.05    # static ring dropped
    assert stats["mean_mask_fraction_final"] < stats["mean_mask_fraction_phone"]


def test_object_manifest_box_is_centred_on_the_axis():
    m = spin.object_manifest({"box": {"center": [3, 1, 4], "size": [0.2, 0.1, 0.15], "yaw_deg": 33}})
    b = m["box"]
    assert b["center"] == [0.0, 0.05, 0.0] and b["yaw_deg"] == 0.0
    assert abs(b["size"][0] - math.hypot(0.2, 0.15)) < 1e-12 and b["size"][1] == 0.1
    assert m["support_plane"] == {"normal": [0.0, 1.0, 0.0], "d": 0.0}
