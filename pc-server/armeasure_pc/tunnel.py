"""Cloudflare quick tunnel via the portable cloudflared.exe."""
from __future__ import annotations

import re
import subprocess
import threading
import time

from . import config

URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")


class Tunnel:
    def __init__(self, port: int):
        self.port = port
        self.proc: subprocess.Popen | None = None
        self.url: str | None = None
        self.lines: list[str] = []

    def start(self, timeout: float = 60.0) -> str:
        exe = config.CLOUDFLARED
        if not exe.exists():
            raise RuntimeError(f"cloudflared not found at {exe}")
        self.proc = subprocess.Popen(
            [str(exe), "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{self.port}"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        found = threading.Event()

        def pump():
            for line in self.proc.stdout:
                self.lines.append(line.rstrip())
                m = URL_RE.search(line)
                if m and not self.url:
                    self.url = m.group(0)
                    found.set()

        threading.Thread(target=pump, daemon=True).start()
        deadline = time.time() + timeout
        while time.time() < deadline and not found.is_set():
            if self.proc.poll() is not None:
                break
            time.sleep(0.2)
        if not self.url:
            self.stop()
            raise RuntimeError("cloudflared did not report a trycloudflare URL:\n" + "\n".join(self.lines[-10:]))
        return self.url

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
