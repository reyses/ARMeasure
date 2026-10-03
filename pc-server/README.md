# ARMeasure PC server

PC side of the phone <-> PC processing link. Implements `docs/PROCESSING_PROTOCOL.md` (API v1, FastAPI + Open3D).
Python 3.12.10, venv `C:\venvs\armeasure-pc`, all dependencies pinned in `requirements.txt`.

## Start

```powershell
C:\venvs\armeasure-pc\Scripts\python.exe -m armeasure_pc            # LAN mode, http://<LAN IPv4>:48310
C:\venvs\armeasure-pc\Scripts\python.exe -m armeasure_pc --tunnel   # Cloudflare quick tunnel, https URL in the QR
```

Run it from `D:\ARMeasure\pc-server`. On start it prints the pairing JSON `{"v":1,"url","token","name"}`, an ASCII QR in
the console and opens `data\pairing.png` in the default image viewer (`--no-open` to skip). Scan it in the app.

Flags: `--tunnel`, `--new-token` (rotate), `--no-open`, `--host`, `--port` (default 0.0.0.0:48310), `--data-dir`.
`--tunnel` needs `%LOCALAPPDATA%\Programs\cloudflared\cloudflared.exe`. The LAN IP is the physical adapter that carries
the default route (virtual adapters such as Hyper-V, WSL, VMware, VPN are skipped).

Tests: `C:\venvs\armeasure-pc\Scripts\python.exe -m pytest` (about 10 s).

## Firewall (LAN mode)

Allow inbound TCP 48310 on Private networks only. Run once in an elevated PowerShell (not run for you):

```powershell
New-NetFirewallRule -DisplayName "ARMeasure PC server" -Direction Inbound -Protocol TCP -LocalPort 48310 -Action Allow -Profile Private
```

## Security

- Every request needs `Authorization: Bearer <token>`; the token (43 chars, `secrets.token_urlsafe(32)`) is created on
  first run in `data\config.json` (gitignored) and compared in constant time. Uploads are authenticated and
  size-checked before the body is read.
- The quick-tunnel URL is PUBLIC (anyone who learns it reaches the server), so the token is the only protection.
  Do not paste the pairing JSON or `pairing.png` anywhere. The tunnel URL changes on every start.
- Rotate with `--new-token`; the phone must pair again.
- ZIPs are extracted with a path-traversal check; jobs older than 7 days are deleted (checked at start and hourly).

## Processing

| job_type | what runs |
|---|---|
| `scan_analyze` | outlier removal, iterative RANSAC planes, floor/ceiling/wall, room outline, area/perimeter/height/volume with low/high = walls and floor/ceiling moved by 1 cm |
| `object_mesh` | crop to oriented box (optional), support-plane removal (1.6 x voxel), DBSCAN, Poisson (depth 9, 10 below 4 mm voxel, trimmed at 5 % density) with ball-pivoting fallback; dimensions and hull volume come from denoised points (raw noise inflates a 4 mm-noise 200 mm cube to about 230 mm) |
| `photogrammetry` | COLMAP + OpenMVS, see `INSTALL_PHOTOGRAMMETRY.md`; fails with a clear message when the tools are missing |

Open3D (pip) is CPU-only; the RTX 3060 is used only by COLMAP/OpenMVS. Result entries are exactly the protocol names
(`result.json`, `mesh.ply`, `mesh.obj`, `texture.png`, `planes.json`, `cloud_clean.ply`).
