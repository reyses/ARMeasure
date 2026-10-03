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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.arruler.ar.ArRenderer
import com.example.arruler.ar.ArSceneHost
import com.example.arruler.ar.ArSessionController
import com.example.arruler.ar.HitRanking
import com.example.arruler.ar.RecordingFiles
import com.example.arruler.ar.SurfaceKind
import com.example.arruler.ar.TrackEvent
import com.example.arruler.depth.ObjectPhase
import com.example.arruler.depth.ObjectScanController
import com.example.arruler.depth.ObjectUiState
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
import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.ObjectPlacement
import com.example.arruler.objscan.TriMesh
import com.example.arruler.processing.Backend
import com.example.arruler.processing.CloudData
import com.example.arruler.processing.JobEstimate
import com.example.arruler.processing.JobType
import com.example.arruler.processing.ObjectCardText
import com.example.arruler.processing.ObjectOutcome
import com.example.arruler.processing.PickerRows
import com.example.arruler.processing.ProcessingHub
import com.example.arruler.processing.ProcessingJob
import com.example.arruler.processing.ProcessingState
import com.example.arruler.processing.ProcessingUi
import com.example.arruler.processing.ProcessingUiText
import com.example.arruler.processing.ResultJson
import com.example.arruler.processing.ResultPackage
import com.example.arruler.processing.ScanCardText
import com.example.arruler.processing.UserPref
import com.example.arruler.processing.ObjectQuality as ProcQuality
import com.example.arruler.objscan.ObjectQuality as ScanQuality
import com.example.arruler.nav.Screen
import com.example.arruler.plan.ExportFormat
import com.example.arruler.plan.PlanShare
import com.example.arruler.store.PlanFrame
import com.example.arruler.store.Project
import com.example.arruler.store.ProjectRepository
import com.example.arruler.store.RoomCapture
import com.example.arruler.scan3d.MeshExport
import com.example.arruler.scan3d.MeshIo
import com.example.arruler.scan3d.MeshShare
import com.example.arruler.scan3d.ObjectSummary
import com.example.arruler.scan3d.Scan3DViewer
import com.example.arruler.scan3d.ScanFiles
import com.example.arruler.scan3d.ScanSnapshot
import com.example.arruler.scan3d.SnapshotPlane
import com.example.arruler.depth.PlaneKind
import com.example.arruler.scan3d.scansRoot
import com.example.arruler.ui.AreaControls
import com.example.arruler.ui.ArRulerTheme
import com.example.arruler.ui.CardAction
import com.example.arruler.ui.ConfirmDialog
import com.example.arruler.ui.ControlsBar
import com.example.arruler.ui.DepthConfidenceOverlay
import com.example.arruler.ui.SurfacesControl
import com.example.arruler.ui.MeasureOverlay
import com.example.arruler.ui.ObjectActions
import com.example.arruler.ui.ObjectControls
import com.example.arruler.ui.PlanScreen
import com.example.arruler.ui.ProcessingCard
import com.example.arruler.ui.QualityPickerDialog
import com.example.arruler.ui.ResultCard
import com.example.arruler.ui.SettingsScreen
import com.example.arruler.ui.pcStatusLine
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
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    // ---- OBJECT mode / processing ----
    private lateinit var objectScan: ObjectScanController
    private lateinit var hub: ProcessingHub
    private var picker by mutableStateOf<PickerRequest?>(null)

    /** Progress of the running job (object mesh, or a room scan sent to the PC); null when idle. */
    private var procUi by mutableStateOf<ProcessingUi?>(null)
    private var procJob: Job? = null
    private var objResult by mutableStateOf<ObjectResultState?>(null)
    private var scanPcResult by mutableStateOf<ScanPcResult?>(null)

    /** Question shown before a PC upload on mobile data; the lambda re-runs the job confirmed. */
    private var confirmUpload by mutableStateOf<PendingUpload?>(null)

    /** True while a separate activity (the Google QR scanner) needs the camera: holds the AR view paused. */
    private var arPaused by mutableStateOf(false)
    private var renderedBox: ObjectBox? = null
    private var domeShown = false
    private var lastDomeMs = 0L

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
        objectScan = ObjectScanController(lifecycleScope)
        hub = ProcessingHub(this, lifecycleScope)
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                objectScan.state.collect { renderObject(it) }
            }
        }

        setContent {
            val state by session.state.collectAsState()
            val objState by objectScan.state.collectAsState()
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
                    ArSceneHost(ar, renderer, Modifier.fillMaxSize(), paused = screen is Screen.Scan3D || arPaused)
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
                                AppMode.OBJECT -> {
                                    ObjectControls(objState, state.unit, procUi, objectActions)
                                    objResult?.let { r ->
                                        if (objState.phase == ObjectPhase.RESULT) ObjectResultCard(r, state.unit)
                                    }
                                }
                                else -> {}
                            }
                            if (appMode == AppMode.SCAN) {
                                procUi?.let { ui ->
                                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 260.dp, start = 12.dp, end = 12.dp)) {
                                        ProcessingCard(ui, ::onCancelJob)
                                    }
                                }
                                scanPcResult?.let { r ->
                                    ResultCard(
                                        "Scanned room (${r.result.stats.backend})",
                                        ScanCardText.lines(state.unit, r.result),
                                        listOfNotNull(
                                            if (r.planes.isNotEmpty() || r.mesh != null) CardAction("View 3D", onClick = { onScanPcView3D(r) }) else null,
                                            CardAction("Close", onClick = { scanPcResult = null }),
                                        ),
                                    )
                                }
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
                                onSettings = { screen = Screen.Settings },
                            )
                        }
                        Screen.Settings -> Surface(Modifier.fillMaxSize()) {
                            val pref by hub.pref.collectAsState()
                            val profile by hub.profile.collectAsState()
                            val speedTesting by hub.speedTesting.collectAsState()
                            val pairing by hub.pairing.collectAsState()
                            val pcStatus by hub.status.collectAsState()
                            SettingsScreen(
                                pref = pref, onPref = hub::setPref,
                                profile = profile, speedTesting = speedTesting, onSpeedTest = hub::runSpeedTest,
                                pairing = pairing, status = pcStatus,
                                onScanQr = ::scanPairingQr, onPairText = ::onPairText,
                                onUnpair = hub::unpair, onTest = hub::testConnection,
                                onBack = { screen = Screen.Projects },
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
                    when (picker) {
                        PickerRequest.Object -> {
                            val pcStatus by hub.status.collectAsState()
                            val paired by hub.pairing.collectAsState()
                            LaunchedEffect(Unit) { if (hub.pairing.value != null) hub.testConnection() }
                            val options = remember(pcStatus, paired) { hub.qualities(JobEstimate(pointCount = OBJECT_ESTIMATE_POINTS)) }
                            val rows = remember(options) { PickerRows.forObject(options) }
                            QualityPickerDialog(
                                title = "Scan quality",
                                rows = rows,
                                initialId = remember(rows) { PickerRows.defaultId(rows, options) },
                                pcLine = pcStatusLine(paired != null, pcStatus),
                                confirmLabel = "Start",
                                onConfirm = { id -> picker = null; onObjectQualityChosen(id) },
                                onDismiss = { picker = null },
                            )
                        }
                        PickerRequest.ScanAnalyze -> {
                            val pcStatus by hub.status.collectAsState()
                            val paired by hub.pairing.collectAsState()
                            LaunchedEffect(Unit) { if (hub.pairing.value != null) hub.testConnection() }
                            val rows = remember(pcStatus, paired) {
                                PickerRows.forScan(hub.pcAvailable, scan.stats.value.voxels, hub.profile.value.tier)
                            }
                            QualityPickerDialog(
                                title = "Analyze the scan",
                                rows = rows,
                                initialId = remember(rows) { UserPref.AUTO.name },
                                pcLine = pcStatusLine(paired != null, pcStatus),
                                confirmLabel = "Analyze",
                                onConfirm = { id -> picker = null; runScanAnalyze(UserPref.valueOf(id)) },
                                onDismiss = { picker = null },
                            )
                        }
                        null -> {}
                    }
                    confirmUpload?.let { p ->
                        ConfirmDialog(
                            "Upload on mobile data?", p.message, "Upload",
                            { confirmUpload = null; p.run() },
                            { confirmUpload = null; onUploadDeclined() },
                        )
                    }
                }
                BackHandler(enabled = screen != Screen.Measure) {
                    screen = when (screen) {
                        is Screen.Plan -> Screen.Projects
                        Screen.Settings -> Screen.Projects
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
        if (appMode == AppMode.OBJECT) return onObjectFrame()
        if (appMode == AppMode.SHAPES) return
        areaFrame()
        if (session.state.value.phase == Phase.MEASURING) {
            ar.hitTestCenter()?.let { session.setLive(it.point) }
        }
    }

    private fun onArTap(x: Float, y: Float) {
        if (screen != Screen.Measure) return
        if (appMode == AppMode.SCAN) return
        if (appMode == AppMode.OBJECT) {
            ar.hitTest(x, y)?.let(::onObjectTap)
            return
        }
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
        if (appMode == AppMode.OBJECT) {
            ar.hitTestCenter()?.let(::onObjectTap)
            return
        }
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
        if (appMode == AppMode.SCAN || appMode == AppMode.OBJECT) return
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
        if (mode.needsDepth && !ar.setDepthEnabled(true)) return toast("Depth could not be enabled")
        if (appMode == AppMode.SCAN || appMode == AppMode.OBJECT) cancelJob()
        if (appMode == AppMode.SCAN) {
            scan.reset()
            ar.setDepthEnabled(false)
            scanSaved = false
            scanPcResult = null
        }
        if (appMode == AppMode.OBJECT) resetObject()
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
        cancelJob()
        scanPcResult = null
        scan.reset()
        scanSaved = false
        renderer.renderCloud(emptyList())
    }

    /** Analyze asks where to run (this phone, the PC, automatic) before it does anything. */
    private fun onScanAnalyze() {
        picker = PickerRequest.ScanAnalyze
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
            AppMode.OBJECT -> onObjectReset()
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

    // ---- OBJECT mode ----

    private val objectActions by lazy {
        ObjectActions(
            onResize = objectScan::resize,
            onRotate = { objectScan.rotate() },
            onFit = ::onObjectFit,
            onStart = { if (objectScan.phase == ObjectPhase.PAUSED) objectScan.start() else picker = PickerRequest.Object },
            onPause = objectScan::pause,
            onFinish = ::onObjectFinish,
            onReset = ::onObjectReset,
            onCancelJob = ::onCancelJob,
        )
    }

    /** Per AR frame: depth into the Fit / capture clouds, camera into the dome, dome markers at 2 Hz. */
    private fun onObjectFrame() {
        val frame = ar.latestFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        val t = frame.camera.pose
        objectScan.onFrame(frame, Vec3(t.tx(), t.ty(), t.tz()))
        val now = SystemClock.elapsedRealtime()
        if (now - lastDomeMs < DOME_INTERVAL_MS) return
        lastDomeMs = now
        val box = objectScan.state.value.box
        val bins = objectScan.domeBins()
        if (box == null || bins.isEmpty()) {
            if (domeShown) renderer.renderDome(MeasurePoint(0f, 0f, 0f), emptyList(), 0f)
            domeShown = false
            return
        }
        val c = box.volumeCentre()
        renderer.renderDome(
            MeasurePoint(c.x, c.y, c.z),
            bins.map { MeasurePoint(it.direction.x, it.direction.y, it.direction.z) to it.observed },
            0.5f * box.maxDimension() + 0.2f,
        )
        domeShown = true
    }

    /** The 12 box edges as thin cylinders; redrawn only when the box changes. */
    private fun renderObject(s: ObjectUiState) {
        if (appMode != AppMode.OBJECT || s.box == renderedBox) return
        renderedBox = s.box
        renderer.render(emptyList())
        renderer.renderExtra(
            s.box?.let { b ->
                ObjectPlacement.edges(b).map { (a, c) -> MeasurePoint(a.x, a.y, a.z) to MeasurePoint(c.x, c.y, c.z) }
            } ?: emptyList()
        )
    }

    private fun onObjectTap(hit: ArSessionController.SurfaceHit) {
        val phase = objectScan.phase
        if (phase != ObjectPhase.IDLE && phase != ObjectPhase.PLACED) return
        if (hit.kind != SurfaceKind.FLOOR) return toast("Tap a horizontal surface: the floor or a table top")
        haptic()
        objectScan.place(Vec3(hit.point.x, hit.point.y, hit.point.z))
    }

    private fun onObjectFit() {
        objectScan.fit { ok ->
            runOnUiThread {
                toastLong(
                    if (ok) "Box fitted to the object"
                    else "Not enough depth near the tap yet. Move the phone slowly around the object, then tap Fit again."
                )
            }
        }
    }

    /** The picker's answer: remember the job quality and start capturing with the matching voxel size. */
    private fun onObjectQualityChosen(id: String) {
        val q = runCatching { ProcQuality.valueOf(id) }.getOrNull() ?: return
        objectScan.start(if (q == ProcQuality.FINE) ScanQuality.FINE else ScanQuality.QUICK)
    }

    private fun onObjectFinish() {
        objectScan.pause()
        lifecycleScope.launch {
            val cap = objectScan.capture() ?: return@launch toast("Nothing captured yet. Move closer to the object.")
            objectScan.working()
            renderer.renderDome(MeasurePoint(0f, 0f, 0f), emptyList(), 0f)
            domeShown = false
            procUi = ProcessingUi("Preparing the scan")
            val n = cap.points.size / 3
            val job = ProcessingJob(
                type = JobType.OBJECT_MESH,
                quality = if (cap.quality == ScanQuality.FINE) ProcQuality.FINE else ProcQuality.QUICK,
                estimate = JobEstimate(pointCount = n),
                cloud = CloudData(cap.points, cap.hits, IntArray(n) { 255 }),
                workDir = hub.workDir(),
                objectBox = cap.box,
                supportPlane = cap.plane,
            )
            runObjectJob(job, confirmed = false)
        }
    }

    private fun runObjectJob(job: ProcessingJob, confirmed: Boolean) {
        procJob?.cancel()
        procJob = lifecycleScope.launch {
            hub.service().process(job, confirmed).collect { st ->
                when (st) {
                    is ProcessingState.Done -> onObjectDone(job, st)
                    is ProcessingState.Failed -> {
                        procUi = null
                        objectScan.backToPaused()
                        toastLong("Object scan failed: ${st.message}")
                    }
                    is ProcessingState.NeedsConfirmation -> {
                        procUi = null
                        confirmUpload = PendingUpload(st.decision.warning ?: "The upload is large.") { procUi = ProcessingUi("Uploading"); runObjectJob(job, true) }
                    }
                    else -> ProcessingUiText.of(st, procUi)?.let { procUi = it }
                }
            }
        }
    }

    private suspend fun onObjectDone(job: ProcessingJob, done: ProcessingState.Done) {
        val outcome = ObjectOutcome.from(done.result)
        if (outcome == null) {
            procUi = null
            objectScan.backToPaused()
            return toastLong("The result has no object dimensions")
        }
        val mesh = if (done.backend == Backend.PHONE) job.mesh else done.resultZip?.let { loadResultMesh(it) }
        objResult = ObjectResultState(outcome, mesh, null)
        procUi = null
        objectScan.showResult()
    }

    /** Reads mesh.ply (or mesh.obj) from a PC result ZIP. */
    private suspend fun loadResultMesh(zip: File): TriMesh? = withContext(Dispatchers.IO) {
        runCatching {
            ResultPackage.readEntry(zip, "mesh.ply")?.let(MeshIo::readPly)
                ?: ResultPackage.readEntry(zip, "mesh.obj")?.let { MeshIo.readObj(String(it, Charsets.UTF_8)) }
        }.getOrNull()
    }

    private fun onObjectReset() {
        cancelJob()
        resetObject()
    }

    private fun resetObject() {
        objResult = null
        objectScan.reset()
        renderedBox = null
        renderer.clear()
        domeShown = false
    }

    private fun onCancelJob() {
        cancelJob()
        if (appMode == AppMode.OBJECT) objectScan.backToPaused()
        toast("Cancelled")
    }

    private fun cancelJob() {
        procJob?.cancel()
        procJob = null
        procUi = null
        confirmUpload = null
    }

    /** The user declined the mobile-data upload: back to where they were. */
    private fun onUploadDeclined() {
        procUi = null
        if (appMode == AppMode.OBJECT) objectScan.backToPaused()
    }

    private fun summaryOf(o: ObjectOutcome) = ObjectSummary(
        o.lengthM.toFloat(), o.widthM.toFloat(), o.heightM.toFloat(),
        o.volume.low.toFloat(), o.volume.high.toFloat(), o.volume.recommended.toFloat(),
    )

    private fun objectSnapshot(r: ObjectResultState, id: String, projectId: String?, mesh: TriMesh) = ScanSnapshot(
        id, projectId, System.currentTimeMillis(), FloatArray(0), FloatArray(0), emptyList(), null,
        mesh, ScanSnapshot.KIND_OBJECT, summaryOf(r.outcome),
    )

    private fun onObjectView3D(r: ObjectResultState) {
        val mesh = r.mesh ?: return toast("No mesh was produced")
        val id = r.savedId ?: java.util.UUID.randomUUID().toString()
        scan3dSnapshot = objectSnapshot(r, id, null, mesh)
        scan3dReturnTo = Screen.Measure
        screen = Screen.Scan3D(id)
    }

    private fun onObjectShare(r: ObjectResultState, format: MeshExport) {
        val mesh = r.mesh ?: return toast("No mesh was produced")
        try {
            MeshShare.share(this, mesh, format)
        } catch (e: Exception) {
            toast("Share failed: ${e.message}")
        }
    }

    /** Saves the object into the current / last-used project's scans (kind = object). */
    private fun onObjectSave(r: ObjectResultState) {
        val mesh = r.mesh ?: return toast("No mesh to save")
        if (r.savedId != null) return toast("Already saved")
        lifecycleScope.launch {
            val id = java.util.UUID.randomUUID().toString()
            val projectId = scanProjectId()
            val snap = objectSnapshot(r, id, projectId, mesh)
            withContext(Dispatchers.IO) { runCatching { ScanFiles.save(scansRoot(this@MainActivity), snap) } }
                .onSuccess {
                    objResult = r.copy(savedId = id)
                    scanListVersion++
                    toast("Saved to the project's scans")
                }
                .onFailure { toast("Could not save the object: ${it.message}") }
        }
    }

    @Composable
    private fun BoxScope.ObjectResultCard(r: ObjectResultState, units: Units) {
        val lines = ObjectCardText.lines(units, r.outcome) +
            (if (r.mesh == null) listOf("Mesh" to "not available (too sparse or too large)") else emptyList())
        ResultCard(
            "Object (${r.outcome.backend})",
            lines,
            listOfNotNull(
                if (r.mesh != null) CardAction("View 3D", onClick = { onObjectView3D(r) }) else null,
                if (r.mesh != null) CardAction(
                    "Share",
                    menu = MeshExport.entries.map { f -> f.label to { onObjectShare(r, f) } },
                ) else null,
                if (r.mesh != null) CardAction(if (r.savedId != null) "Saved" else "Save", Color(0xFF34C759), { onObjectSave(r) }) else null,
            ),
        )
    }

    // ---- processing: scan analysis on the PC, pairing ----

    /** The picker's answer for Analyze: the phone keeps the existing path, the PC gets a job. */
    private fun runScanAnalyze(pref: UserPref) {
        val n = scan.stats.value.voxels
        scanSaved = false
        scanPcResult = null
        val decision = hub.route(JobType.SCAN_ANALYZE, null, JobEstimate(pointCount = n), pref)
        if (decision.blocked || decision.backend == Backend.PHONE) {
            decision.warning?.let { toastLong(it) }
            scan.analyze()
            return
        }
        scan.pause()
        lifecycleScope.launch {
            val cloud = scan.cloudData() ?: return@launch toast("Nothing scanned yet")
            procUi = ProcessingUi("Preparing the scan")
            val job = ProcessingJob(
                type = JobType.SCAN_ANALYZE, estimate = JobEstimate(pointCount = cloud.count),
                cloud = cloud, workDir = hub.workDir(),
            )
            runScanJob(job, pref, confirmed = false)
        }
    }

    private fun runScanJob(job: ProcessingJob, pref: UserPref, confirmed: Boolean) {
        procJob?.cancel()
        procJob = lifecycleScope.launch {
            hub.service(pref).process(job, confirmed).collect { st ->
                when (st) {
                    is ProcessingState.Done -> {
                        val planes = st.resultZip?.let { loadResultPlanes(it) }.orEmpty()
                        val mesh = st.resultZip?.let { loadResultMesh(it) }
                        procUi = null
                        scanPcResult = ScanPcResult(st.result, planes, mesh)
                    }
                    is ProcessingState.Failed -> {
                        procUi = null
                        toastLong("Analysis failed: ${st.message}")
                    }
                    is ProcessingState.NeedsConfirmation -> {
                        procUi = null
                        confirmUpload = PendingUpload(st.decision.warning ?: "The upload is large.") {
                            procUi = ProcessingUi("Uploading"); runScanJob(job, pref, true)
                        }
                    }
                    else -> ProcessingUiText.of(st, procUi)?.let { procUi = it }
                }
            }
        }
    }

    /** planes.json of a PC result as viewer planes. */
    private suspend fun loadResultPlanes(zip: File): List<SnapshotPlane> = withContext(Dispatchers.IO) {
        runCatching {
            ResultPackage.readPlanes(zip)?.planes.orEmpty().mapNotNull { p ->
                val kind = runCatching { PlaneKind.valueOf(p.kind) }.getOrNull() ?: return@mapNotNull null
                if (p.normal.size < 3) return@mapNotNull null
                SnapshotPlane(
                    kind, p.normal[0], p.normal[1], p.normal[2], p.d,
                    p.outline3d.filter { it.size >= 3 }.flatMap { listOf(it[0], it[1], it[2]) }.toFloatArray(), p.inliers,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Saves the scan with the PC's planes (and mesh, when it made one) and opens the 3D viewer. */
    private fun onScanPcView3D(r: ScanPcResult) {
        lifecycleScope.launch {
            val id = java.util.UUID.randomUUID().toString()
            val snap = scan.snapshotWith(id, scanProjectId(), r.planes, r.mesh) ?: return@launch toast("Nothing scanned yet")
            withContext(Dispatchers.IO) { runCatching { ScanFiles.save(scansRoot(this@MainActivity), snap) } }
                .onFailure { toast("Could not save the scan: ${it.message}") }
            scan3dSnapshot = snap
            scan3dReturnTo = Screen.Measure
            scanListVersion++
            screen = Screen.Scan3D(id)
        }
    }

    /** Pairing text from the QR or the paste field; returns an error message or null. */
    private fun onPairText(text: String): String? = hub.pair(text).fold(
        onSuccess = { toast("Paired with ${it.name.ifEmpty { "the PC" }}"); null },
        onFailure = { it.message ?: "Pairing failed" },
    )

    /**
     * Scans the PC's QR with the Google code scanner. It runs in its own activity and needs the camera,
     * so the AR view is held paused first (the same gating as the 3D viewer) and released afterwards.
     */
    private fun scanPairingQr() {
        arPaused = true
        lifecycleScope.launch {
            delay(CAMERA_RELEASE_MS)
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(this@MainActivity, options).startScan()
                .addOnSuccessListener { code ->
                    arPaused = false
                    val raw = code.rawValue
                    if (raw == null) toast("The QR code is empty") else onPairText(raw)?.let { toastLong(it) }
                }
                .addOnCanceledListener { arPaused = false }
                .addOnFailureListener { e ->
                    arPaused = false
                    toastLong("QR scanner unavailable (${e.message}). Paste the pairing JSON instead.")
                }
        }
    }

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

    private fun toastLong(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PLAYBACK_URI = "playback_uri"
        private const val PREFS = "plan"
        private const val KEY_LAST_PROJECT = "last_project"

        /** Dome markers are redrawn at most this often (2 Hz). */
        private const val DOME_INTERVAL_MS = 500L

        /** Pause between holding the AR view paused and starting the QR scanner, so ARCore has let go of the camera. */
        private const val CAMERA_RELEASE_MS = 600L

        /** Point count assumed by the quality picker before a capture exists (50-250 k voxels, see docs/OBJECT_SCAN.md). */
        private const val OBJECT_ESTIMATE_POINTS = 100_000
    }
}

/** What the quality / backend picker is open for. */
private sealed interface PickerRequest {
    data object Object : PickerRequest
    data object ScanAnalyze : PickerRequest
}

/** A finished object job: numbers, the mesh (null when none was built) and the saved scan id once saved. */
private data class ObjectResultState(val outcome: ObjectOutcome, val mesh: TriMesh?, val savedId: String?)

/** A room-scan result that came back (from the PC, or the phone through the service) with optional planes and mesh. */
private class ScanPcResult(val result: ResultJson, val planes: List<SnapshotPlane>, val mesh: TriMesh?)

/** A PC upload waiting for the user's yes (mobile data, over 20 MB). */
private class PendingUpload(val message: String, val run: () -> Unit)
