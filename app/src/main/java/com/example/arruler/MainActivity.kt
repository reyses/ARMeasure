package com.example.arruler

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.arruler.ar.ArRenderer
import com.example.arruler.ar.ArSceneHost
import com.example.arruler.ar.ArSessionController
import com.example.arruler.ar.HitRanking
import com.example.arruler.ar.RecordingFiles
import com.example.arruler.ar.TrackEvent
import com.example.arruler.depth.ScanAnalysis
import com.example.arruler.depth.ScanController
import com.example.arruler.depth.floorPolygon3d
import com.example.arruler.measure.AppMode
import com.example.arruler.measure.MeasureMode
import com.example.arruler.measure.MeasurePoint
import com.example.arruler.measure.MeasureState
import com.example.arruler.measure.MeasurementSession
import com.example.arruler.measure.Phase
import com.example.arruler.measure.Units
import com.example.arruler.measure.shapes.ShapeCapture
import com.example.arruler.measure.shapes.ShapeKind
import com.example.arruler.measure.shapes.ShapePreview
import com.example.arruler.measure.toVec3
import com.example.arruler.nav.Screen
import com.example.arruler.plan.ExportFormat
import com.example.arruler.plan.PlanShare
import com.example.arruler.store.PlanFrame
import com.example.arruler.store.Project
import com.example.arruler.store.ProjectRepository
import com.example.arruler.store.RoomCapture
import com.example.arruler.scan3d.Scan3DViewer
import com.example.arruler.scan3d.ScanFiles
import com.example.arruler.scan3d.ScanSnapshot
import com.example.arruler.scan3d.scansRoot
import com.example.arruler.ui.AreaControls
import com.example.arruler.ui.ArRulerTheme
import com.example.arruler.ui.ControlsBar
import com.example.arruler.ui.DepthConfidenceOverlay
import com.example.arruler.ui.SurfacesControl
import com.example.arruler.ui.MeasureOverlay
import com.example.arruler.ui.PlanScreen
import com.example.arruler.ui.PlaybackBadge
import com.example.arruler.ui.ProjectChoice
import com.example.arruler.ui.ProjectsButton
import com.example.arruler.ui.ProjectsScreen
import com.example.arruler.ui.RecordControl
import com.example.arruler.ui.ScanControls
import com.example.arruler.ui.ShapeControls
import com.example.arruler.ui.SaveRoomDraft
import com.example.arruler.ui.SaveRoomPill
import com.example.arruler.ui.SaveRoomSheet
import com.google.ar.core.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Thin wiring: AR session + measurement state + renderer + Compose UI + navigation. */
class MainActivity : AppCompatActivity() {

    private lateinit var ar: ArSessionController
    private val renderer = ArRenderer()
    private val session = MeasurementSession()
    private var pointCount = 0

    /** True once a point of the current measurement came from a depth or feature-point hit (noisier than a plane). */
    private var lowConfPlaced by mutableStateOf(false)

    // ---- SHAPES / SCAN ----
    private var appMode by mutableStateOf(AppMode.DISTANCE)
    private var capture by mutableStateOf(ShapeCapture(ShapeKind.BOX))
    private lateinit var scan: ScanController
    private var scanSaved by mutableStateOf(false)

    // ---- plan / projects ----
    private lateinit var repo: ProjectRepository
    private var screen by mutableStateOf<Screen>(Screen.Measure)
    private var saveDraft by mutableStateOf<SaveRoomDraft?>(null)
    private var lastUsedProjectId by mutableStateOf<String?>(null)

    // ---- 3D scan viewer ----
    private var scan3dSnapshot by mutableStateOf<ScanSnapshot?>(null)
    private var scanListVersion by mutableStateOf(0) // bump after save / delete to refresh scan lists

    /** Where Back from the 3D viewer goes: Measure for a fresh scan, the Plan for one opened from its list. */
    private var scan3dReturnTo by mutableStateOf<Screen>(Screen.Measure)

    /** Outline + height point of the room last saved, so the Save pill hides until the measurement changes. */
    private var savedFor by mutableStateOf<Pair<List<MeasurePoint>, MeasurePoint?>?>(null)

    /** Shared plan frame of the current AR session: set by the first saved room, dropped with the world. */
    private var planFrame: PlanFrame? = null

    /** Bound to the composition's view while it exists (see [setContent] below). */
    private var haptic: () -> Unit = {}

