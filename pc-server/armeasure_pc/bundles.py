"""Beam bundles (docs/BEAM.md): chunked, resumable upload of a session bundle ZIP from the phone, sha256 verify, safe unpack.

Layout under <data>/devbundles/:
    incoming/<id>/meta.json, <n>.chunk       chunks of an unfinished upload (deleted on success)
    .done/<id>.json                           receipt of a finished upload, so a lost "complete" answer can be retried
    <YYYY-MM-DD>/<session-id>/                the unpacked bundle (manifest.json, timeline.jsonl, snapshots/, ...)
"""
from __future__ import annotations

import hashlib
import json
import re
import secrets
import shutil
import stat
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

from . import config

ID_RE = re.compile(r"[0-9a-f]{16}")
SHA_RE = re.compile(r"[0-9a-f]{64}")
SAFE = re.compile(r"[^A-Za-z0-9._-]+")
MAX_UNPACKED_FACTOR = 2          # uncompressed total may not exceed 2 x the max bundle (zip-bomb guard)
STALE_INCOMING_S = 3 * 24 * 3600


class BundleError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def chunk_count(size: int, chunk_size: int) -> int:
    return (size + chunk_size - 1) // chunk_size if size > 0 else 0


def chunk_length(size: int, chunk_size: int, n: int) -> int:
    return min(chunk_size, size - n * chunk_size)


def safe_member(name: str) -> PurePosixPath | None:
    """The ZIP member as a relative POSIX path, or None when it could escape (absolute, drive, backslash, '..')."""
    if not name or "\\" in name or "\x00" in name or name.startswith("/") or re.match(r"^[A-Za-z]:", name):
        return None
    p = PurePosixPath(name)
    if any(part in ("..", "") for part in p.parts):
        return None
    return p


def safe_extract(zpath: Path, dest: Path, max_total: int) -> list[str]:
    """Unpack zpath into dest, refusing traversal, symlinks and oversize totals. Returns the member names written."""
    written: list[str] = []
    dest = dest.resolve()
    with zipfile.ZipFile(zpath) as z:
        infos = z.infolist()
        if sum(i.file_size for i in infos) > max_total:
            raise BundleError(422, "bundle unpacks to too much data")
        for i in infos:
            rel = safe_member(i.filename.rstrip("/")) if i.filename.rstrip("/") else None
            if rel is None:
                raise BundleError(422, f"unsafe path in bundle: {i.filename!r}")
            mode = i.external_attr >> 16
            if mode and stat.S_ISLNK(mode):
                raise BundleError(422, f"symlink in bundle: {i.filename!r}")
            target = (dest / Path(*rel.parts)).resolve()
            if dest != target and dest not in target.parents:
                raise BundleError(422, f"path escapes the bundle folder: {i.filename!r}")
            if i.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(i) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out, 1 << 20)
            written.append(str(rel))
    return written


