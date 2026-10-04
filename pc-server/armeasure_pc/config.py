"""Paths, token storage and runtime settings."""
from __future__ import annotations

import json
import os
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATA_DIR = ROOT / "data"
TOOLS_DIR = ROOT / "tools"
PORT = 48310
MAX_UPLOAD_BYTES = 2 * 1024 ** 3
JOB_TTL_DAYS = 7
CLOUDFLARED = Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "cloudflared" / "cloudflared.exe"


def load_token(data_dir: Path, new: bool = False) -> str:
    """Return the persisted token, generating (or rotating) it when needed."""
    data_dir.mkdir(parents=True, exist_ok=True)
    cfg_path = data_dir / "config.json"
    cfg: dict = {}
    if cfg_path.exists():
        try:
            cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            cfg = {}
    tok = cfg.get("token")
    if new or not isinstance(tok, str) or len(tok) < 32:
        cfg["token"] = secrets.token_urlsafe(32)  # 43 chars
        tmp = cfg_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
        os.replace(tmp, cfg_path)
    return cfg["token"]

# ---- dev link (docs/DEV_LINK.md) ----
DEFAULT_APK_DIR = Path(r"D:\APK")
DEV_PACKAGES = {"com.example.arruler": "ARMeasure-*.apk", "com.reyses.leaveontime": "LeaveOnTime-*.apk"}
MAX_DEV_LOG_BYTES = 20 * 1024 * 1024
DEV_LOG_KEEP = 500
TAILSCALE = Path(r"C:\Program Files\Tailscale\tailscale.exe")

# ---- Beam bundles (docs/BEAM.md) ----
BUNDLE_MAX_BYTES = 4 * 1024 ** 3
BUNDLE_CHUNK_BYTES = 8 * 1024 * 1024
BUNDLE_KEEP = 50

# ---- access control ----
LOCKOUT_FAILURES = 10
LOCKOUT_WINDOW_S = 600
LOCKOUT_DURATION_S = 900
