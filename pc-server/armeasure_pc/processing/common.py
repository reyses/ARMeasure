"""Shared helpers: cancellation context, ZIP safety, PLY reading, manifest."""
from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

import numpy as np

PLY_TYPES = {"char": "i1", "uchar": "u1", "int8": "i1", "uint8": "u1", "short": "i2", "ushort": "u2",
             "int16": "i2", "uint16": "u2", "int": "i4", "uint": "u4", "int32": "i4", "uint32": "u4",
             "float": "f4", "float32": "f4", "double": "f8", "float64": "f8"}


class Cancelled(Exception):
    pass


class JobError(Exception):
    """A failure with a message that is safe to show to the phone user."""


class Ctx:
    """Handed to processors: progress reporting + cooperative cancellation."""

    def __init__(self, workdir: Path, update=None, is_cancelled=None, register_proc=None):
        self.workdir = Path(workdir).resolve()     # absolute: external tools run with another cwd
        self._update = update or (lambda *a, **k: None)
        self._is_cancelled = is_cancelled or (lambda: False)
        self.register_proc = register_proc or (lambda p: None)

    def progress(self, p: float, stage: str, message: str | None = None):
        self.check()
        self._update(p, stage, message)

    def check(self):
        if self._is_cancelled():
            raise Cancelled()


def safe_extract(zip_path: Path, dest: Path, max_total: int = 16 * 1024 ** 3) -> None:
    dest = dest.resolve()
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        total = sum(i.file_size for i in z.infolist())
        if total > max_total:
            raise JobError("ZIP expands to more than the allowed size")
        for info in z.infolist():
            target = (dest / info.filename).resolve()
            if dest != target and dest not in target.parents:
                raise JobError(f"unsafe path in ZIP: {info.filename}")
        z.extractall(dest)


def read_manifest(root: Path, required: bool = True) -> dict:
    p = root / "manifest.json"
    if not p.exists():
        if required:
            raise JobError("manifest.json missing from upload ZIP")
        return {}
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except ValueError as e:
        raise JobError(f"manifest.json is not valid JSON: {e}")


def read_ply_points(path: Path) -> dict[str, np.ndarray]:
    """Read a binary little-endian (or ascii) PLY vertex element into named numpy arrays."""
    with open(path, "rb") as f:
        if f.readline().strip() != b"ply":
            raise JobError("cloud.ply is not a PLY file")
        fmt, n, props, cur = None, 0, [], None
        while True:
            line = f.readline()
            if not line:
                raise JobError("PLY header truncated")
            t = line.decode("ascii", "replace").split()
            if not t:
                continue
            if t[0] == "format":
                fmt = t[1]
            elif t[0] == "element":
                cur = t[1]
                if cur == "vertex":
                    n = int(t[2])
            elif t[0] == "property" and cur == "vertex":
                if t[1] == "list":
                    raise JobError("list property in vertex element unsupported")
                props.append((t[2], PLY_TYPES[t[1]]))
            elif t[0] == "end_header":
                break
        if fmt == "binary_little_endian":
            dt = np.dtype([(n_, "<" + c) for n_, c in props])
            arr = np.fromfile(f, dtype=dt, count=n)
        elif fmt == "ascii":
            raw = np.loadtxt(f, max_rows=n, ndmin=2)
            dt = np.dtype([(n_, "<" + c) for n_, c in props])
            arr = np.zeros(len(raw), dtype=dt)
            for i, (n_, _) in enumerate(props):
                arr[n_] = raw[:, i]
        else:
            raise JobError(f"unsupported PLY format {fmt}")
    if len(arr) < n:
        raise JobError("PLY body truncated")
    return {name: arr[name] for name, _ in props}


def write_ply_points(path: Path, xyz: np.ndarray, hits=None, conf=None) -> None:
    n = len(xyz)
    hits = np.ones(n, "<u2") if hits is None else np.asarray(hits, "<u2")
    conf = np.full(n, 255, "u1") if conf is None else np.asarray(conf, "u1")
    dt = np.dtype([("x", "<f4"), ("y", "<f4"), ("z", "<f4"), ("hits", "<u2"), ("confidence", "u1")])
    a = np.empty(n, dt)
    a["x"], a["y"], a["z"] = xyz[:, 0], xyz[:, 1], xyz[:, 2]
    a["hits"], a["confidence"] = hits, conf
    hdr = ("ply\nformat binary_little_endian 1.0\nelement vertex %d\nproperty float x\nproperty float y\n"
           "property float z\nproperty ushort hits\nproperty uchar confidence\nend_header\n" % n)
    with open(path, "wb") as f:
        f.write(hdr.encode("ascii"))
        a.tofile(f)


def load_cloud(root: Path, min_conf: int = 0) -> np.ndarray:
    p = root / "cloud.ply"
    if not p.exists():
        raise JobError("cloud.ply missing from upload ZIP")
    d = read_ply_points(p)
    xyz = np.column_stack([d["x"], d["y"], d["z"]]).astype(np.float64)
    if "confidence" in d and min_conf > 0:
        xyz = xyz[d["confidence"] >= min_conf]
    xyz = xyz[np.isfinite(xyz).all(axis=1)]
    if len(xyz) < 100:
        raise JobError(f"cloud.ply has only {len(xyz)} usable points")
    return xyz


def versions() -> dict:
    import open3d
    from .. import __version__
    return {"server": __version__, "python": sys.version.split()[0], "open3d": open3d.__version__,
            "numpy": np.__version__}
