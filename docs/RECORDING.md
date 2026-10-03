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

## Playback mechanism (SceneView 4.52.0)

SceneView 4.52.0 (`io.github.sceneview:arsceneview:4.52.0`, Compose `ARSceneView`) owns the ARCore
session: it creates it, resumes and pauses it with the lifecycle, and closes it when the composable
leaves the composition. It exposes no public pause/resume (`ARCore.session` is `internal`), but it has
a supported playback parameter, which is what the app uses:

- `ARSceneView(playbackDatasetUri = uri)` calls `session.setPlaybackDatasetUri(uri)` right after the
  session is created and before its first `resume()`. The value is read once, at first composition.
  Only `content://` and `file://` URIs are accepted (anything else throws at composition; the app
  refuses them first and toasts "Could not open recording").
- Switching between live and playback, or to another file, therefore rebuilds the view:
  `ArSceneHost` wraps `ARSceneView` in `key(controller.request)` and `ArSessionController.startPlayback`
  swaps in a new request. This closes the old ARCore session and creates a new one, so the measurement
  and its anchors are cleared first (`MainActivity.launchPlayback`), and a recording in progress is stopped.
- Playback started before the first composition (adb extra at launch) is simply the first request, so
  there is no "pending until onSessionCreated" path any more.
- A dataset ARCore cannot open is reported through `onPlaybackFailed` and shows as `PlaybackStatus.IO_ERROR`
  (toast "Playback error"); the session then continues on the live camera.
- Recording still uses the live `Session` and per-frame `Frame` from the `onSessionCreated` /
  `onSessionUpdated` callbacks (`SessionRecorder` is unchanged). `setAutoStopOnPause(true)` and the
  `onSessionPaused` callback finalise the file when SceneView pauses the session.
- Fallback if this ever misbehaves: the `ARSession` handed to the callbacks is public, so the 2.3.0 way
  (`session.pause()`, `setPlaybackDatasetUri`, `session.resume()`) is still callable from there.

## Not yet verified on a device (as of 2026-10-03)

Everything above compiles and the pure parts are unit-tested; none of it has run on a phone yet.
First device run must confirm, in this order:

1. `setMp4DatasetUri(Uri.fromFile(...))` is accepted (ARCore needs a seekable fd). If it throws, switch to a `FileProvider` content Uri.
2. Tapping the play entry rebuilds the AR view onto the dataset (new session, no crash, no black screen, no leaked Filament engine) and a second playback request, even for the same file, restarts it.
3. Playback requested at launch (adb extra) plays from the first frame; a `file://` or bare path is accepted by `playbackDatasetUri`.
4. Hit tests and planes work during playback; `PlaybackStatus.FINISHED` fires and the badge/toast appear; a bad file gives the "Playback error" toast.
5. Auto-stop on pause finalises a playable MP4 and the JSONL track bytes are present in it.
6. `playback_uri` via `onNewIntent` on an already-running app (singleTop).
7. The one-line depth-support log (`Depth AUTOMATIC supported on this device: <bool>`), and that `FocusMode.FIXED` and vertical plane finding take effect. Note: 2.3.0 reset the focus mode to AUTO on every resume, so FIXED never applied; 4.52.0 keeps it, so focus behaviour changes.
8. Taps and the centre-of-screen hit test line up with the drawn points (hit-test pixels come from the AR surface size, `onSizeChanged`), and spheres and segments appear at the placed points.
9. Reading the track back during replay is not implemented (TODO).
