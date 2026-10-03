"""ARCore camera->world poses to COLMAP world->camera poses (verified by tests/test_pose_conversion.py).

ARCore camera: +X right, +Y up, -Z forward (OpenGL style). Pose = 4x4 camera->world, column-major in poses.json.
COLMAP camera: +X right, +Y down, +Z forward.  Pose = world->camera, quaternion (w, x, y, z) + translation.

    R_cw = diag(1, -1, -1) @ R_wc^T          t_cw = -R_cw @ t_wc
"""
from __future__ import annotations

import numpy as np

FLIP = np.diag([1.0, -1.0, -1.0])


def pose16_to_matrix(pose16) -> np.ndarray:
    m = np.asarray(pose16, float)
    if m.shape != (16,):
        raise ValueError("pose must be 16 floats (column-major 4x4)")
    return m.reshape(4, 4, order="F")


def rot_to_quat_wxyz(R: np.ndarray) -> np.ndarray:
    t = np.trace(R)
    if t > 0:
        s = 2.0 * np.sqrt(t + 1.0)
        q = [0.25 * s, (R[2, 1] - R[1, 2]) / s, (R[0, 2] - R[2, 0]) / s, (R[1, 0] - R[0, 1]) / s]
    elif R[0, 0] > R[1, 1] and R[0, 0] > R[2, 2]:
        s = 2.0 * np.sqrt(1.0 + R[0, 0] - R[1, 1] - R[2, 2])
        q = [(R[2, 1] - R[1, 2]) / s, 0.25 * s, (R[0, 1] + R[1, 0]) / s, (R[0, 2] + R[2, 0]) / s]
    elif R[1, 1] > R[2, 2]:
        s = 2.0 * np.sqrt(1.0 + R[1, 1] - R[0, 0] - R[2, 2])
        q = [(R[0, 2] - R[2, 0]) / s, (R[0, 1] + R[1, 0]) / s, 0.25 * s, (R[1, 2] + R[2, 1]) / s]
    else:
        s = 2.0 * np.sqrt(1.0 + R[2, 2] - R[0, 0] - R[1, 1])
        q = [(R[1, 0] - R[0, 1]) / s, (R[0, 2] + R[2, 0]) / s, (R[1, 2] + R[2, 1]) / s, 0.25 * s]
    q = np.array(q)
    q /= np.linalg.norm(q)
    return q if q[0] >= 0 else -q


def quat_wxyz_to_rot(q) -> np.ndarray:
    w, x, y, z = np.asarray(q, float) / np.linalg.norm(q)
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def arcore_to_colmap(pose16) -> tuple[np.ndarray, np.ndarray]:
    """Return (quat wxyz, t) of the COLMAP world->camera transform."""
    M = pose16_to_matrix(pose16)
    R_wc, t_wc = M[:3, :3], M[:3, 3]
    R_cw = FLIP @ R_wc.T
    t_cw = -R_cw @ t_wc
    return rot_to_quat_wxyz(R_cw), t_cw


def project_arcore(pose16, intr: dict, Xw) -> np.ndarray:
    """Pixel of world point Xw seen by an ARCore camera (+X right, +Y up, -Z forward; image v points down)."""
    M = pose16_to_matrix(pose16)
    Xc = np.linalg.inv(M) @ np.append(np.asarray(Xw, float), 1.0)
    x, y, z = Xc[:3]
    depth = -z
    return np.array([intr["fx"] * x / depth + intr["cx"], intr["cy"] - intr["fy"] * y / depth])


def project_colmap(q_wxyz, t, intr: dict, Xw) -> np.ndarray:
    Xc = quat_wxyz_to_rot(q_wxyz) @ np.asarray(Xw, float) + np.asarray(t, float)
    return np.array([intr["fx"] * Xc[0] / Xc[2] + intr["cx"], intr["fy"] * Xc[1] / Xc[2] + intr["cy"]])
