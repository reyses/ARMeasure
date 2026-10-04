# Dev link (debug builds only): update from PC, logs to PC

Server contract for the PC side (not implemented yet) plus the owner's one-time setup.
The app side lives in `app/src/debug/java/com/example/arruler/devlink/`; release builds contain only a no-op stub
(`app/src/release/.../DevEntries.kt`), no dev code, no `REQUEST_INSTALL_PACKAGES`.

## Owner's one-time setup (Tailscale)

1. Install Tailscale on the PC (hostname `Rxmoi`, LAN 192.168.0.247) and on the Pixel; sign in with the SAME account on both. Both appear on one tailnet (IPv4 `100.64.0.0/10`, IPv6 `fd7a:115c:a1e0::/48`).
2. Turn MagicDNS on (admin console > DNS). The PC is then reachable as `rxmoi` and `rxmoi.<tailnet>.ts.net`.
3. Start the PC server bound to all interfaces (not only the LAN address), and allow its port through the Windows firewall for the Tailscale network.
4. The pairing QR now lists the tailnet URL first: `urls: [tailnet, lan, tunnel]` (see PROCESSING_PROTOCOL.md section 6). Re-scan it once in Settings > PC. At home the phone falls through to the LAN URL if the tailnet one is slow (2 s connect timeout per candidate).
5. Debug phone only: the first "Update from PC" opens the system page "Install unknown apps"; allow ARMeasure there once.
6. The APK on the PC must be signed with the same key as the installed one (the debug keystore on the PC build machine), otherwise the system refuses the update.

## Auth

Every request carries `Authorization: Bearer <token>` (the pairing token), like all other calls. Wrong or missing token: 401 `{"error":{"code":"unauthorized","message":"..."}}`. Errors use the envelope of PROCESSING_PROTOCOL.md section 3.

## GET /v1/dev/apk?package=<applicationId>

`applicationId` of the asking app (`com.example.arruler`). The server picks the newest APK:

- Directory `D:\APK`; candidates match `ARMeasure-*.apk` (this includes `ARMeasure-debug-<commit>.apk` and `ARMeasure-phone-<commit>.apk`).
- Newest by file modification time (mtime), ties broken by name. Ignore `*.part`, zero-byte files and files modified less than 2 s ago (still being written).
- `commit` = the last hyphen-separated token of the file name without `.apk`, if it matches `[0-9a-f]{7,40}`; otherwise `""`.
- A package other than `com.example.arruler` has no APK: 404 `{"error":{"code":"not_found",...}}`. Empty directory: 404 too.

200 body:

```json
{"versionCode": 1, "versionName": "1.0.0", "commit": "506a405", "size": 123456789,
 "sha256": "<64 lowercase hex>", "url": "/v1/dev/apk/ARMeasure-debug-506a405.apk"}
```

- `versionCode`/`versionName`: read from the APK (`aapt2 dump badging`, or the androguard-style parse of the binary manifest); if that is not possible send `versionCode` 1 and `versionName` `""`. Debug builds all have versionCode 1; the phone therefore decides by `commit`.
- `size` bytes and `sha256` of the file exactly as served (cache the hash by path + mtime + size).
- `url` MUST start with `/v1/dev/apk/`, contain no `..`, `\` or `//`; the phone refuses anything else.

Phone decision (`UpdateDecision`): `versionCode` higher than installed -> offer; otherwise equal commit (prefix match of at least 7 characters) -> "Up to date (commit abc1234)"; otherwise offer. An answer without `sha256` or without `commit` (when versionCode is not higher) is refused as unusable.

## GET /v1/dev/apk/<file>

200 `application/vnd.android.package-archive`, body = the APK, with `Content-Length` (the phone shows progress from it) and Bearer auth. `<file>` must be a plain name from the same `D:\APK` listing matching `ARMeasure-*.apk`; anything else 404. No directory traversal. The phone downloads to `cacheDir/dev-update/update.apk`, checks `size` and `sha256`, then installs through the `PackageInstaller` session API; the system shows its own confirmation.

## POST /v1/dev/logs

`multipart/form-data` with text fields and one file part:

| field | meaning |
|---|---|
| `device` | `<manufacturer> <model>`, e.g. `Google Pixel 11 Pro` |
| `app` | applicationId |
| `commit` | the build's commit (`unknown` if built without git) |
| `kind` | `logs`, `crash` or `diagnostics`; anything else 400 `bad_request` |
| `file` | `text/plain; charset=utf-8`, file name `<kind>-<commit>-<yyyyMMdd-HHmmss UTC>.txt`, at most about 2 MB (logcat tail) |

Contents: `logs` = a header line (app, version, commit, build type, device, Android API, pid) then `logcat -d -v threadtime --pid=<pid>` (last ~2 MB); `diagnostics` = the same header then the Diagnostics report text (device, GL, GPU gate line, AR, cameras); `crash` = the stack trace recorded by the debug-only uncaught-exception handler in `filesDir/crash/last.txt`, uploaded on the next launch once paired, then deleted.

200 (or 201) `{"id": "<opaque string>"}`; the phone shows it ("Sent. logs id ..., diagnostics id ...").

Storage (as implemented in pc-server/armeasure_pc/devlink.py, 2026-10-03): `pc-server\data\devlogs\<yyyy-mm-dd>\<id>-<kind>-<device>.txt` holding the uploaded file's bytes. `id` = `<kind-first-letter>-<yyyymmdd-HHmmss>-<4 random hex>` (e.g. `L-20261003-101500-9f3a`). The device name is sanitised to `[A-Za-z0-9._-]`, max 40 chars. Uploads over 20 MB get 413; the newest 500 uploads are kept. The server prepends one header line `# app=<app> commit=<commit> device=<device> kind=<kind>` from the multipart fields; the client address is in `data\access.log`.

## Notes

- The Settings > Dev section (debug builds) shows the build commit, which pairing URL answered last (tailnet / LAN / tunnel) and the two buttons.
- Pure, JVM-tested parts: `UrlPolicy`, `PairingInfo.parse` with `urls`, `UrlOrder`, `UpdateDecision`, `Sha256`, `DevUpload` (multipart assembly), `TailBuffer`, `CrashStore`, `UrlKind`.
