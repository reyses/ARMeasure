# Beam: diagnostics, screen snapshots and video straight to the PC (debug builds only)

Purpose: the owner tests on a Pixel 11 Pro away from the PC. Beam records what happened (event timeline, window snapshots,
the ARCore recording, exports, logcat, crash) and sends one bundle per session to his own PC over Tailscale, so the developer
can read `summary.md`, look at `contact_sheet.jpg` and `video_frames/` without asking him anything.

The real code is in `app/src/debug/java/com/example/arruler/beam/`. `app/src/main/.../beam/BeamEntry.kt` holds only the
interface and the `Beam` facade; `app/src/release/.../beam/BeamEntries.kt` is a no-op. Same pattern as `devlink/`.
A release APK records and uploads nothing.

## Privacy statement

- Camera video of the owner's home (the ARCore recording of a Scan / Object run), window snapshots (540 px wide, JPEG) and the
  optional full screen recording go **only to his own PC**, over the tailnet or the LAN. The public Cloudflare tunnel URL is
  refused unless "Allow over the public tunnel" is switched on (default off).
- Network policy (default): a bundle of 20 MB or less is uploaded over any network when the paired URL is a tailnet or LAN URL
  (tailnet traffic over mobile data is fine for small payloads); a bundle over 20 MB waits for Wi-Fi (unmetered network) unless
  "Allow large uploads on mobile data" is switched on (default off) or "Wi-Fi only" is switched off. The public tunnel URL is
  never used on mobile data while "Wi-Fi only" is on. Logs, crash and diagnostics (Dev link, `POST /v1/dev/logs`) are small and
  not gated by Beam's Wi-Fi switch.
- "Beam to PC" off = nothing is recorded beyond the features that already exist (the ARCore recording he starts himself,
  Dev link logs). Nothing is recorded either until a PC is paired.