class BundleStore:
    def __init__(self, data_dir: Path, max_bytes: int = config.BUNDLE_MAX_BYTES, chunk_size: int = config.BUNDLE_CHUNK_BYTES,
                 keep: int = config.BUNDLE_KEEP):
        self.root = Path(data_dir) / "devbundles"
        self.incoming = self.root / "incoming"
        self.done = self.root / ".done"
        self.max_bytes = max_bytes
        self.chunk_size = chunk_size
        self.keep = keep

    # ---- helpers
    def _dir(self, bid: str) -> Path:
        if not ID_RE.fullmatch(bid or ""):
            raise BundleError(404, "no such bundle")
        return self.incoming / bid

    def _meta(self, bid: str) -> dict:
        d = self._dir(bid)
        try:
            return json.loads((d / "meta.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raise BundleError(404, "no such bundle") from None

    def _received(self, bid: str) -> list[int]:
        d = self._dir(bid)
        out = []
        for f in d.glob("*.chunk"):
            try:
                out.append(int(f.stem))
            except ValueError:
                pass
        return sorted(out)

    def _reply(self, meta: dict, state: str = "uploading") -> dict:
        received = self._received(meta["id"]) if state != "complete" else []
        return {"id": meta["id"], "chunk_size": meta["chunk_size"], "chunks": chunk_count(meta["size"], meta["chunk_size"]),
                "received": received, "state": state}

    # ---- the four calls
    def create(self, name: str, size: int, sha256: str) -> dict:
        sha256 = (sha256 or "").lower()
        if not SHA_RE.fullmatch(sha256):
            raise BundleError(400, "sha256 must be 64 hex characters")
        if size <= 0:
            raise BundleError(400, "size must be positive")
        if size > self.max_bytes:
            raise BundleError(413, f"bundle exceeds {self.max_bytes} bytes")
        self._cleanup_stale()
        for f in self.done.glob("*.json") if self.done.is_dir() else []:
            try:
                r = json.loads(f.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if r.get("sha256") == sha256 and r.get("size") == size:
                return {"id": r["id"], "chunk_size": r["chunk_size"], "chunks": chunk_count(size, r["chunk_size"]),
                        "received": [], "state": "complete"}
        if self.incoming.is_dir():
            for d in self.incoming.iterdir():
                try:
                    m = json.loads((d / "meta.json").read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    continue
                if m.get("sha256") == sha256 and m.get("size") == size:
                    return self._reply(m)          # the same bundle again: resume it
        bid = secrets.token_hex(8)
        d = self.incoming / bid
        d.mkdir(parents=True)
        meta = {"id": bid, "name": SAFE.sub("_", name or "bundle.zip")[:100] or "bundle.zip", "size": size, "sha256": sha256,
                "chunk_size": self.chunk_size, "created": time.time()}
        (d / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
        return self._reply(meta)

    def put_chunk(self, bid: str, n: int, data: bytes) -> dict:
        meta = self._meta(bid)
        total = chunk_count(meta["size"], meta["chunk_size"])
        if not 0 <= n < total:
            raise BundleError(400, f"chunk {n} out of range 0..{total - 1}")
        want = chunk_length(meta["size"], meta["chunk_size"], n)
        if len(data) != want:
            raise BundleError(400, f"chunk {n} is {len(data)} bytes, expected {want}")
        d = self._dir(bid)
        tmp = d / f"{n}.tmp"
        tmp.write_bytes(data)
        tmp.replace(d / f"{n}.chunk")
        return {"received": n}

    def status(self, bid: str) -> dict:
        if ID_RE.fullmatch(bid or "") and (self.done / f"{bid}.json").is_file():
            r = json.loads((self.done / f"{bid}.json").read_text(encoding="utf-8"))
            return {"id": bid, "chunk_size": r["chunk_size"], "chunks": chunk_count(r["size"], r["chunk_size"]),
                    "received": [], "state": "complete"}
        return self._reply(self._meta(bid))

    def complete(self, bid: str) -> dict:
        if ID_RE.fullmatch(bid or "") and (self.done / f"{bid}.json").is_file():
            return self.status(bid)
        meta = self._meta(bid)
        d = self._dir(bid)
        total = chunk_count(meta["size"], meta["chunk_size"])
        missing = [n for n in range(total) if not (d / f"{n}.chunk").is_file()]
        if missing:
            raise BundleError(409, f"{len(missing)} chunk(s) missing, first {missing[0]}")
        zpath = d / "bundle.zip"
        h = hashlib.sha256()
        with open(zpath, "wb") as out:
            for n in range(total):
                with open(d / f"{n}.chunk", "rb") as c:
                    while block := c.read(1 << 20):
                        h.update(block)
                        out.write(block)
        if h.hexdigest() != meta["sha256"]:
            shutil.rmtree(d, ignore_errors=True)
            raise BundleError(422, "sha256 mismatch: upload discarded, send it again")
        if not zipfile.is_zipfile(zpath):
            shutil.rmtree(d, ignore_errors=True)
            raise BundleError(422, "not a ZIP archive")
        session = self._session_id(zpath, meta["name"])
        day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        dest = self.root / day / session
        n = 1
        while dest.exists():
            n += 1
            dest = self.root / day / f"{session}-{n}"
        try:
            safe_extract(zpath, dest, self.max_bytes * MAX_UNPACKED_FACTOR)
        except BundleError:
            shutil.rmtree(dest, ignore_errors=True)
            shutil.rmtree(d, ignore_errors=True)
            raise
        except zipfile.BadZipFile:
            shutil.rmtree(dest, ignore_errors=True)
            shutil.rmtree(d, ignore_errors=True)
            raise BundleError(422, "corrupt ZIP") from None
        self.done.mkdir(parents=True, exist_ok=True)
        (self.done / f"{bid}.json").write_text(json.dumps({"id": bid, "sha256": meta["sha256"], "size": meta["size"],
                                                           "chunk_size": meta["chunk_size"], "path": str(dest),
                                                           "time": time.time()}), encoding="utf-8")
        shutil.rmtree(d, ignore_errors=True)
        self.prune()
        return {"id": bid, "state": "complete", "path": str(dest), "chunk_size": meta["chunk_size"],
                "chunks": total, "received": []}

    # ---- housekeeping
    @staticmethod
    def _session_id(zpath: Path, name: str) -> str:
        sid = ""
        try:
            with zipfile.ZipFile(zpath) as z:
                sid = str(json.loads(z.read("manifest.json")).get("session_id") or "")
        except (KeyError, ValueError, OSError, zipfile.BadZipFile):
            pass
        sid = SAFE.sub("_", sid or Path(name).stem).strip("._-")[:80]
        return sid or "bundle"

    def _cleanup_stale(self) -> None:
        if not self.incoming.is_dir():
            return
        now = time.time()
        for d in self.incoming.iterdir():
            try:
                if now - d.stat().st_mtime > STALE_INCOMING_S:
                    shutil.rmtree(d, ignore_errors=True)
            except OSError:
                pass

    def sessions(self) -> list[Path]:
        """Unpacked bundle folders, newest first (by folder mtime)."""
        out = []
        if self.root.is_dir():
            for day in self.root.iterdir():
                if day.is_dir() and re.fullmatch(r"\d{4}-\d{2}-\d{2}", day.name):
                    out += [s for s in day.iterdir() if s.is_dir()]
        return sorted(out, key=lambda p: (p.stat().st_mtime, p.name), reverse=True)

    def prune(self) -> int:
        old = self.sessions()[self.keep:]
        for s in old:
            shutil.rmtree(s, ignore_errors=True)
        for f in self.done.glob("*.json") if self.done.is_dir() else []:
            try:
                if not Path(json.loads(f.read_text(encoding="utf-8")).get("path", "")).exists():
                    f.unlink()
            except (OSError, ValueError):
                pass
        for day in self.root.iterdir() if self.root.is_dir() else []:
            if day.is_dir() and day.name not in (".done", "incoming") and not any(day.iterdir()):
                day.rmdir()
        return len(old)

    def latest(self) -> Path | None:
        s = self.sessions()
        return s[0] if s else None
