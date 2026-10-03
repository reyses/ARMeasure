# Wiring the 3D scan viewer (scan3d/)

New files only; nothing existing was edited. Four edits are needed, all small. No Gradle change (SceneView's `SceneView` composable, `Geometry`, `MeshNode` and Filament are already on the classpath via `arsceneview`).

## 1. ScanController: expose a snapshot (depth/ScanController.kt)

The cloud is private and `ScanAnalysis.Room` keeps only the RoomModel, not the planes, so add one method inside `class ScanController`:

```kotlin
/** Cloud + planes + room as an immutable snapshot (off the main thread); null when the cloud is empty. */
suspend fun snapshot(id: String, projectId: String?): com.example.arruler.scan3d.ScanSnapshot? =
    kotlinx.coroutines.withContext(Dispatchers.Default) {
        lock.withLock {
            if (cloud.count == 0) return@withLock null
            val planes = PlaneExtractor().extract(cloud.points(ScanLogic.ANALYZE_MIN_HITS))
            com.example.arruler.scan3d.ScanSnapshot.from(
                cloud, planes, RoomFromPlanes.build(planes), id, projectId,
            )
        }
    }
```

Cost: one plane extraction (the same one Analyze runs, a second or two); it holds the cloud lock meanwhile, so call it only while paused/analysed.

## 2. Screen entry (nav/Screen.kt)

```kotlin
data class Scan3D(val scanId: String) : Screen
```

## 3. MainActivity

Imports:
```kotlin
import com.example.arruler.scan3d.ScanFiles
import com.example.arruler.scan3d.ScanSnapshot
import com.example.arruler.scan3d.Scan3DViewer
import com.example.arruler.scan3d.ScanListSection
import com.example.arruler.scan3d.scansRoot
```

Field next to `screen`:
```kotlin
private var scan3dSnapshot by mutableStateOf<ScanSnapshot?>(null)
private var scanListVersion by mutableStateOf(0)   // bump after save / delete to refresh lists
```

Handler (next to `onScanAnalyze`):
```kotlin
private fun onScanView3D() {
    lifecycleScope.launch {
        val id = java.util.UUID.randomUUID().toString()
        val snap = scan.snapshot(id, lastUsedProjectId) ?: return@launch toast("Nothing scanned yet")
        withContext(Dispatchers.IO) { runCatching { ScanFiles.save(scansRoot(this@MainActivity), snap) } }
            .onFailure { toast("Could not save the scan: ${it.message}") }
        scan3dSnapshot = snap
        scanListVersion++
        screen = Screen.Scan3D(id)
    }
}
```
(`lastUsedProjectId` is the existing field; any String? works. `toast`, `lifecycleScope`, `withContext`, `Dispatchers` are already imported or trivially so.)

ScanControls: add a parameter `onView3D: () -> Unit` after `onAnalyze` and, in the pill Row, directly after the Analyze pill:
```kotlin
if (!analyzing && stats.voxels > 0) GlassPill("View 3D", Color.White) { haptic(); onView3D() }
```
and pass `onView3D = ::onScanView3D` at the call site in MainActivity (line ~198).

The `when (screen)` block, new branch:
```kotlin
is Screen.Scan3D -> Surface(Modifier.fillMaxSize()) {
    val snap = scan3dSnapshot?.takeIf { it.id == s.scanId }
        ?: remember(s.scanId) { ScanFiles.load(scansRoot(this@MainActivity), s.scanId) }
    if (snap == null) {
        LaunchedEffect(Unit) { toast("Scan not found"); screen = Screen.Measure }
    } else {
        Scan3DViewer(snap, onBack = { screen = Screen.Measure })
    }
}
```
(The `when` variable is named `s` in the existing code: `is Screen.Plan ->` uses `s.projectId`.) Make sure the existing `if (screen == Screen.Measure)` AR-overlay guard and the `BackHandler` at line ~250 treat Scan3D like the other non-Measure screens (they already do: `screen != Screen.Measure`). The viewer has its own `BackHandler` too, so the outer one never double-fires.

Two SceneView surfaces: while on Scan3D the ARCore `ArSceneView` stays composed under it. If you see two GL surfaces fighting, pause the AR session first (`scan.pause()` at the top of `onScanView3D`, and gate the AR view on `screen == Screen.Measure`, as the existing Projects screen presumably does).

## 4. ScanListSection on the Plan screen (ui/PlanScreen.kt)

Add two parameters to `PlanScreen`: `scans: List<ScanInfo>`, `onOpenScan: (String) -> Unit`, `onDeleteScan: (String) -> Unit`, and inside its scrolling column, below the rooms list:
```kotlin
ScanListSection(scans, onOpen = onOpenScan, onDelete = onDeleteScan, modifier = Modifier.padding(16.dp))
```
At the call in MainActivity's `is Screen.Plan ->` branch:
```kotlin
val scans = remember(s.projectId, scanListVersion) { ScanFiles.list(scansRoot(this@MainActivity), s.projectId) }
PlanScreen(
    ...,
    scans = scans,
    onOpenScan = { id -> scan3dSnapshot = null; screen = Screen.Scan3D(id) },
    onDeleteScan = { id -> ScanFiles.delete(scansRoot(this@MainActivity), id); scanListVersion++ },
)
```
Back from a scan opened this way goes to Measure (the viewer's `onBack`); if you want it to return to the Plan, use `onBack = { screen = scan3dReturnTo }` with a remembered previous screen.

## Notes

- Share reuses the existing FileProvider (`${packageName}.fileprovider`, `cache-path exports/`): `ScanShare` copies into `cacheDir/exports/` then fires ACTION_SEND. No manifest or file_paths change.
- Files: `filesDir/scans/<id>/cloud.ply` + `planes.json`. `ScanFiles.exportObj(snapshot, file)` writes surfaces.obj on demand (Share > Surfaces does it).
- The red-to-green ramp lives in `QualityRamp` (red 0, yellow 0.5, green 1). The repo's AR overlay colour code was not in the merged tree when this was written; if the overlay uses a different ramp, change `QualityRamp.r/g` (PLY colours and viewer both follow, and `rampEnds` in ScanFilesTest pins the three anchor colours).
