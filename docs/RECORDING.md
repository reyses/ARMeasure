# Recording and playback

Record a walk through a room on the phone, replay the MP4 later without the camera
(hit tests and plane detection run on the recorded frames).

## Record
- Tap the red dot (top right). It becomes a square with mm:ss while recording. Tap again to stop.
- Recording also stops automatically when the app pauses (`setAutoStopOnPause(true)`).
- Output: `Android/data/com.example.arruler/files/recordings/room_<yyyyMMdd_HHmmss>.mp4`
- Measurement points are written to a custom track (id `5f0c2a4e-8d3b-4c1a-9e57-0a1b2c3d4e5f`,
  mime `application/x-arruler-events+jsonl`), one JSON line per point:
  `{"type":"point","i":0,"x":0.1,"y":0.0,"z":-1.2,"ts":1700000000123}` (x,y,z in meters, ts in ms epoch).
  Reading it back on replay is a TODO.

## Pull a recording
    adb pull /sdcard/Android/data/com.example.arruler/files/recordings/

## Play back
- In the app: tap the small play triangle next to the record dot, or long-press the record dot,
  and pick an MP4. A PLAYBACK badge shows while a dataset is loaded; a toast shows at the end.
- From adb (push the file first, then):

      adb push room_20261003_140509.mp4 /sdcard/Android/data/com.example.arruler/files/recordings/
      adb shell am start -n com.example.arruler/.MainActivity --es playback_uri file:///sdcard/Android/data/com.example.arruler/files/recordings/room_20261003_140509.mp4

  A bare absolute path (`--es playback_uri /sdcard/...`) is accepted too. The activity is
  `singleTop`, so the command also works on a running app.

## Not yet verified on a device (as of 2026-10-03)

Everything above compiles and the pure parts are unit-tested; none of it has run on a phone yet.
First device run must confirm, in this order:

1. `setMp4DatasetUri(Uri.fromFile(...))` is accepted (ARCore needs a seekable fd). If it throws, switch to a `FileProvider` content Uri.
2. `pause() -> setPlaybackDatasetUri -> resume()` on SceneView's own session works and SceneView does not re-resume or call `update()` while paused.
3. Playback requested before the session exists (first launch via the adb extra) is applied in `onSessionCreated`.
4. Hit tests and planes work during playback; `PlaybackStatus.FINISHED` fires and the badge/toast appear.
5. Auto-stop on pause finalises a playable MP4 and the JSONL track bytes are present in it.
6. `playback_uri` via `onNewIntent` on an already-running app (singleTop).
7. The one-line depth-support log (`Depth AUTOMATIC supported on this device: <bool>`), and that `FocusMode.FIXED` and vertical plane finding take effect.
8. Reading the track back during replay is not implemented (TODO).
