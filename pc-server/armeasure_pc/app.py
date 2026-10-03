"""FastAPI app implementing docs/PROCESSING_PROTOCOL.md (API v1)."""
from __future__ import annotations

import json
import socket
import zipfile
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from . import __version__, config
from .auth import check_bearer, require_auth
from .jobs import JobManager, normalize_type
from .processing import drone as drone_job
from .net import gpu_info

CHUNK = 1024 * 1024
API_VERSION = 1
CODES = {400: "bad_request", 401: "unauthorized", 403: "forbidden", 404: "not_found", 409: "not_ready", 410: "not_found",
         413: "too_large", 415: "unsupported_media", 422: "invalid_package", 429: "busy", 503: "busy",
         500: "internal"}


def err(status: int, message: str, headers: dict | None = None) -> JSONResponse:
    return JSONResponse({"error": {"code": CODES.get(status, "internal"), "message": message}}, status,
                        headers)


class _TooLarge(Exception):
    pass


class UploadGuard:
    """Pure-ASGI guard for POST /v1/jobs: auth and size are decided BEFORE the body is parsed or stored."""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] != "http" or scope["method"] != "POST" or scope["path"].rstrip("/") != "/v1/jobs":
            return await self.app(scope, receive, send)
        st = scope["app"].state
        headers = {k.decode().lower(): v.decode() for k, v in scope["headers"]}
        if not check_bearer(headers.get("authorization"), st.token):
            return await err(401, "invalid or missing token", {"WWW-Authenticate": "Bearer"})(scope, receive, send)
        limit = st.max_upload
        cl = headers.get("content-length")
        if cl and cl.isdigit() and int(cl) > limit:
            return await err(413, f"upload exceeds {limit} bytes")(scope, receive, send)
        seen = 0

        async def counting() -> Message:
            nonlocal seen
            msg = await receive()
            if msg["type"] == "http.request":
                seen += len(msg.get("body", b""))
                if seen > limit:
                    raise _TooLarge()
            return msg

        started = False

        async def tracked_send(msg: Message):
            nonlocal started
            started = started or msg["type"] == "http.response.start"
            await send(msg)

        try:
            await self.app(scope, counting, tracked_send)
        except _TooLarge:
            if not started:
                await err(413, f"upload exceeds {limit} bytes")(scope, receive, send)


POINT_JOBS = ("SCAN_ANALYZE", "OBJECT_MESH")
PROXY_HEADERS = ("x-forwarded-for", "forwarded", "x-real-ip", "cf-connecting-ip", "cf-ray", "x-forwarded-host")


def validate_package(zpath: Path, job_type: str) -> str | None:
    """Return an error message when the upload is not a valid job package (checked before queueing)."""
    try:
        with zipfile.ZipFile(zpath) as z:
            names = set(z.namelist())
            if job_type == "DRONE_PHOTOS":
                if len(drone_job.image_files(list(names))) < drone_job.MIN_IMAGES:
                    return f"need at least {drone_job.MIN_IMAGES} JPG/DNG images"
                if "manifest.json" not in names:
                    return None          # the manifest is optional for drone photos
            if "manifest.json" not in names:
                return "manifest.json missing"
            info = z.getinfo("manifest.json")
            if info.file_size > 1024 * 1024:
                return "manifest.json too large"
            try:
                man = json.loads(z.read("manifest.json"))
            except ValueError:
                return "manifest.json is not valid JSON"
            if not isinstance(man, dict):
                return "manifest.json must be an object"
            schema = man.get("schema")
            if not isinstance(schema, int) or schema < 1 or schema > 1:
                return f"unsupported manifest schema {schema!r} (this server reads schema 1)"
            mt = man.get("job_type")
            if not isinstance(mt, str) or normalize_type(mt) != job_type:
                return f"manifest job_type {mt!r} does not match type {job_type.lower()!r}"
            if job_type == "DRONE_PHOTOS":
                return drone_job.check_manifest(man)
            if job_type in POINT_JOBS:
                if "cloud.ply" not in names:
                    return "cloud.ply missing"
            else:
                if "poses.json" not in names:
                    return "poses.json missing"
                if not any(n.startswith("images/") and n.lower().endswith(".jpg") for n in names):
                    return "images/*.jpg missing"
    except zipfile.BadZipFile:
        return "corrupt ZIP"
    return None


