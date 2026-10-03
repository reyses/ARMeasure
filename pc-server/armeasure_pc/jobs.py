"""Single-worker job queue with on-disk state under <data>/jobs/<id>/."""
from __future__ import annotations

import json
import os
import queue
import shutil
import threading
import time
import traceback
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from .processing.common import Cancelled, Ctx, JobError

TYPES = ("SCAN_ANALYZE", "OBJECT_MESH", "PHOTOGRAMMETRY", "DRONE_PHOTOS")
STATES = ("QUEUED", "RUNNING", "DONE", "FAILED", "CANCELLED")


def normalize_type(t: str | None) -> str | None:
    """Wire strings are lower-case (scan_analyze); the upper-case spelling is accepted too."""
    if not isinstance(t, str):
        return None
    t = t.strip().upper()
    return t if t in TYPES else None


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def _processor(job_type: str):
    if job_type == "SCAN_ANALYZE":
        from .processing import scan
        return scan.run
    if job_type == "OBJECT_MESH":
        from .processing import objmesh
        return objmesh.run
    if job_type == "DRONE_PHOTOS":
        from .processing import drone
        return drone.run
    from .processing import photogrammetry
    return photogrammetry.run


class JobManager:
    def __init__(self, data_dir: Path, ttl_days: float = 7):
        self.root = Path(data_dir) / "jobs"
        self.root.mkdir(parents=True, exist_ok=True)
        self.ttl = ttl_days * 86400
        self.q: queue.Queue = queue.Queue()
        self.lock = threading.RLock()
        self.cancel_flags: set[str] = set()
        self.procs: dict[str, object] = {}
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._last_cleanup = 0.0
        self.on_progress = None     # optional callback(progress, stage) used by the CLI

    # ------------------------------------------------------------ storage
    def dir(self, job_id: str) -> Path:
        if not job_id.isalnum():
            raise KeyError(job_id)
        return self.root / job_id

    def _meta_path(self, job_id: str) -> Path:
        return self.dir(job_id) / "job.json"

    def get(self, job_id: str) -> dict | None:
        try:
            with self.lock:
                return json.loads(self._meta_path(job_id).read_text(encoding="utf-8"))
        except (OSError, ValueError, KeyError):
            return None

    def _save(self, meta: dict) -> None:
        meta["updated"] = now_iso()
        p = self._meta_path(meta["id"])
        tmp = p.with_suffix(".tmp")
        with self.lock:
            tmp.write_text(json.dumps(meta), encoding="utf-8")
            os.replace(tmp, p)

    def _update(self, job_id: str, **kw) -> dict | None:
        with self.lock:
            meta = self.get(job_id)
            if meta is None:
                return None
            meta.update(kw)
            self._save(meta)
            return meta

    def create(self, job_type: str) -> tuple[str, Path]:
        job_id = uuid.uuid4().hex
        d = self.dir(job_id)
        d.mkdir(parents=True)
        meta = {"id": job_id, "type": job_type, "state": "QUEUED", "progress": 0.0, "stage": "queued",
                "message": None, "created": now_iso()}
        self._save(meta)
        return job_id, d / "upload.zip"

    def create_local(self, job_type: str, source: dict, name: str | None = None) -> str:
        """Job whose input is a folder on this PC (source.json instead of upload.zip); the photos stay in place."""
        job_id, _ = self.create(job_type)
        d = self.dir(job_id)
        (d / "source.json").write_text(json.dumps({**source, "name": name}), encoding="utf-8")
        if name:
            self._update(job_id, name=name)
        return job_id

    def run_inline(self, job_id: str) -> dict | None:
        """Run a created job in the calling thread (CLI) and return its final job.json."""
        self._run(job_id)
        return self.get(job_id)

    def enqueue(self, job_id: str) -> None:
        self.q.put(job_id)

    def public(self, meta: dict, position: int | None = None) -> dict:
        out = {"id": meta["id"], "state": meta["state"].lower(), "progress": meta.get("progress", 0.0),
               "stage": meta.get("stage", "")}
        if meta["state"] == "QUEUED" and position:
            out["position"] = position
        if meta["state"] == "FAILED":
            out["error"] = meta.get("message") or "failed"
        return out

    def queue_position(self, job_id: str) -> int | None:
        with self.q.mutex:
            items = [j for j in self.q.queue if j is not None]
        if job_id in items:
            return items.index(job_id) + 1 + (1 if self.running_count() else 0)
        return None

    def running_count(self) -> int:
        n = 0
        for d in self.root.iterdir():
            m = self.get(d.name) if d.is_dir() else None
            if m and m["state"] == "RUNNING":
                n += 1
        return n

    # ------------------------------------------------------------ control
    def discard(self, job_id: str) -> None:
        shutil.rmtree(self.dir(job_id), ignore_errors=True)

    def cancel_and_delete(self, job_id: str) -> None:
        """Cancel if queued/running and delete the data. Idempotent."""
        meta = self.get(job_id)
        if meta is None:
            return
        if meta["state"] == "RUNNING":
            with self.lock:
                self.cancel_flags.add(job_id)
                p = self.procs.get(job_id)
            self._update(job_id, state="CANCELLED", stage="cancelled")
            if p is not None:
                try:
                    p.kill()
                except Exception:  # noqa: BLE001
                    pass
            return      # the worker removes the directory when it unwinds
        self.discard(job_id)

    # ------------------------------------------------------------ worker
    def start(self) -> None:
        # recover: jobs that were running when the server died cannot continue
        for d in sorted(self.root.iterdir()):
            m = self.get(d.name) if d.is_dir() else None
            if not m:
                continue
            if m["state"] == "RUNNING":
                self._update(m["id"], state="FAILED", message="server restarted while the job was running")
            elif m["state"] == "QUEUED" and ((d / "upload.zip").exists() or (d / "source.json").exists()):
                self.q.put(m["id"])
            elif m["state"] == "QUEUED":
                self._update(m["id"], state="FAILED", message="upload incomplete")
        self.cleanup()
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="job-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self.q.put(None)
        if self._thread:
            self._thread.join(timeout=5)

    def cleanup(self) -> int:
        n, cutoff = 0, time.time() - self.ttl
        for d in self.root.iterdir():
            if not d.is_dir():
                continue
            mp = d / "job.json"
            ref = mp.stat().st_mtime if mp.exists() else d.stat().st_mtime
            m = self.get(d.name)
            if ref < cutoff and not (m and m["state"] in ("QUEUED", "RUNNING")):
                shutil.rmtree(d, ignore_errors=True)
                n += 1
        self._last_cleanup = time.time()
        return n

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                job_id = self.q.get(timeout=3600)
            except queue.Empty:
                job_id = None
            if time.time() - self._last_cleanup > 3600:
                self.cleanup()
            if job_id is None:
                continue
            self._run(job_id)

    def _run(self, job_id: str) -> None:
        meta = self.get(job_id)
        if meta is None or meta["state"] != "QUEUED":
            return  # deleted or cancelled while queued
        self._update(job_id, state="RUNNING", stage="starting", progress=0.0)
        d = self.dir(job_id)
        work, out = d / "work", d / "result"
        work.mkdir(exist_ok=True)

        def upd(p, stage, message=None):
            cur = self.get(job_id)
            if cur and cur["state"] == "RUNNING":
                self._update(job_id, progress=round(float(p), 3), stage=stage, message=message)
                if self.on_progress:
                    self.on_progress(float(p), stage)

        def register(p):
            with self.lock:
                if p is None:
                    self.procs.pop(job_id, None)
                else:
                    self.procs[job_id] = p

        ctx = Ctx(work, upd, lambda: job_id in self.cancel_flags, register)
        t0 = time.time()
        try:
            result = _processor(meta["type"])(d / "upload.zip", out, ctx)
            result.setdefault("stats", {})["backend"] = "pc"
            result.setdefault("stats", {}).setdefault("notes", [])
            files = sorted(f.relative_to(out).as_posix() for f in out.rglob("*")
                           if f.is_file() and f.name != "result.json")
            result = {"schema": 1, "job_type": meta["type"].lower(), **result, "files": files}
            (out / "result.json").write_text(json.dumps(result, indent=1), encoding="utf-8")
            ctx.check()
            with zipfile.ZipFile(d / "result.zip", "w", zipfile.ZIP_DEFLATED) as z:
                for f in sorted(out.rglob("*")):
                    if f.is_file():
                        z.write(f, f.relative_to(out).as_posix())
            self._update(job_id, state="DONE", progress=1.0, stage="done", message=None)
        except Cancelled:
            self.cancel_flags.discard(job_id)
            shutil.rmtree(d, ignore_errors=True)
            return
        except JobError as e:
            self._update(job_id, state="FAILED", stage="failed", message=str(e)[:2000])
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            self._update(job_id, state="FAILED", stage="failed", message=f"internal error: {type(e).__name__}: {e}")
        finally:
            self.cancel_flags.discard(job_id)
            shutil.rmtree(work, ignore_errors=True)
            # keep upload only while useful; drop it once finished to save disk
            try:
                (d / "upload.zip").unlink()
            except OSError:
                pass
