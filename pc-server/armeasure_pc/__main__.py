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


def import_drone(argv) -> int:
    """python -m armeasure_pc import-drone <folder> [--quality FINE] [--name X]: run a drone_photos job in-process."""
    from .jobs import JobManager
    from .processing import drone
    ap = argparse.ArgumentParser(prog="armeasure_pc import-drone",
                                 description="Build a metric 3D model from a folder of DJI photos (no zipping).")
    ap.add_argument("folder", type=Path, help="folder with the JPG (or JPG+DNG) files, not recursive")
    ap.add_argument("--quality", default=drone.DEFAULT_QUALITY, help="QUICK | FINE | DETAILED (default FINE)")
    ap.add_argument("--name", default=None, help="label stored in result.json")
    ap.add_argument("--data-dir", type=Path, default=config.DEFAULT_DATA_DIR)
    args = ap.parse_args(argv)
    problem = drone.check_manifest({"quality": args.quality}) or drone.validate_folder(args.folder)
    if problem:
        print(f"error: {problem}", file=sys.stderr)
        return 2
    jm = JobManager(args.data_dir)
    job_id = jm.create_local("DRONE_PHOTOS", {"path": str(args.folder.resolve()), "quality": args.quality.upper()},
                             args.name)
    last = [None]

    def show(p, stage):
        if stage != last[0]:
            last[0] = stage
            print(f"[{p * 100:5.1f} %] {stage}", flush=True)

    jm.on_progress = show
    print(f"job {job_id}")
    meta = jm.run_inline(job_id)
    if not meta or meta["state"] != "DONE":
        print(f"FAILED: {(meta or {}).get('message') or (meta or {}).get('state')}", file=sys.stderr)
        return 1
    d = jm.dir(job_id)
    print(f"done. result folder: {d / 'result'}\nresult zip:    {d / 'result.zip'}\n"
          f"(jobs are deleted after {config.JOB_TTL_DAYS} days: copy what you need)")
    return 0


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else list(argv)
    if argv and argv[0] == "import-drone":
        return import_drone(argv[1:])
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