- Bundles live in `files/beam/` (app-private) until the PC confirms the sha256, then they are deleted from the phone. On the
  PC they sit in `pc-server\data\devbundles\`, newest 50 kept.
- No audio is ever recorded. The screen recording needs the system consent prompt every time it is started.

## Settings rows (Settings > "Beam to PC (debug build)")

Add one `Section` in `ui/SettingsScreen.kt`, next to the Dev section (about line 146):

```kotlin
if (com.example.arruler.beam.BeamEntries.entry.enabled) {   // BeamEntries is defined in debug and release
    Section("Beam to PC (debug build)") { com.example.arruler.beam.BeamEntries.entry.SettingsSection(pairing) }
}
```

Rows it shows (all in `BeamEntries.SettingsSection`, stored in SharedPreferences `beam_settings`):

| row | default | meaning |
|---|---|---|
| Beam to PC | ON (acts only when a PC is paired) | master switch |
| Wi-Fi only | ON | bundles over 20 MB wait for an unmetered network (NET_CAPABILITY_NOT_METERED); small ones are not held back (tailnet / LAN URL) |
| Allow large uploads on mobile data | OFF | lets bundles over 20 MB use mobile data too (tailnet / LAN URL; the tunnel still needs "Wi-Fi only" off) |
| Allow over the public tunnel | OFF | otherwise only tailnet / LAN pairing URLs are used (`UrlKind`) |
| Include camera video | ON | the ARCore recording of the session goes into the bundle |
| Include screen snapshots | ON | PixelCopy snapshots every 2 s while a capture session is active |
| Full screen recording | OFF | MediaProjection MP4 (720p, 4 Mbps); switching it on opens the system prompt |
| Send this session now | | ends the app session, builds its bundle, queues it |

Plus one status line ("Upload: uploading x.zip 42 %", "waiting for Wi-Fi", ...).

## Sessions

- **app session**: first started activity until 1.5 s after the last one stopped (rotation does not split it). Automatic, no call site.
- **run session**: a Scan or Object run. `Beam.startRun("scan" | "object", label)` ... `Beam.endRun(reason)`. While a run is open
  snapshots are taken every 2 s. Events go to both open sessions. A run is closed automatically when the app goes to the background.
- Snapshots also run in an app session between `capture_start` and `capture_stop` events.
- A session whose process died is found at the next launch (no `closed.json`), bundled with reason `crash` plus the crash text.
- An app session with nothing but start / stop / fps / thermal events is not worth a bundle and is dropped.

## Call sites (all wired 2026-10-03; MainActivity unless noted)

Wiring notes: item 3 starts a run when the mode becomes Scan or Object and ends it with "mode_left"; resets (`onScanReset`,
`onObjectReset`) end it with "cancel" and start a fresh run while the mode stays; a finished Object result ends the run with
"finish" (after an `analyze_result` event), a finished Scan analysis (phone collector on `scan.analysis`, or the PC result) with
"analyze_done". Item 6 is in `onArFrame` from `ar.centerHit`. Item 9: `box_placed` in `renderObject` (first box of a placement),
`box_moved` at drag end, `box_resized` after resize/scale buttons and after Fit. Item 11 attaches in `stopCaptureVideo`. Item 12
`analyze_start` is in `runScanAnalyze` (the real start, after the picker). Item 14 attaches in `MeshShare`, `PlanShare`,
`ScanFiles.save`, and `publicExport` writes an `export` event with the file names. Item 16: `beamError(...)` at the catch / failure
sites, in `showActionError` (the ActionError card) and in the scan coroutine handler. Original plan text follows.

`Beam.event(type, "key" to value, ...)` is safe from any thread, never throws and returns at once in release.

1. `MainActivity.onCreate`: directly **before** `DevEntries.entry.onCreate(application)`:
   `com.example.arruler.beam.BeamEntries.entry.onCreate(application)`  (it copies the previous run's `crash/last.txt` before
   the dev link uploads and deletes it).
2. Same place, inside the `hub.pairing.collect { ... }` lambda, add `BeamEntries.entry.onPairing(this@MainActivity, it)` before the DevEntries call.
3. `onSetAppMode(mode)` (line ~757): `Beam.event("mode_change", "mode" to mode.name)`; and when `mode` is SCAN or OBJECT
   `Beam.startRun(mode.name.lowercase(), "")`, when leaving them `Beam.endRun("mode_left")` (this runs before `cancelJob()`).
4. Screen changes: where `screen = ...` is assigned (a single `LaunchedEffect(screen)` in the content block is enough):
   `Beam.event("screen_change", "screen" to screen::class.simpleName)`.
5. `onArTap(x, y)` (line ~680) and `onObjectTapAt(x, y)` (~941): `Beam.event("tap", "x" to x, "y" to y, "view_w" to renderer view width, "view_h" to view height, "target" to "measure" | "object" | "shape" | "area")`.
6. Hit quality: where the reticle's `SurfaceHit` is computed per frame (`onArFrame`, ~666): `Beam.event("hit_quality", "quality" to hit.quality.name, "kind" to hit.kind.name)`.
   Call it every frame; Beam emits only when (quality, kind) changes.
7. `onArFrame()` first line: `Beam.event("frame")` (no data). Beam folds frames into one `fps` event per second (fps, frames, max_gap_ms).
8. `ArSessionController` where `_trackingState.value = frame.camera.trackingState` (ar/ArSessionController.kt:236): only when it
   changes, `Beam.event("tracking_state", "state" to state.name, "reason" to frame.camera.trackingFailureReason.name)`.
9. Object box (`onObjectTapAt`, `onObjectDragStart/Drag/DragEnd`, `onObjectFit`, `onObjectFrame` auto box): `Beam.event("box_placed", "cx" ..., "cy", "cz", "w", "d", "h")`,
   `"box_moved"` at drag end (center only), `"box_resized"` at drag end (sizes), all metres.
10. Capture: `onObjectResume()` ~1045 / scan `onScanStartPause()` start branch: `Beam.event("capture_start", "mode" to ...)`; `onObjectPause()` / scan pause:
    `Beam.event("capture_stop", "frames" to n)`.
11. ARCore recording file: `ar/SessionRecorder.stop()` (or the places that read `captureVideo`, MainActivity ~1060-1065):
    `Beam.attach("arcore_recording", file)` once the recorder reports Idle, so the MP4 of that session ships with the bundle.
12. Analyze: `onScanAnalyze()` ~807 start `Beam.event("analyze_start")`; result callbacks (`onObjectDone`, the scan PC result, failure):
    `Beam.event("analyze_result", "summary" to "<what the UI shows: n surfaces, RMS, volume, ...>")`, then `Beam.endRun("analyze_done")`.
13. Object Finish: `onObjectFinish()` ~1130 calls `Beam.endRun("finish")` after `onObjectDone` produced its result; `onObjectReset()` / `onScanReset()` / `cancelJob()` call `Beam.endRun("cancel")`.
14. Exports: in `onObjectShare`, `onSaveRoom`, `onObjectSave` and the surfaces / cloud / mesh writers (`scan3d/ScanFiles`, `MeshIo`, `PublicStorage` callers):
    `Beam.attach("export", file)` for each produced file (OBJ, PLY, mesh, measurements.json). The call also writes an `export` event.
15. GPU gate: `GpuGate.init` result and every verification change (`GpuGate.verification` collector, MainActivity ~403):
    `Beam.event("gpu_gate", "status" to GpuGate.statusLine(...))`.
16. Errors: every `catch` that shows a toast or error state, and the processing failure paths: `Beam.event("error", "message" to e.message, "stack" to e.stackTraceToString())`.
    An `error` event also takes a window snapshot at once. Uncaught exceptions are recorded by Beam's own hook (installed in `onCreate`), no call site needed.
17. Thermal: nothing to add (PowerManager listener, API 29+, registered in `onCreate`).
18. `ui/SettingsScreen.kt`: the Beam section above.
19. `MainActivity.onDestroy`: nothing; the lifecycle callbacks close the app session.

Event types written to `timeline.jsonl` (one JSON object per line: `seq`, `t_mono_ms`, `t_wall_ms`, `type`, `data`):
`app_start app_stop session_start session_end screen_change mode_change tap hit_quality box_placed box_moved box_resized capture_start
capture_stop analyze_result export tracking_state fps thermal error gpu_gate snapshot timeline_truncated`; other names are kept as they are.

## Files in the app (debug source set unless noted)

`beam/Timeline.kt` (events, JSON lines, debouncer, fps), `BeamPolicy.kt` (settings, upload gate), `BeamSession.kt` (session on disk),
`SessionBundle.kt` (manifest, selection, budget, ZIP), `ScreenSnap.kt` (PixelCopy + ring), `ScreenRecord.kt` (MediaProjection),
`BeamUploader.kt` (chunked upload, queue), `BeamUploadService.kt` (JobScheduler job, notification), `BeamEntries.kt` (the entry).
Main: `beam/BeamEntry.kt`. Release: `beam/BeamEntries.kt` (no-op). Debug manifest: consent activity, projection service
(`foregroundServiceType=mediaProjection`), upload job service, FOREGROUND_SERVICE(+_MEDIA_PROJECTION), POST_NOTIFICATIONS.

WorkManager is not a dependency of the project, so the background upload uses the framework JobScheduler (network constraint,
exponential backoff, resumes at the next chunk). Persisting the job across reboots would need RECEIVE_BOOT_COMPLETED; instead
Beam re-schedules it at every launch.

## Bundle ZIP

```
manifest.json            schema 1: app, version, commit, device, android_api, session_id, kind, label, reason,
                         start_wall_ms, end_wall_ms, created_wall_ms, event_total, event_counts, snapshot_count, files[], skipped[]
