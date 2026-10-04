"""python -m armeasure_pc [--tunnel] [--new-token] [--bind auto|ADDR] [--port P] [--apk-dir D] [--no-open]"""
from __future__ import annotations

import argparse
import socket
import sys
from pathlib import Path

import uvicorn

from . import config
from .app import create_app
from .net import bind_addresses, lan_ipv4, pairing_urls, tailscale_info
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


def inspect_bundle_cmd(argv) -> int:
    """python -m armeasure_pc inspect-bundle <folder|latest> [--no-video]: summary.md, contact_sheet.jpg, video_frames/, timeline.html."""
    from . import bundle_inspect
    from .bundles import BundleStore
    ap = argparse.ArgumentParser(prog="armeasure_pc inspect-bundle",
                                 description="Digest a Beam bundle folder (data/devbundles/<date>/<session>) for the developer.")
    ap.add_argument("target", help="a bundle folder, or 'latest' for the newest one under data/devbundles")
    ap.add_argument("--data-dir", type=Path, default=config.DEFAULT_DATA_DIR)
    ap.add_argument("--no-video", action="store_true", help="skip ffmpeg frame extraction")
    args = ap.parse_args(argv)
    d = BundleStore(args.data_dir).latest() if args.target == "latest" else Path(args.target)
    if d is None or not Path(d).is_dir():
        print(f"error: no bundle folder ({args.target})", file=sys.stderr)
        return 2
    out = bundle_inspect.inspect_bundle(Path(d), video=not args.no_video)
    print(f"bundle: {d}")
    for k, v in out.items():
        print(f"  {k}: {v}")
    return 0


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else list(argv)
    if argv and argv[0] == "inspect-bundle":
        return inspect_bundle_cmd(argv[1:])
    if argv and argv[0] == "import-drone":
        return import_drone(argv[1:])
    ap = argparse.ArgumentParser(prog="armeasure_pc")
    ap.add_argument("--bind", "--host", dest="bind", default="auto",
                    help="auto (default): 127.0.0.1 + Tailscale IP + LAN IP only; or one address, e.g. 0.0.0.0")
    ap.add_argument("--apk-dir", type=Path, default=config.DEFAULT_APK_DIR, help="folder served by /v1/dev/apk")
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
    socks: list[socket.socket] = []
    try:
        ts = tailscale_info()
        try:
            lan = lan_ipv4()
        except RuntimeError:
            lan = None
        if ts is None:
            print("Tailscale: not available (not installed, stopped or not logged in); LAN/tunnel URLs only.")
        else:
            print(f"Tailscale: {ts['ip']}" + (f" ({ts['fqdn']})" if ts["fqdn"] else ""))
        tunnel_url = None
        if args.tunnel:
            tunnel = Tunnel(args.port)
            print("Starting Cloudflare quick tunnel ...")
            tunnel_url = tunnel.start()
        urls = pairing_urls(args.port, lan, tunnel_url, ts)
        if not urls:
            print("error: no usable address (no LAN, Tailscale or tunnel)", file=sys.stderr)
            return 2
        print("Server URLs (pairing order): " + ", ".join(urls))
        show(urls[0], token, socket.gethostname(), args.data_dir, open_png=not args.no_open, urls=urls)
        hosts = bind_addresses(args.bind, lan, ts)
        allowed = None if args.bind != "auto" else set(hosts)
        app = create_app(args.data_dir, token, apk_dir=args.apk_dir, allowed_hosts=allowed)
        cfg = uvicorn.Config(app, host=hosts[0], port=args.port, log_level="info")
        server = uvicorn.Server(cfg)
        if len(hosts) > 1:
            for h in hosts:
                sk = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                socks.append(sk)
                sk.bind((h, args.port))
                sk.listen(100)
                sk.set_inheritable(True)
            print("Listening on " + ", ".join(f"{h}:{args.port}" for h in hosts))
            server.run(sockets=socks)
        else:
            server.run()
    finally:
        for sk in socks:
            sk.close()
        if tunnel:
            tunnel.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