    private val pickPlayback = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) launchPlayback(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repo = ProjectRepository(File(filesDir, "projects"))
        lastUsedProjectId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_PROJECT, null)

        ar = ArSessionController(this)
        ar.onFrame = ::onArFrame
        ar.onTap = ::onArTap
        ar.onSurfaces = renderer::renderSurfaces
        scan = ScanController(lifecycleScope)
        handlePlaybackIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var last = PlaybackStatus.NONE
                ar.playbackStatus.collect { st ->
                    if (st == PlaybackStatus.FINISHED && last != st) toast("Playback finished")
                    if (st == PlaybackStatus.IO_ERROR && last != st) toast("Playback error")
                    last = st
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect { s ->
                    renderMeasureState(s)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                scan.preview.collect { if (appMode == AppMode.SCAN) renderer.renderCloud(it) }
            }
        }

        setContent {
            val state by session.state.collectAsState()
            val hasSurface by ar.hasSurface.collectAsState()
            val centerHit by ar.centerHit.collectAsState()
            val depthHeat by ar.depthHeat.collectAsState()
            var showPlanes by rememberSaveable { mutableStateOf(false) }
            var showDepth by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(showPlanes) { ar.setSurfacesEnabled(showPlanes) }
            LaunchedEffect(showDepth) { ar.setDepthHeatEnabled(showDepth) }
            val recState by ar.recorder.state.collectAsState()
            val playback by ar.playbackStatus.collectAsState()
            val projects by repo.projects.collectAsState()
            val scanAvailable by ar.depthSupported.collectAsState()
            val scanning by scan.scanning.collectAsState()
            val analyzing by scan.analyzing.collectAsState()
            val scanStats by scan.stats.collectAsState()
            val scanAnalysis by scan.analysis.collectAsState()
            val view = LocalView.current
            DisposableEffect(view) {
                haptic = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
                onDispose { haptic = {} }
            }
            ArRulerTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    // The AR view stays composed under the other screens so the ARCore session,
                    // anchors and the shared plan frame survive a visit to Projects/Plan. Under the 3D
                    // viewer it is held paused (same composition, session paused, no drawing) so the two
                    // GL surfaces never render together and the anchors still survive.
                    ArSceneHost(ar, renderer, Modifier.fillMaxSize(), paused = screen is Screen.Scan3D)
                    if (screen == Screen.Measure && showDepth) DepthConfidenceOverlay(depthHeat)
                    if (screen == Screen.Measure) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                            val liveLow = state.phase == Phase.MEASURING && centerHit?.let { HitRanking.isLowConfidence(it.quality) } == true
                            MeasureOverlay(state, hasSurface, centerHit, lowConfPlaced || liveLow)
                            SurfacesControl(showPlanes, showDepth, { showPlanes = it }, { showDepth = it })
                            RecordControl(
                                state = recState,
                                onToggle = ar.recorder::toggle,
                                onPlayback = ::pickPlaybackFile,
                            )
                            if (playback != PlaybackStatus.NONE) PlaybackBadge(playback == PlaybackStatus.FINISHED)
                            ControlsBar(
                                state = state,
                                onToggleUnit = session::nextUnit,
                                onMainButton = ::onMainButton,
                                onClear = ::onClear,
                            )
                            AreaControls(state, appMode, scanAvailable, ::onSetAppMode, ::onAreaClose, ::onAreaUndo, ::onAreaHeight)
                            when (appMode) {
                                AppMode.SHAPES -> ShapeControls(
                                    capture, state.unit, ::onShapeKind, ::onShapeUndo, ::onShapeDone, ::onShapeReset,
                                )
                                AppMode.SCAN -> ScanControls(
                                    scanning = scanning,
                                    analyzing = analyzing,
                                    stats = scanStats,
                                    analysis = scanAnalysis,
                                    savable = scanAnalysis is ScanAnalysis.Room && !scanSaved,
                                    units = state.unit,
                                    onStartPause = ::onScanStartPause,
                                    onReset = ::onScanReset,
                                    onAnalyze = ::onScanAnalyze,
                                    onView3D = ::onScanView3D,
                                    onSave = ::onSaveScanRoom,
                                )
                                else -> {}
                            }
                            ProjectsButton { screen = Screen.Projects }
                            if (appMode == AppMode.AREA && state.closed && !state.heightActive &&
                                savedFor != Pair(state.points, state.heightPoint)
                            ) {
                                SaveRoomPill(::onSaveRoomPill)
                            }
                        }
                    }
                    when (val s = screen) {
                        Screen.Measure -> {}
                        Screen.Projects -> Surface(Modifier.fillMaxSize()) {
                            ProjectsScreen(
                                projects = projects,
                                units = state.unit,
                                onBack = { screen = Screen.Measure },
                                onOpen = { screen = Screen.Plan(it) },
                                onCreate = { name -> guarded { repo.createProject(name) } },
                                onRename = { id, name -> guarded { repo.renameProject(id, name) } },
                                onDelete = { id -> guarded { repo.deleteProject(id) } },
                            )
                        }
                        is Screen.Plan -> Surface(Modifier.fillMaxSize()) {
                            val project = projects.firstOrNull { it.id == s.projectId }
                            val scans = remember(s.projectId, scanListVersion) {
                                ScanFiles.list(scansRoot(this@MainActivity), s.projectId)
                            }
                            PlanScreen(
                                project = project,
                                units = state.unit,
                                onBack = { screen = Screen.Projects },
                                onRenameRoom = { rid, name -> guarded { repo.renameRoom(s.projectId, rid, name) } },
                                onDeleteRoom = { rid -> guarded { repo.deleteRoom(s.projectId, rid) } },
                                onExport = { format, angles ->
                                    project?.let { exportPlan(it, format, state.unit, angles) }
                                },
                                scans = scans,
                                onOpenScan = { id -> scan3dSnapshot = null; scan3dReturnTo = s; screen = Screen.Scan3D(id) },
                                onDeleteScan = { id -> ScanFiles.delete(scansRoot(this@MainActivity), id); scanListVersion++ },
                            )
                        }
                        is Screen.Scan3D -> Surface(Modifier.fillMaxSize()) {
                            val snap = scan3dSnapshot?.takeIf { it.id == s.scanId }
                                ?: remember(s.scanId) { ScanFiles.load(scansRoot(this@MainActivity), s.scanId) }
                            if (snap == null) {
                                LaunchedEffect(Unit) { toast("Scan not found"); screen = scan3dReturnTo }
                            } else {
                                Scan3DViewer(snap, onBack = { screen = scan3dReturnTo })
                            }
                        }
                    }
                    saveDraft?.let { draft ->
                        SaveRoomSheet(
                            draft = draft,
                            projects = projects,
                            lastUsedId = lastUsedProjectId,
                            units = state.unit,
                            onSave = { name, choice, snap -> onSaveRoom(draft, name, choice, snap) },
                            onDismiss = { saveDraft = null },
                        )
                    }
                }
                BackHandler(enabled = screen != Screen.Measure) {
                    screen = when (screen) {
                        is Screen.Plan -> Screen.Projects
                        is Screen.Scan3D -> scan3dReturnTo
                        else -> Screen.Measure
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        ar.recorder.stop()
        renderer.release()
        ar.releaseAnchors()
        super.onDestroy()
    }

    private fun onArFrame() {
        if (screen != Screen.Measure) return
        if (appMode == AppMode.SCAN) {
            ar.latestFrame?.let { scan.onFrame(it, SystemClock.elapsedRealtime()) }
            return
        }
        if (appMode == AppMode.SHAPES) return
        areaFrame()
        if (session.state.value.phase == Phase.MEASURING) {
            ar.hitTestCenter()?.let { session.setLive(it.point) }
        }
    }

    private fun onArTap(x: Float, y: Float) {
        if (screen != Screen.Measure) return
        if (appMode == AppMode.SCAN) return
        if (appMode == AppMode.SHAPES) {
            ar.hitTest(x, y)?.let(::onShapeTap)
            return
        }
        val s = session.state.value
        if (s.mode == MeasureMode.AREA) return
        if (s.phase != Phase.MEASURING || s.points.isEmpty()) return
        val hit = ar.hitTest(x, y) ?: return
        haptic()
        val p = anchorAt(hit)
        logPoint(p)
        session.addPoint(p)
    }

    private fun onMainButton() {
        if (appMode == AppMode.SCAN) return onScanStartPause()
        if (appMode == AppMode.SHAPES) {
            ar.hitTestCenter()?.let(::onShapeTap)
            return
        }
        if (session.state.value.mode == MeasureMode.AREA) return onAreaShutter()
        if (session.state.value.phase == Phase.MEASURING) {
            session.stop()
            return
        }
        if (session.state.value.points.isNotEmpty()) clearAll()
        val hit = ar.hitTestCenter() ?: return
        haptic()
        val p = anchorAt(hit)
        pointCount = 0
        logPoint(p)
        session.start(p)
    }

    private fun anchorAt(hit: ArSessionController.SurfaceHit): MeasurePoint {
        if (HitRanking.isLowConfidence(hit.quality)) lowConfPlaced = true
        return ar.createAnchor(hit)
    }

    private fun clearAll() {
        lowConfPlaced = false
        ar.releaseAnchors()
        session.clear()
    }

    // ---- AREA mode (additive) ----

    private fun renderMeasureState(s: MeasureState) {
        if (appMode == AppMode.SHAPES) return renderShapes()
        if (appMode == AppMode.SCAN) return
        if (s.mode == MeasureMode.AREA) {
            renderer.render(s.displayPoints, closed = s.closed, final = s.closed)
            val hp = s.heightPoint
            val area = s.areaMeasurement
            renderer.renderExtra(if (s.closed && hp != null && area != null) listOf(area.centroid() to hp) else emptyList())
        } else {
            renderer.render(s.displayPoints, final = s.phase == Phase.FINISHED)
            renderer.renderExtra(emptyList())
        }
    }

    private fun areaFrame() {
        val s = session.state.value
        if (s.mode == MeasureMode.AREA && s.heightActive) {
            ar.hitTestCenter()?.let { session.setHeightLive(it.point) }
        }
    }

    private fun onSetAppMode(mode: AppMode) {
        if (appMode == mode) return
        if (mode == AppMode.SCAN && !ar.setDepthEnabled(true)) return toast("Depth could not be enabled")
        if (appMode == AppMode.SCAN) {
            scan.reset()
            ar.setDepthEnabled(false)
            scanSaved = false
        }
        clearAll()
        renderer.clear()
        appMode = mode
        session.setMode(mode.sessionMode)
        capture = ShapeCapture(capture.kind)
        if (mode == AppMode.SHAPES) renderShapes()
    }

    // ---- SHAPES mode ----

    private fun onShapeTap(hit: ArSessionController.SurfaceHit) {
        haptic()
        capture = capture.add(anchorAt(hit))
        renderShapes()
    }

    private fun renderShapes() {
        renderer.render(capture.points)
        renderer.renderExtra(ShapePreview.segments(capture))
    }

    private fun onShapeKind(k: ShapeKind) { capture = ShapeCapture(k); renderShapes() }
    private fun onShapeUndo() { capture = capture.undo(); renderShapes() }
    private fun onShapeDone() { capture = capture.closeBase(); renderShapes() }
    private fun onShapeReset() { capture = capture.reset(); renderShapes() }

    // ---- SCAN mode ----

    private fun onScanStartPause() { if (scan.scanning.value) scan.pause() else scan.start() }

    private fun onScanReset() {
        scan.reset()
        scanSaved = false
        renderer.renderCloud(emptyList())
    }

    private fun onScanAnalyze() {
        scanSaved = false
        scan.analyze()
    }

    /**
     * Saves the scan (cloud + planes + room) into the current / last-used project, creating "My place"
     * when there is none, and opens the 3D viewer. The cloud and the AR session stay intact for Back.
     */
    private fun onScanView3D() {
        scan.pause()
        lifecycleScope.launch {
            val id = java.util.UUID.randomUUID().toString()
            val projectId = scanProjectId()
            val snap = scan.snapshot(id, projectId) ?: return@launch toast("Nothing scanned yet")
            withContext(Dispatchers.IO) { runCatching { ScanFiles.save(scansRoot(this@MainActivity), snap) } }
                .onFailure { toast("Could not save the scan: ${it.message}") }
            scan3dSnapshot = snap
            scan3dReturnTo = Screen.Measure
            scanListVersion++
            screen = Screen.Scan3D(id)
        }
    }

    /** The project a new scan belongs to: the last-used one if it still exists, else a new "My place". */
    private fun scanProjectId(): String? = try {
        lastUsedProjectId?.takeIf { repo.project(it) != null }
            ?: repo.createProject("My place").id.also {
                lastUsedProjectId = it
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_PROJECT, it).apply()
            }
    } catch (e: IOException) {
        toast("Storage error: ${e.message}")
        null
    }

    /** Lifts the scanned floor polygon back to 3D at floorY and reuses the AREA save path. */
    private fun onSaveScanRoom() {
        val room = scan.analysis.value as? ScanAnalysis.Room ?: return
        val captured = RoomCapture.capture(room.model.floorPolygon3d(), planFrame)
        saveDraft = SaveRoomDraft(captured, room.heightM)
    }

    private fun onClear() {
        when (appMode) {
            AppMode.SHAPES -> onShapeReset()
            AppMode.SCAN -> onScanReset()
            else -> clearAll()
        }
    }

    private fun onAreaShutter() {
        val s = session.state.value
        val hit = ar.hitTestCenter() ?: return
        when {
            s.heightActive -> { haptic(); session.commitHeight(anchorAt(hit)) }
            s.closed -> { clearAll(); haptic(); session.start(anchorAt(hit)) }
            s.phase == Phase.MEASURING && s.points.isNotEmpty() -> { haptic(); session.addPoint(anchorAt(hit)) }
            else -> { clearAll(); haptic(); session.start(anchorAt(hit)) }
        }
    }

    private fun onAreaClose() = session.closePolygon()

    private fun onAreaUndo() = session.undo()

    private fun onAreaHeight() = session.startHeight()

    // ---- save room / projects / export ----

    /** Builds the save draft for the closed outline; the shared frame is only committed on save. */
    private fun onSaveRoomPill() {
        val s = session.state.value
        val area = s.areaMeasurement ?: return
        if (!s.closed) return
        val captured = RoomCapture.capture(area.points.map { it.toVec3() }, planFrame)
        saveDraft = SaveRoomDraft(captured, s.volumeMeasurement?.height)
    }

    private fun onSaveRoom(draft: SaveRoomDraft, name: String, choice: ProjectChoice, snap: Boolean) {
        try {
            val project: Project = when (choice) {
                is ProjectChoice.Existing -> repo.project(choice.id) ?: return toast("Project not found")
                is ProjectChoice.New -> repo.createProject(choice.name)
            }
            val room = draft.captured.toSavedRoom(repo.newRoomId(), name, snap, draft.heightM, System.currentTimeMillis())
            if (!repo.addRoom(project.id, room)) return toast("Project not found")
            planFrame = draft.captured.frame
            lastUsedProjectId = project.id
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_PROJECT, project.id).apply()
            val s = session.state.value
            savedFor = s.points to s.heightPoint
            if (appMode == AppMode.SCAN) scanSaved = true
            saveDraft = null
            toast("Saved to ${project.name}")
        } catch (e: IOException) {
            toast("Could not save: ${e.message}")
        }
    }

    private fun exportPlan(project: Project, format: ExportFormat, unit: Units, angles: Boolean) {
        try {
            val file = PlanShare.export(this, project, format, unit, angles)
            PlanShare.share(this, file, format, project.name)
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    /** Runs a repository write, turning a disk error into a toast. */
    private fun guarded(block: () -> Unit) {
        try { block() } catch (e: IOException) { toast("Storage error: ${e.message}") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlaybackIntent(intent)
    }

    /** adb: am start -n com.example.arruler/.MainActivity --es playback_uri <uri or /abs/path> */
    private fun handlePlaybackIntent(intent: Intent?) {
        val raw = RecordingFiles.normalizePlaybackUri(intent?.getStringExtra(EXTRA_PLAYBACK_URI)) ?: return
        intent?.removeExtra(EXTRA_PLAYBACK_URI)
        launchPlayback(Uri.parse(raw))
    }

    private fun pickPlaybackFile() = pickPlayback.launch(arrayOf("video/mp4"))

    /** The playback session is a new world frame, so the measurement, its anchors and the plan frame are dropped. */
    private fun launchPlayback(uri: Uri) {
        onSetAppMode(AppMode.DISTANCE)
        clearAll()
        planFrame = null
        screen = Screen.Measure
        if (!ar.startPlayback(uri)) toast("Could not open recording")
    }

    private fun logPoint(p: MeasurePoint) {
        ar.recorder.log(TrackEvent.PointPlaced(pointCount++, p.x, p.y, p.z, System.currentTimeMillis()))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PLAYBACK_URI = "playback_uri"
        private const val PREFS = "plan"
        private const val KEY_LAST_PROJECT = "last_project"
    }
}