timeline.jsonl
snapshots/snap-<wall_ms>-<mono_ms>.jpg      (last 300)
recordings/<arcore>.mp4                     (camera video, if "Include camera video")
screen/<screen>.mp4                         (if full screen recording ran)
exports/<files>                             (OBJ, PLY, mesh, measurements.json)
logcat.txt  diagnostics.txt  crash.txt
```
`reason`: analyze_done, finish, stop, cancel, background, manual, app_background, crash. Budget 1.5 GB per bundle; when over, the
lowest-priority files are skipped and listed in `skipped` (order kept: timeline, crash, diagnostics, logcat, exports, snapshots, camera video, screen video).

## Wire contract (server: `pc-server/armeasure_pc/bundles.py`, all calls carry `Authorization: Bearer <token>`)

| call | body | answer |
|---|---|---|
| `POST /v1/dev/bundles` | `{"name","size","sha256"}` | `{"id","chunk_size","chunks","received":[..],"state"}`; the same sha256 + size resumes the same id; already finished: `state "complete"` |
| `PUT /v1/dev/bundles/{id}/chunks/{n}` | raw bytes, exactly `chunk_size` (last chunk shorter) | `{"received": n}`; 400 wrong length / index, 413 over `chunk_size`, 404 unknown id |
| `GET /v1/dev/bundles/{id}` | | `{"id","chunk_size","chunks","received":[..],"state":"uploading"|"complete"}` |
| `POST /v1/dev/bundles/{id}/complete` | | 200 `{"id","state":"complete","path"}`; 409 chunks missing; 422 sha256 mismatch (upload discarded, the phone restarts under a new id, at most 2 times) or unsafe / corrupt ZIP |

Limits: bundle at most 4 GB (413 at create), chunk 8 MB, incoming chunks older than 3 days are deleted, newest 50 unpacked bundles kept.
Unpacked to `data\devbundles\<YYYY-MM-DD>\<session-id>\` (zip-slip, absolute paths, drive letters, backslashes, symlinks and zip bombs refused).

## On the PC

```
cd pc-server
C:\venvs\armeasure-pc\Scripts\python.exe -m armeasure_pc inspect-bundle latest      # or a folder path; --no-video skips ffmpeg
```
Writes `summary.md`, `contact_sheet.jpg`, `video_frames/` (arcore_NNNN.jpg, screen_NNNN.jpg, 1 per second, via the pinned
`imageio-ffmpeg==0.6.0` bundled ffmpeg) and `timeline.html` into the bundle folder. Read `summary.md` first.
