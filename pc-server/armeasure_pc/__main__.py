"""python -m armeasure_pc [--tunnel] [--new-token] [--host H] [--port P] [--no-open]"""
from __future__ import annotations

import argparse
import socket
import sys
from pathlib import Path

import uvicorn

from . import config
from .app import create_app
from .net import lan_ipv4
from .pairing import show
from .tunnel import Tunnel


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="armeasure_pc")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=config.PORT)
    ap.add_argument("--tunnel", action="store_true", help="start a Cloudflare quick tunnel and pair via its https URL")
    ap.add_argument("--new-token", action="store_true", help="rotate the token (old pairings stop working)")
    ap.add_argument("--no-open", action="store_true", help="do not open the pairing PNG in the image viewer")
    ap.add_argument("--data-dir", type=Path, default=config.DEFAULT_DATA_DIR)
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

    token = config.load_token(args.data_dir, new=args.new_token)
    if args.new_token:
        print("Token rotated.")
    tunnel = None
    try:
        if args.tunnel:
            tunnel = Tunnel(args.port)
            print("Starting Cloudflare quick tunnel ...")
            url = tunnel.start()
        else:
            url = f"http://{lan_ipv4()}:{args.port}"
        print(f"Server URL: {url}")
        show(url, token, socket.gethostname(), args.data_dir, open_png=not args.no_open)
        app = create_app(args.data_dir, token)
        uvicorn.run(app, host=args.host, port=args.port, log_level="info")
    finally:
        if tunnel:
            tunnel.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
