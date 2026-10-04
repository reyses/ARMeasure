"""Access log, auth-failure log, per-IP lockout and the server-address filter (pure ASGI, outermost layers)."""
from __future__ import annotations

import ipaddress
import json
import threading
import time
from collections import deque
from datetime import datetime, timezone
from pathlib import Path

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from . import config

LOOPBACK = ("127.0.0.1", "::1")


class JsonlLog:
    """Append-only JSON-lines file with size rotation (name.1 .. name.N). Opened per write: no handle is kept."""

    def __init__(self, path: Path, max_bytes: int = 5 * 1024 * 1024, backups: int = 5):
        self.path, self.max_bytes, self.backups = Path(path), max_bytes, backups
        self.lock = threading.Lock()

    def write(self, rec: dict) -> None:
        line = json.dumps(rec, separators=(",", ":"), ensure_ascii=True) + "\n"
        with self.lock:
            try:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                if self.path.exists() and self.path.stat().st_size + len(line) > self.max_bytes:
                    for i in range(self.backups, 0, -1):
                        src = self.path if i == 1 else self.path.with_name(f"{self.path.name}.{i - 1}")
                        if src.exists():
                            src.replace(self.path.with_name(f"{self.path.name}.{i}"))
                with open(self.path, "a", encoding="utf-8") as f:
                    f.write(line)
            except OSError:
                pass       # logging must never take the server down


class Lockout:
    """N failures from one IP within a window lock that IP out for a fixed time."""

    def __init__(self, failures: int = config.LOCKOUT_FAILURES, window: float = config.LOCKOUT_WINDOW_S,
                 duration: float = config.LOCKOUT_DURATION_S, clock=time.monotonic):
        self.failures, self.window, self.duration, self.clock = failures, window, duration, clock
        self.hits: dict[str, deque] = {}
        self.until: dict[str, float] = {}
        self.lock = threading.Lock()

    def locked(self, ip: str) -> float:
        """Seconds of lockout left for ip (0 = not locked)."""
        with self.lock:
            left = self.until.get(ip, 0) - self.clock()
            if left <= 0:
                self.until.pop(ip, None)
                return 0.0
            return left

    def fail(self, ip: str) -> bool:
        """Record a failed auth; True when this failure triggers the lockout."""
        now = self.clock()
        with self.lock:
            if len(self.hits) > 10000:      # bound memory under a spray from many IPs
                for k in [k for k, d in self.hits.items() if not d or now - d[-1] > self.window]:
                    self.hits.pop(k, None)
            d = self.hits.setdefault(ip, deque())
            d.append(now)
            while d and now - d[0] > self.window:
                d.popleft()
            if len(d) >= self.failures:
                self.until[ip] = now + self.duration
                d.clear()
                return True
            return False


def effective_ip(scope: Scope) -> tuple[str, str]:
    """(client ip, peer ip). Forwarding headers are believed ONLY when the TCP peer is loopback (cloudflared)."""
    peer = (scope.get("client") or ("-", 0))[0] or "-"
    if peer not in LOOPBACK:
        return peer, peer
    hdr = {k.decode("latin-1").lower(): v.decode("latin-1") for k, v in scope["headers"]}
    cand = hdr.get("cf-connecting-ip") or hdr.get("x-forwarded-for", "").split(",")[0]
    try:
        return str(ipaddress.ip_address(cand.strip())), peer
    except ValueError:
        return peer, peer


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


class AccessGuard:
    """Outermost layer: lockout (429), access.log line per request, auth_failures.jsonl + lockout counting on 401."""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        st = scope["app"].state
        ip, peer = effective_ip(scope)
        method, path = scope["method"], scope["path"]
        status = 500

        async def tracked(msg: Message):
            nonlocal status
            if msg["type"] == "http.response.start":
                status = msg["status"]
            await send(msg)

        left = st.lockout.locked(ip)
        try:
            if left > 0:
                body = json.dumps({"error": {"code": "busy", "message": "too many failed attempts"}}).encode()
                await tracked({"type": "http.response.start", "status": 429, "headers": [
                    (b"content-type", b"application/json"), (b"content-length", str(len(body)).encode()),
                    (b"retry-after", str(int(left) + 1).encode())]})
                await tracked({"type": "http.response.body", "body": body})
            else:
                await self.app(scope, receive, tracked)
        finally:
            rec = {"ts": _now(), "ip": ip, "method": method, "path": path, "status": status}
            if ip != peer:
                rec["peer"] = peer
            st.access_log.write(rec)
            if status == 401:
                hdrs = {k.lower() for k, _ in scope["headers"]}
                reason = "missing" if b"authorization" not in hdrs else "invalid"
                locked = st.lockout.fail(ip)
                st.auth_log.write({**rec, "reason": reason, "locked": locked})


class AddressFilter:
    """Reject requests whose server-side (local) address is not allowed. Defence in depth for --bind modes."""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        allowed = scope["app"].state.allowed_hosts if scope["type"] == "http" else None
        server = scope.get("server")
        if allowed is None or server is None or server[0] in allowed:
            return await self.app(scope, receive, send)
        body = b'{"error":{"code":"forbidden","message":"not served on this address"}}'
        await send({"type": "http.response.start", "status": 403, "headers": [
            (b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())]})
        await send({"type": "http.response.body", "body": body})
