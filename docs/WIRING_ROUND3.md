# Round 3 wiring: hook points for the ML, texture and spin work

**Status (2026-10-03): every hook below is filled.** `autoBoxProvider` = `MlAutoBoxProvider` (ML Kit, falls back to `DepthFitAutoBox`),
`resultAnnotators` = `ShapeAnnotator`, `captureObservers` = `WalkKeyframes`, `textureProviders` = `KeyframeTextureProvider`,
`spinCapture` = `AndroidSpinCapture` (its `onFrame` is called from `onObjectFrame` while the spin stage runs; `SpinCapture` gained
`betweenTurns`, `canEndTurn`, `nextTurn()`). DETAILED, SPIN and HYBRID send a photo job built by `PhotogrammetryJobBuilder` /
`SpinJobBuilder` through `ProcessingJob.prebuilt`; the PC's `mesh.obj` + `texture.png` come back through `PcTexturedResult`.
`ViewerHooks` is no longer used: `Scan3DViewer(..., textured = TexturedMeshData)` draws the baked mesh (the BakedMesh has duplicated
seam vertices, so it cannot share the grey mesh's vertex count). Capture video: one MP4 across the walk and spin phases of a Hybrid.

All hooks live in `MainActivity` (section "HOOKS") as one line each. Everything below is wired and called today with an
empty default, so a feature is switched on by filling that one line.

## 1. Tap the object: `AutoBoxProvider` (ML drone)

- Interface: `objscan/AutoBox.kt`, `suspend fun propose(tapX, tapY, frameContext): ObjectBox?`.
- `FrameContext` carries the support plane, the tap hit on the object, the tap dropped on the plane, the camera position,
  the view size, a `DepthPointSource` (the pre-scan cloud around the tap, still filling while the call waits) and an
  optional `FrameImage` (null today; an ML provider that needs the camera image adds its capture in
  `MainActivity.onObjectTapAt`, which runs on the main thread inside the AR frame).
- Built-in: `DepthFitAutoBox` (the old Fit seeded at the tap: depth points above the plane within 40 cm, oriented
  minimum-area footprint for the yaw).
- **Swap = one line**: `private val autoBoxProvider: AutoBoxProvider by lazy { DepthFitAutoBox() }` becomes
  `... { MlAutoBoxProvider(this) }`. A provider may fall back to `DepthFitAutoBox` itself when it returns null.
- Returning null keeps the default 25 cm box and shows "Could not see the object's shape".

## 2. Primitive fit on the result card (ML drone)

- Interface: `objscan/ObjectHooks.kt`, `ResultAnnotator.annotate(ResultContext, Units): List<Pair<String,String>>`.
- Register in `MainActivity.resultAnnotators`. Lines are appended to the object result card, stored with the saved
  object (`ScanSnapshot.extras`, "label: value") and written into measurements.txt / measurements.json.
- Example lines: `"Shape" to "Looks like a cylinder: r 9.8 cm, h 20.1 cm"`, `"Formula volume" to "6.05 L"`.
- `ResultContext` has the mesh, the captured points (empty for some PC results), the box, the support plane and the
  measured `ObjectSummary`.

## 3. Keyframe capture during the walk (texture drone)

- Interface: `depth/ObjectCaptureHooks.kt`, `CaptureObserver` (all methods default to empty).
- Register in `MainActivity.captureObservers`. Calls, all on the main thread:
  - `onCaptureStart(box, plane, resumed)`: after the quality picker's Start and after Resume.
  - `onFrame(frame)`: every AR frame while the phase is CAPTURING and not in the spin stage. The `Frame` is valid only
    inside the call.
  - `onCapturePause()`, `onCaptureFinish()` (Finish pressed, before the result is computed), `onCaptureReset()`
    (Reset, or leaving OBJECT mode).
- The ARCore MP4 of the capture is separate (see section 6).

## 4. Textured mesh in the viewer and the export (texture drone)

- `TextureProvider.bake(ResultContext): TexturedObject?` registered in `MainActivity.textureProviders` (first non-null
  wins), called once the grey mesh exists.
- `TexturedObject(obj, mtl, png, bestPhoto)`: the OBJ text must contain the line `mtllib mesh.mtl`, the MTL text the line
  `map_Kd texture.png`; the exporter rewrites both to the human-readable names it writes
  (`<name> mesh.mtl`, `<name> texture.png`). `bestPhoto` becomes the gallery thumbnail (`thumbnailOverride`, see
  `ObjectThumbnail.save(root, id, mesh, thumbnailOverride)`).
- Stored with the saved object in `scans/<id>/textured/{mesh.obj,mesh.mtl,texture.png}`
  (`ScanFiles.saveTextured / loadTextured`).
- Exports: `PublicExporter.exportObject` writes `<name> mesh.obj` + `.mtl` + `<name> texture.png` when a textured object is
  given, else the grey OBJ. The share sheet (`ObjectShare`, format OBJ) sends the three files.
- Viewer: `scan3d/Scan3DViewer.kt` `ViewerHooks.meshUv` (two floats per vertex of `snapshot.mesh`) and
  `ViewerHooks.meshMaterial` (a Filament material whose base colour is the texture). Set both once at start-up; while
  they are null the viewer shows the grey mesh. The detail page uses the same viewer in `compact` mode.

## 5. Spin and Hybrid capture (texture drone writes the policy, PC drone the processing)

UI states exist: after "Looks right" three cards (Walk around, Spin (phone on a stand), Hybrid (walk once, then spin)) and a
"What's best?" sheet (`objscan/CaptureMode.kt`, text in `CaptureModes.HELP_LINES`). Spin and Hybrid are disabled with
"needs your PC" when no PC is paired, and with "coming soon" while `spinCapture.available` is false.

- Interface `SpinCapture` (`objscan/CaptureMode.kt`), instance `MainActivity.spinCapture` (default `NoSpinCapture`):
  - `startSpinCapture(box, plane, hybrid)` / `stopSpinCapture()`: called when the user picks Spin, when a Hybrid walk is
    done and the spin starts (button "Walk done: start the spin"), and from Done / Reset / leaving the mode.
  - `phoneMoved: StateFlow<Boolean>`: true shows the red banner "The phone moved. Put it back on the stand and keep it still."
  - `progress: StateFlow<SpinProgress?>`: `SpinProgress(turn, turns, photos)` renders "Turn 1 of 2 · 34 photos".
  - `available`: set true when the implementation exists.
- UI state is `ObjectUiState.captureMode` (WALK / SPIN / HYBRID) and `spinning` (the spin stage). In the spin stage the
  walk depth capture is off; Hybrid keeps its walk cloud and processes it at Done as for Walk.
- Where the spin result goes: `MainActivity.onSpinFinished()` (SPIN only; the PC job for the photos is started there).
  For Hybrid, `onObjectFinish` stops the spin photos and then runs the normal walk job; hand the spin photos to the PC
  job in that function too.

## 6. Capture video

- Settings "Record capture video" (default on, "about 30-60 MB per minute"), `AppSettings.recordCaptureVideo`.
- `MainActivity.startCaptureVideo()` starts `ar.recorder` (the ARCore MP4 recorder) when the capture starts (walk, hybrid
  walk, spin) and reuses a recording that is already running; `stopCaptureVideo()` stops it at Finish, Done or Reset.
- Saving the object copies the MP4 into `scans/<id>/capture.mp4` and exports `<name> capture.mp4` to
  `Download/ARMeasure/<project>/`. The detail page action "Play capture video" opens the MediaStore Uri (API 29+), else
  the private copy through the FileProvider, with `video/mp4`. Whether the ARCore MP4 plays in an ordinary video player
  is device-only (it carries ARCore's extra tracks).

## 7. Saving and the public folder

- `store/PublicExport.kt`: names, layout, measurements texts and `PublicExporter` (JVM-tested through `PublicSink`).
- `store/PublicStorage.kt`: Android side. API 29+ `MediaStore.Downloads` with `RELATIVE_PATH` and `IS_PENDING`;
  API 24-28 plain files in the public Downloads directory with `WRITE_EXTERNAL_STORAGE` (manifest `maxSdkVersion 28`),
  requested at the first save. Every save in the app calls `MainActivity.publicExport(project) { ... }` when
  Settings "Copy saves to Downloads/ARMeasure" is on (default), then the snackbar offers Open folder and Share.
- Open folder: `ACTION_VIEW` of the folder's document Uri in the external storage provider
  (`DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/ARMeasure/<project>")`,
  type `vnd.android.document/directory`); falls back to the Downloads list (`DownloadManager.ACTION_VIEW_DOWNLOADS`),
  then to launching the Files app; else a toast with the path.

## 8. Saved objects

- Gallery: Plan screen tab "Objects", and a top-level "Objects" entry on Projects (`Screen.Objects`). Detail page
  `Screen.ObjectDetail` (`ui/ObjectDetailScreen.kt`). Objects are scans of kind `object`; name, notes, method and extras
  are in planes.json, the thumbnail in `thumb.png`, the video in `capture.mp4`.
- Thumbnail: orthographic shaded projection drawn with `android.graphics` into 512 px (`scan3d/ObjectThumbnail.kt`,
  projection maths JVM-tested). Filament has no offscreen snapshot in SceneView 4.52 without owning a SwapChain, so the
  render is not done with it. Old saves get their thumbnail on first display.