def create_app(data_dir: Path | None = None, token: str | None = None, max_upload: int | None = None,
               ttl_days: float = config.JOB_TTL_DAYS, start_worker: bool = True) -> FastAPI:
    data_dir = Path(data_dir or config.DEFAULT_DATA_DIR)
    jm = JobManager(data_dir, ttl_days)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if start_worker:
            jm.start()
        yield
        jm.stop()

    app = FastAPI(title="ARMeasure PC server", version=__version__, lifespan=lifespan)
    app.state.token = token or config.load_token(data_dir)
    app.state.max_upload = max_upload or config.MAX_UPLOAD_BYTES
    app.state.jobs = jm
    app.add_middleware(UploadGuard)
    auth = [Depends(require_auth)]

    @app.exception_handler(StarletteHTTPException)
    async def http_exc(request: Request, exc: StarletteHTTPException):
        return err(exc.status_code, str(exc.detail), getattr(exc, "headers", None))

    @app.exception_handler(RequestValidationError)
    async def val_exc(request: Request, exc: RequestValidationError):
        return err(400, "bad request")

    @app.exception_handler(Exception)
    async def any_exc(request: Request, exc: Exception):
        return err(500, f"{type(exc).__name__}: {exc}")

    @app.get("/v1/ping", dependencies=auth)
    def ping():
        name, cuda = gpu_info()
        return {"name": socket.gethostname(), "version": __version__, "gpu": name,
                "api_version": API_VERSION, "max_upload_bytes": app.state.max_upload,
                "cuda": cuda, "jobs_running": jm.running_count()}

    @app.post("/v1/jobs", status_code=202, dependencies=auth)
    async def submit(file: UploadFile | None = File(None), type: str | None = Form(None)):
        if file is None or type is None:
            raise HTTPException(400, "multipart parts 'file' and 'type' are required")
        job_type = normalize_type(type)
        if job_type is None:
            raise HTTPException(400, f"unknown type {type!r}")
        job_id, dest = jm.create(job_type)
        try:
            size = 0
            with open(dest, "wb") as out:
                while chunk := await file.read(CHUNK):
                    size += len(chunk)
                    if size > app.state.max_upload:
                        raise HTTPException(413, "upload too large")
                    out.write(chunk)
            if not zipfile.is_zipfile(dest):
                raise HTTPException(415, "file is not a ZIP archive")
            problem = validate_package(dest, job_type)
            if problem:
                raise HTTPException(422, problem)
        except BaseException:
            jm.discard(job_id)
            raise
        jm.enqueue(job_id)
        return {"id": job_id}

    @app.post("/v1/jobs/local", status_code=202, dependencies=auth)
    async def submit_local(request: Request):
        """Create a job from a folder on THIS PC. Loopback only: the path is read by the server process, so a
        LAN or tunnel client holding the token must not be able to point it at arbitrary server-side folders.
        cloudflared connects from 127.0.0.1, so requests carrying proxy headers are refused as well."""
        host = request.client.host if request.client else None
        if host != "127.0.0.1" or any(h in request.headers for h in PROXY_HEADERS):
            raise HTTPException(403, "this endpoint accepts requests from 127.0.0.1 only")
        try:
            body = await request.json()
        except ValueError:
            raise HTTPException(400, "body must be JSON")
        if not isinstance(body, dict) or not isinstance(body.get("path"), str):
            raise HTTPException(400, "JSON body with a 'path' string is required")
        job_type = normalize_type(body.get("type"))
        if job_type != "DRONE_PHOTOS":
            raise HTTPException(400, "type must be 'drone_photos'")
        problem = drone_job.check_manifest({"quality": body.get("quality")})
        if problem:
            raise HTTPException(422, problem)
        problem = drone_job.validate_folder(Path(body["path"]))
        if problem:
            raise HTTPException(422, problem)
        name = body.get("name") if isinstance(body.get("name"), str) else None
        job_id = jm.create_local(job_type, {"path": str(Path(body["path"]).resolve()),
                                            "quality": body.get("quality")}, name)
        jm.enqueue(job_id)
        return {"id": job_id}

    def _job_or_404(job_id: str) -> dict:
        meta = jm.get(job_id)
        if meta is None:
            raise HTTPException(404, "no such job")
        return meta

    @app.get("/v1/jobs/{job_id}", dependencies=auth)
    def status(job_id: str):
        return jm.public(_job_or_404(job_id), jm.queue_position(job_id))

    @app.get("/v1/jobs/{job_id}/result", dependencies=auth)
    def result(job_id: str):
        meta = _job_or_404(job_id)
        if meta["state"] != "DONE":
            raise HTTPException(409, f"job is {meta['state'].lower()}, not done")
        z = jm.dir(job_id) / "result.zip"
        if not z.exists():
            raise HTTPException(404, "result no longer available")
        return FileResponse(z, media_type="application/zip", filename=f"result-{job_id}.zip")

    @app.delete("/v1/jobs/{job_id}", dependencies=auth, status_code=204)
    def delete(job_id: str):
        jm.cancel_and_delete(job_id)     # idempotent: unknown ids are fine
        return Response(status_code=204)

    return app
