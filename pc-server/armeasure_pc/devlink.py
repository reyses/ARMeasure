"""Dev link (docs/DEV_LINK.md): newest APK per package, APK download, log upload."""
from __future__ import annotations

import fnmatch
import hashlib
import os
import re
import secrets
import shutil
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from . import config

COMMIT_RE = re.compile(r"[0-9a-f]{7,40}")
SAFE = re.compile(r"[^A-Za-z0-9._-]+")
KINDS = ("logs", "crash", "diagnostics")
SETTLE_S = 2.0


def commit_of(name: str) -> str:
    tok = name[:-4].rsplit("-", 1)[-1] if name.lower().endswith(".apk") else ""
    return tok if COMMIT_RE.fullmatch(tok) else ""


def _stable(p: Path, now: float) -> bool:
    try:
        st = p.stat()
    except OSError:
        return False
    return p.is_file() and st.st_size > 0 and now - st.st_mtime >= SETTLE_S and not p.name.endswith(".part")


def listing(apk_dir: Path, pattern: str, now: float | None = None) -> list[Path]:
    """Settled APKs matching pattern, newest first (mtime, ties by name)."""
    now = time.time() if now is None else now
    try:
        names = os.listdir(apk_dir)
    except OSError:
        return []
    out = [apk_dir / n for n in names if fnmatch.fnmatchcase(n, pattern) and _stable(apk_dir / n, now)]
    return sorted(out, key=lambda p: (p.stat().st_mtime, p.name), reverse=True)


def newest(apk_dir: Path, package: str, now: float | None = None) -> Path | None:
    pat = config.DEV_PACKAGES.get(package)
    lst = listing(apk_dir, pat, now) if pat else []
    return lst[0] if lst else None


def downloadable(apk_dir: Path, name: str, now: float | None = None) -> Path | None:
    """The file for a plain name that is in a package listing; None for everything else."""
    if not name or name != Path(name).name or "/" in name or "\\" in name or ".." in name:
        return None
    for pat in config.DEV_PACKAGES.values():
        for p in listing(apk_dir, pat, now):
            if p.name == name:
                return p
    return None


_hash_cache: dict[tuple, str] = {}
_ver_cache: dict[tuple, tuple[int, str]] = {}


def sha256_of(p: Path) -> str:
    st = p.stat()
    key = (str(p), st.st_mtime_ns, st.st_size)
    if key not in _hash_cache:
        h = hashlib.sha256()
        with open(p, "rb") as f:
            while chunk := f.read(1 << 20):
                h.update(chunk)
        _hash_cache[key] = h.hexdigest()
    return _hash_cache[key]


def _aapt2() -> str | None:
    exe = shutil.which("aapt2")
    if exe:
        return exe
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk")
    found = sorted(sdk.glob("build-tools/*/aapt2.exe")) + sorted(sdk.glob("build-tools/*/aapt2"))
    return str(found[-1]) if found else None


def version_of(p: Path) -> tuple[int, str]:
    """(versionCode, versionName) via aapt2 when available, else (1, "") as DEV_LINK.md allows."""
    st = p.stat()
    key = (str(p), st.st_mtime_ns, st.st_size)
    if key in _ver_cache:
        return _ver_cache[key]
    res = (1, "")
    exe = _aapt2()
    if exe:
        try:
            r = subprocess.run([exe, "dump", "badging", str(p)], capture_output=True, text=True, timeout=30,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            m = re.search(r"versionCode='(\d+)'", r.stdout)
            n = re.search(r"versionName='([^']*)'", r.stdout)
            if r.returncode == 0 and m:
                res = (int(m.group(1)), n.group(1) if n else "")
        except (OSError, subprocess.TimeoutExpired):
            pass
    _ver_cache[key] = res
    return res


def describe(p: Path) -> dict:
    code, name = version_of(p)
    return {"versionCode": code, "versionName": name, "commit": commit_of(p.name), "size": p.stat().st_size,
            "sha256": sha256_of(p), "url": f"/v1/dev/apk/{p.name}"}


def store_log(data_dir: Path, kind: str, device: str, content: bytes, keep: int = config.DEV_LOG_KEEP) -> str:
    """Write data/devlogs/<YYYY-MM-DD>/<id>-<kind>-<device>.txt and return the id."""
    now = datetime.now(timezone.utc)
    lid = f"{kind[0].upper()}-{now:%Y%m%d-%H%M%S}-{secrets.token_hex(2)}"
    dev = SAFE.sub("_", device).strip("._-")[:40] or "unknown"
    d = data_dir / "devlogs" / f"{now:%Y-%m-%d}"
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{lid}-{kind}-{dev}.txt").write_bytes(content)
    files = sorted((data_dir / "devlogs").glob("*/*.txt"), key=lambda f: (f.stat().st_mtime, f.name))
    for old in files[:-keep] if len(files) > keep else []:
        try:
            old.unlink()
        except OSError:
            pass
    return lid
