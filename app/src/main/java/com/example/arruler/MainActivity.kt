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
import androidx.compose.runtime.produceState
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
import com.example.arruler.depth.CaptureObserver
import com.example.arruler.ml.ImageOrientation
import com.example.arruler.ml.MlAutoBox
import com.example.arruler.ml.MlAutoBoxProvider
import com.example.arruler.ml.MlFrameInput
import com.example.arruler.ml.MlObjectDetector
import com.example.arruler.ml.ShapeAnnotator
import com.example.arruler.ml.captureMlTap
import com.example.arruler.objscan.AutoBoxProvider
import com.example.arruler.objscan.CaptureMode
import com.example.arruler.objscan.DepthFitAutoBox
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.TexturedMeshData
import com.example.arruler.texture.AndroidSpinCapture
import com.example.arruler.texture.KeyframeLoader
import com.example.arruler.texture.KeyframeRecord
import com.example.arruler.texture.KeyframeTextureProvider
import com.example.arruler.texture.KeyframeStore
import com.example.arruler.texture.PcTexturedResult
import com.example.arruler.texture.PhotogrammetryJobBuilder
import com.example.arruler.texture.SpinJobBuilder
import com.example.arruler.texture.SpinJobInput
import com.example.arruler.texture.WalkKeyframes
import com.example.arruler.texture.meshData
import com.example.arruler.texture.CaptureMode as SpinJobMode
import com.example.arruler.objscan.ObjectPlacement
import com.example.arruler.objscan.PlaneRaycast
import com.example.arruler.objscan.ResultAnnotator
import com.example.arruler.objscan.ResultContext
import com.example.arruler.objscan.SpinCapture
import com.example.arruler.objscan.SupportPlane
import com.example.arruler.objscan.SupportPlanePick
import com.example.arruler.objscan.TextureProvider
import com.example.arruler.objscan.TexturedObject
import com.example.arruler.objscan.TriMesh
import com.example.arruler.ar.RecordingState
import com.example.arruler.store.AppSettings
import com.example.arruler.store.ExportNames
import com.example.arruler.store.ExportResult
import com.example.arruler.store.MeasurementsText
import com.example.arruler.store.ObjectExportInfo
import com.example.arruler.store.PublicExporter
import com.example.arruler.store.PublicStorage
import com.example.arruler.scan3d.ObjectShare
import com.example.arruler.scan3d.ObjectShareFormat
import com.example.arruler.scan3d.ObjectThumbnail
import com.example.arruler.ui.KeepAwake
import com.example.arruler.ui.ObjectDetailScreen
import com.example.arruler.ui.ObjectFormat
import com.example.arruler.ui.ObjectHudInfo
import com.example.arruler.ui.ObjectItem
import com.example.arruler.ui.ObjectSaveSheet
import com.example.arruler.ui.ObjectTouchLayer
import com.example.arruler.ui.ObjectsScreen
import com.example.arruler.ui.SaveNote
import com.example.arruler.ui.SaveSnackbar
import com.example.arruler.processing.Backend
import com.example.arruler.processing.CloudData
import com.example.arruler.processing.JobEstimate
import com.example.arruler.processing.JobType
import com.example.arruler.processing.ObjectCardText
import com.example.arruler.processing.ObjectOutcome
import com.example.arruler.processing.PackageMeta
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
import java.util.concurrent.atomic.AtomicReference

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

    // ---- HOOKS for the ML / texture / spin work (docs/WIRING_ROUND3.md), all filled ----

    /** HOOK ML: ML Kit finds the object under the tap; the depth-only fit is the fallback (nothing found, an error, no depth in time). */
    private val mlDetectorLazy = lazy { MlObjectDetector() }

    /** The frame capture of the last tap, made on the main thread and taken once by the provider on its own thread. */
    private val mlTapInput = AtomicReference<MlFrameInput?>(null)
    private var mlLabel by mutableStateOf<String?>(null)
    private var sensorOrientationOf: Pair<String, Int>? = null

    private val autoBoxProvider: AutoBoxProvider by lazy {
        MlAutoBoxProvider(MlAutoBox(mlDetectorLazy.value), DepthFitAutoBox(), { mlTapInput.getAndSet(null) }, ::showMlLabel)
    }

    /** HOOK SPIN: the spin capture (phone on a stand): photos by image change in the box region, two turns, phone-moved check. */
    private val spinCapture: AndroidSpinCapture by lazy { AndroidSpinCapture(File(cacheDir, "keyframes")) }

    /** HOOK TEXTURE: the walk-around photos, taken during the walk capture and stopped at Finish. */
    private val walkKeyframes: WalkKeyframes by lazy { WalkKeyframes(File(cacheDir, "keyframes")) }
    private val captureObservers: List<CaptureObserver> by lazy { listOf(walkKeyframes) }

    /** HOOK ML: the 'Shape' line on the result card, the detail page and in the exports. */
    private val resultAnnotators = listOf<ResultAnnotator>(ShapeAnnotator())

    /** HOOK TEXTURE: bakes the photo texture of the grey mesh (atlas on MID / HIGH, vertex colours on LOW) once it exists. */
    private val textureProviders: List<TextureProvider> by lazy {
        listOf(KeyframeTextureProvider(walkKeyframes, { hub.profile.value.tier }, ::photoRotationDeg) { text, f -> procUi = ProcessingUi(text, f) })
    }

    private lateinit var settings: AppSettings
    private var pendingMode = CaptureMode.WALK
    private var chosenQuality = ProcQuality.QUICK

    /** Box, support plane and photos of the capture that is being processed (set at Finish / Done, cleared at Reset). */
    private var pendingBox: ObjectBox? = null
    private var pendingPlane: SupportPlane? = null
    private var pendingPhotos: Pair<File, List<KeyframeRecord>>? = null
    private var captureVideo: File? = null
    private var dragOffset: Pair<Float, Float>? = null
    private var objSaveOpen by mutableStateOf(false)
    private var saveNote by mutableStateOf<SaveNote?>(null)
    private var objectReturnTo by mutableStateOf<Screen>(Screen.Projects)
    private var pendingStorageAction: (() -> Unit)? = null

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (granted) action?.invoke() else toastLong("Storage permission denied: the copy to Downloads was skipped")
    }

    // ---- plan / projects ----
    private lateinit var repo: ProjectRepository
    private var screen by mutableStateOf<Screen>(Screen.Measure)
    private var saveDraft by mutableStateOf<SaveRoomDraft?>(null)
    private var lastUsedProjectId by mutableStateOf<String?>(null)

    // ---- 3D scan viewer ----
    private var scan3dSnapshot by mutableStateOf<ScanSnapshot?>(null)

    /** The photo-textured mesh of a result being viewed before it is saved; null loads the saved one from disk (objects) or shows grey. */
    private var scan3dTextured by mutableStateOf<TexturedMeshData?>(null)
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
        settings = AppSettings(this)
        com.example.arruler.gpu.GpuGate.init(this, settings.useGpu.value)
        lastUsedProjectId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_PROJECT, null)

        ar = ArSessionController(this)
        ar.onFrame = ::onArFrame
        ar.onTap = ::onArTap
        ar.onSurfaces = renderer::renderSurfaces
        scan = ScanController(lifecycleScope)
        objectScan = ObjectScanController(lifecycleScope)
        hub = ProcessingHub(this, lifecycleScope)
        com.example.arruler.devlink.DevEntries.entry.onCreate(application)
        lifecycleScope.launch { hub.pairing.collect { com.example.arruler.devlink.DevEntries.entry.onPairing(this@MainActivity, it) } }
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
            // The screen stays on while the Measure (AR) screen shows and while a processing card runs.
            val keepOn = KeepAwake.shouldKeepOn(screen, procUi != null)
            DisposableEffect(view, keepOn) {
                view.keepScreenOn = keepOn
                onDispose { view.keepScreenOn = false }
            }
            val pairedPc by hub.pairing.collectAsState()
            val phoneMoved by spinCapture.phoneMoved.collectAsState()
            val spinProgress by spinCapture.progress.collectAsState()
            val betweenTurns by spinCapture.betweenTurns.collectAsState()
            val canEndTurn by spinCapture.canEndTurn.collectAsState()
            val copyToDownloads by settings.copyToDownloads.collectAsState()
            val recordVideo by settings.recordCaptureVideo.collectAsState()
            val useGpu by settings.useGpu.collectAsState()
            val gpuRecord by com.example.arruler.gpu.GpuGate.verification.collectAsState()
            ArRulerTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    // The AR view stays composed under the other screens so the ARCore session,
                    // anchors and the shared plan frame survive a visit to Projects/Plan. Under the 3D
                    // viewer it is held paused (same composition, session paused, no drawing) so the two
                    // GL surfaces never render together and the anchors still survive.
                    ArSceneHost(ar, renderer, Modifier.fillMaxSize(), paused = screen is Screen.Scan3D || screen is Screen.ObjectDetail || screen == Screen.Diagnostics || arPaused)
                    if (screen == Screen.Measure && showDepth) DepthConfidenceOverlay(depthHeat)
                    if (screen == Screen.Measure && appMode == AppMode.OBJECT &&
                        (objState.phase == ObjectPhase.IDLE || objState.phase == ObjectPhase.PLACED)
                    ) {
                        ObjectTouchLayer(
                            onTap = ::onObjectTapAt,
                            onDragStart = ::onObjectDragStart,
                            onDrag = ::onObjectDrag,
                            onDragEnd = ::onObjectDragEnd,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
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
                                    ObjectControls(
                                        objState, state.unit, procUi,
                                        ObjectHudInfo(pairedPc != null, spinCapture.available, phoneMoved, spinProgress, betweenTurns, canEndTurn, mlLabel),
                                        objectActions,
                                    )
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
                                onObjects = { screen = Screen.Objects },
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
                                copyToDownloads = copyToDownloads, onCopyToDownloads = settings::setCopyToDownloads,
                                recordVideo = recordVideo, onRecordVideo = settings::setRecordCaptureVideo,
                                useGpu = useGpu, onUseGpu = settings::setUseGpu,
                                gpuStatus = com.example.arruler.gpu.GpuGate.statusLine(useGpu, gpuRecord),
                                onDiagnostics = { screen = Screen.Diagnostics },
                                onBack = { screen = Screen.Projects },
                            )
                        }
                        Screen.Diagnostics -> Surface(Modifier.fillMaxSize()) {
                            com.example.arruler.ui.DiagnosticsScreen(onBack = { screen = Screen.Settings })
                        }
                        is Screen.Plan -> Surface(Modifier.fillMaxSize()) {
                            val project = projects.firstOrNull { it.id == s.projectId }
                            val scans = remember(s.projectId, scanListVersion) {
                                ScanFiles.list(scansRoot(this@MainActivity), s.projectId).filter { it.kind != ScanSnapshot.KIND_OBJECT }
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
                                onOpenScan = { id -> scan3dSnapshot = null; scan3dTextured = null; scan3dReturnTo = s; screen = Screen.Scan3D(id) },
                                onDeleteScan = { id -> ScanFiles.delete(scansRoot(this@MainActivity), id); scanListVersion++ },
                                objects = remember(s.projectId, scanListVersion, projects) { objectItems(s.projectId, projects) },
                                onOpenObject = { id -> objectReturnTo = s; screen = Screen.ObjectDetail(id) },
                                onExportProject = { project?.let { p -> publicExport(p.name) { it.exportPlan(p, state.unit) } } },
                            )
                        }
                        is Screen.Scan3D -> Surface(Modifier.fillMaxSize()) {
                            val snap = scan3dSnapshot?.takeIf { it.id == s.scanId }
                                ?: remember(s.scanId) { ScanFiles.load(scansRoot(this@MainActivity), s.scanId) }
                            if (snap == null) {
                                LaunchedEffect(Unit) { toast("Scan not found"); screen = scan3dReturnTo }
                            } else {
                                val tex = scan3dTextured
                                    ?: if (snap.kind == ScanSnapshot.KIND_OBJECT) rememberSavedTextured(s.scanId) else null
                                Scan3DViewer(snap, onBack = { screen = scan3dReturnTo }, textured = tex)
                            }
                        }
                        Screen.Objects -> Surface(Modifier.fillMaxSize()) {
                            val items = remember(scanListVersion, projects) { objectItems(null, projects) }
                            ObjectsScreen(
                                items = items, units = state.unit,
                                onBack = { screen = Screen.Projects },
                                onOpen = { id -> objectReturnTo = Screen.Objects; screen = Screen.ObjectDetail(id) },
                            )
                        }
                        is Screen.ObjectDetail -> Surface(Modifier.fillMaxSize()) {
                            ObjectDetailHost(s.scanId, projects, state.unit)
                        }
                    }
                    if (objSaveOpen) objResult?.let { r ->
                        ObjectSaveSheet(
                            summary = ObjectFormat.dims(state.unit, summaryOf(r.outcome)) + ", " + ObjectFormat.volume(state.unit, r.outcome.volume.recommended.toFloat()),
                            projects = projects,
                            lastUsedId = lastUsedProjectId,
                            existingNames = { choice -> objectNames((choice as? ProjectChoice.Existing)?.id) },
                            onSave = { name, choice -> objSaveOpen = false; onObjectSave(r, name, choice) },
                            onDismiss = { objSaveOpen = false },
                        )
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
                            val rows = remember(options) { PickerRows.forObject(options, photoCapture = true) }
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
                    SaveSnackbar(saveNote, ::onSnackOpenFolder, ::onSnackShare) { saveNote = null }
                }
                BackHandler(enabled = screen != Screen.Measure) {
                    screen = when (screen) {
                        is Screen.Plan -> Screen.Projects
                        Screen.Settings -> Screen.Projects
                        Screen.Diagnostics -> Screen.Settings
                        is Screen.Scan3D -> scan3dReturnTo
                        Screen.Objects -> Screen.Projects
                        is Screen.ObjectDetail -> objectReturnTo
                        else -> Screen.Measure
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        ar.recorder.stop()
        if (mlDetectorLazy.isInitialized()) mlDetectorLazy.value.close()
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
        if (appMode == AppMode.OBJECT) return
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
            val (w, h) = ar.viewSize
            onObjectTapAt(w / 2f, h / 2f)
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
            scan3dTextured = null
            scan3dReturnTo = Screen.Measure
            scanListVersion++
            exportScanCopy(snap)
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
            onScale = objectScan::scale,
            onFit = ::onObjectFit,
            onChooseMode = ::onChooseCaptureMode,
            onBeginHybridSpin = ::onBeginHybridSpin,
            onSpinNextTurn = spinCapture::nextTurn,
            onResume = ::onObjectResume,
            onPause = ::onObjectPause,
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
        val st = objectScan.state.value
        if (st.phase == ObjectPhase.CAPTURING) {
            if (st.spinning) spinCapture.onFrame(frame) else captureObservers.forEach { it.onFrame(frame) }
        }
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

    /**
     * 'Tap the object': the tap hit gives a point on the object, the plane under it (table or floor) is the support
     * plane, and [autoBoxProvider] fits a box aligned to the object. Without a provider result the default box stays.
     */
    private fun onObjectTapAt(x: Float, y: Float) {
        val phase = objectScan.phase
        if (phase != ObjectPhase.IDLE && phase != ObjectPhase.PLACED) return
        val hit = ar.hitTest(x, y) ?: return toast("Point at the object so the camera can see its surface")
        val tap = hit.point.toVec3()
        val planeY = SupportPlanePick.pick(tap, ar.supportPlaneCandidates(hit.point))
            ?: tap.y.takeIf { hit.kind == SurfaceKind.FLOOR }
            ?: return toast("Show the table or floor the object stands on, then tap again")
        val cam = ar.cameraPosition()?.toVec3() ?: return
        val (w, h) = ar.viewSize
        haptic()
        val plane = SupportPlane.horizontal(planeY)
        captureMlTapInput(plane)
        mlLabel = null
        objectScan.tapObject(tap, plane, cam, x, y, w, h, autoBoxProvider) { found ->
            runOnUiThread {
                snapHaptic()
                if (!found) toastLong("Could not see the object's shape. Check the box, or tap Adjust to size it.")
            }
        }
    }

    /** Display rotation as the Surface.ROTATION_* constant (0 when the view has no display yet). */
    private fun displayRotation(): Int = window?.decorView?.display?.rotation ?: 0

    /** SENSOR_ORIENTATION of the live camera (cached per camera id); 90, the usual value, while there is no session. */
    private fun sensorOrientation(): Int {
        val id = ar.cameraId() ?: return 90
        sensorOrientationOf?.takeIf { it.first == id }?.let { return it.second }
        return MlObjectDetector.sensorOrientation(this, id).also { sensorOrientationOf = id to it }
    }

    /** Clockwise degrees that turn a sensor-orientation camera photo upright for the display (gallery photo). */
    private fun photoRotationDeg(): Int =
        ImageOrientation.rotationDegrees(sensorOrientation(), ImageOrientation.surfaceRotationDegrees(displayRotation()))

    /** Copies the camera image of the tap's frame for the ML provider (main thread, inside the frame); null leaves the depth fit alone. */
    private fun captureMlTapInput(plane: SupportPlane) {
        mlTapInput.set(null)
        val frame = ar.latestFrame ?: return
        mlTapInput.set(runCatching { captureMlTap(frame, mlDetectorLazy.value, plane, displayRotation(), sensorOrientation()) }.getOrNull())
    }

    /** Shows the ML Kit label of the tapped object ('Home good · 82 %') for a few seconds; null clears it. Any thread. */
    private fun showMlLabel(label: String?) {
        runOnUiThread {
            mlLabel = label
            if (label != null) lifecycleScope.launch {
                delay(ML_LABEL_MS)
                if (mlLabel == label) mlLabel = null
            }
        }
    }

    /** Drag on the screen moves the box along its support plane: the touch ray meets the plane, the grab offset keeps the box from jumping. */
    private fun onObjectDragStart(x: Float, y: Float) {
        dragOffset = null
        if (objectScan.phase != ObjectPhase.PLACED) return
        val box = objectScan.state.value.box ?: return
        val hit = ar.rayAt(x, y)?.let { PlaneRaycast.intersectHorizontal(it, box.centre.y) } ?: return
        dragOffset = ObjectPlacement.grabOffset(box, hit)
    }

    private fun onObjectDrag(x: Float, y: Float) {
        val off = dragOffset ?: return
        val box = objectScan.state.value.box ?: return
        val hit = ar.rayAt(x, y)?.let { PlaneRaycast.intersectHorizontal(it, box.centre.y) } ?: return
        objectScan.moveBox(ObjectPlacement.dragMove(box, off, hit).centre)
    }

    private fun onObjectDragEnd() {
        if (dragOffset != null) { dragOffset = null; snapHaptic() }
    }

    /** A firmer tick for a snap (the box landing on the object, the end of a drag). */
    private fun snapHaptic() {
        window?.decorView?.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
        )
    }

    // ---- capture modes, video, hooks ----

    private fun onChooseCaptureMode(mode: CaptureMode) {
        pendingMode = mode
        when (mode) {
            CaptureMode.WALK, CaptureMode.HYBRID -> picker = PickerRequest.Object
            CaptureMode.SPIN -> {
                val box = objectScan.state.value.box ?: return
                objectScan.startSpin()
                startCaptureVideo()
                pendingPhotos = null
                spinCapture.startSpinCapture(box, SupportPlane.horizontal(box.centre.y), hybrid = false)
            }
        }
    }

    /** HYBRID: the walk is done, the phone goes on its stand and the spin photos start. */
    private fun onBeginHybridSpin() {
        val box = objectScan.state.value.box ?: return
        objectScan.beginHybridSpin()
        spinCapture.startSpinCapture(box, SupportPlane.horizontal(box.centre.y), hybrid = true)
    }

    private fun onObjectResume() {
        val box = objectScan.state.value.box
        objectScan.start()
        if (box != null) captureObservers.forEach { it.onCaptureStart(box, SupportPlane.horizontal(box.centre.y), resumed = true) }
    }

    private fun onObjectPause() {
        objectScan.pause()
        captureObservers.forEach { it.onCapturePause() }
    }

    /** Starts the ARCore MP4 recording of the capture when 'Record capture video' is on (reuses a recording already running). */
    private fun startCaptureVideo() {
        captureVideo = null
        if (!settings.recordCaptureVideo.value) return
        if (ar.recorder.state.value !is RecordingState.Recording) ar.recorder.start()
        captureVideo = (ar.recorder.state.value as? RecordingState.Recording)?.file
    }

    private fun stopCaptureVideo() {
        if (captureVideo != null && ar.recorder.state.value is RecordingState.Recording) ar.recorder.stop()
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
        val box = objectScan.state.value.box ?: return
        chosenQuality = q
        pendingPhotos = null
        // DETAILED and the walk of a HYBRID still collect depth for the coverage dome; the cloud is only used by the phone path and the hybrid ZIP
        objectScan.start(if (q == ProcQuality.FINE) ScanQuality.FINE else ScanQuality.QUICK, pendingMode)
        startCaptureVideo()
        captureObservers.forEach { it.onCaptureStart(box, SupportPlane.horizontal(box.centre.y), resumed = false) }
    }

    /** SPIN: Done. The spin photos (both turns) go to the PC as one photo job with box masks. */
    private fun onSpinFinished() {
        spinCapture.stopSpinCapture()
        stopCaptureVideo()
        val box = objectScan.state.value.box ?: return
        val plane = SupportPlane.horizontal(box.centre.y)
        lifecycleScope.launch {
            val dir = spinCapture.dir
            val recs = spinCapture.records()
            if (dir == null || recs.size < WalkKeyframes.MIN_PHOTOS) {
                return@launch toastLong("Only ${recs.size} photos were taken. Turn the object slowly with the phone still, then press Done.")
            }
            pendingPhotos = dir to recs
            val meta = hub.packageMeta(ProcQuality.DETAILED, box, plane)
            startPhotoJob(box, plane, photoJobOf(dir, recs.size, meta) { dest -> SpinJobBuilder.build(dest, SpinJobInput(SpinJobMode.SPIN, dir, box, plane, meta)) })
        }
    }

    /** A photo job (PHOTOGRAMMETRY, DETAILED) whose ZIP is written by [build]; the upload size is the sum of the JPEGs in [dir]. */
    private fun photoJobOf(dir: File, count: Int, meta: PackageMeta, build: (File) -> Unit): ProcessingJob {
        val bytes = dir.listFiles { f -> f.name.endsWith(".jpg") }?.sumOf { it.length() } ?: 0L
        return ProcessingJob(
            type = JobType.PHOTOGRAMMETRY, quality = ProcQuality.DETAILED,
            estimate = JobEstimate(imageCount = count, imageBytes = bytes), workDir = hub.workDir(),
            objectBox = meta.box, supportPlane = meta.supportPlane, prebuilt = build,
        )
    }

    /** Shows the working state and sends a photo job (DETAILED walk, SPIN, HYBRID) to the PC. */
    private fun startPhotoJob(box: ObjectBox, plane: SupportPlane, job: ProcessingJob) {
        pendingBox = box
        pendingPlane = plane
        objectScan.working()
        renderer.renderDome(MeasurePoint(0f, 0f, 0f), emptyList(), 0f)
        domeShown = false
        procUi = ProcessingUi("Preparing the photos")
        runObjectJob(job, confirmed = false)
    }

    private fun onObjectFinish() {
        val before = objectScan.state.value
        objectScan.pause()
        if (before.captureMode == CaptureMode.SPIN) return onSpinFinished()
        if (before.spinning) spinCapture.stopSpinCapture()
        stopCaptureVideo()
        captureObservers.forEach { it.onCaptureFinish() }
        val box = before.box ?: return
        val plane = SupportPlane.horizontal(box.centre.y)
        lifecycleScope.launch {
            when {
                before.captureMode == CaptureMode.HYBRID -> finishHybrid(box, plane)
                chosenQuality == ProcQuality.DETAILED -> finishDetailed(box, plane)
                else -> finishDepthScan(box, plane)
            }
        }
    }

    /** QUICK / FINE: the depth cloud goes to the phone (or the PC on a LOW phone) as an object_mesh job. */
    private suspend fun finishDepthScan(box: ObjectBox, plane: SupportPlane) {
        val cap = objectScan.capture() ?: return toast("Nothing captured yet. Move closer to the object.")
        pendingBox = box
        pendingPlane = plane
        pendingPhotos = null
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
            objectBox = box,
            supportPlane = plane,
        )
        runObjectJob(job, confirmed = false)
    }

    /** DETAILED: the walk-around photos with their ARCore poses go to the PC's photogrammetry (the phone makes no mesh). */
    private suspend fun finishDetailed(box: ObjectBox, plane: SupportPlane) {
        val dir = walkKeyframes.dir
        val recs = walkKeyframes.records()
        if (dir == null || recs.size < WalkKeyframes.MIN_PHOTOS) {
            return toastLong("Only ${recs.size} photos were taken. Walk slowly around the object, 30 cm or more away, then Finish.")
        }
        pendingPhotos = dir to recs
        val meta = hub.packageMeta(ProcQuality.DETAILED, box, plane)
        startPhotoJob(box, plane, photoJobOf(dir, recs.size, meta) { dest -> PhotogrammetryJobBuilder.build(dir, dest, meta) })
    }

    /** HYBRID: one ZIP with the spin photos + masks, the walk photos and the walk depth cloud. */
    private suspend fun finishHybrid(box: ObjectBox, plane: SupportPlane) {
        val spinDir = spinCapture.dir
        val spinRecs = spinCapture.records()
        val walkDir = walkKeyframes.dir
        val walkRecs = walkKeyframes.records()
        val cap = objectScan.capture()
        if (spinDir == null || spinRecs.size < WalkKeyframes.MIN_PHOTOS) {
            return toastLong("Only ${spinRecs.size} spin photos were taken. Turn the object slowly with the phone still, then press Done.")
        }
        if (walkDir == null || walkRecs.size < WalkKeyframes.MIN_PHOTOS || cap == null) {
            return toastLong("The walk-around part has too little: ${walkRecs.size} photos. Reset and walk around the object first.")
        }
        pendingPhotos = spinDir to spinRecs
        val meta = hub.packageMeta(ProcQuality.DETAILED, box, plane)
        val cloud = CloudData(cap.points, cap.hits, IntArray(cap.points.size / 3) { 255 })
        val bytes = (spinDir.listFiles().orEmpty().toList() + walkDir.listFiles().orEmpty().toList()).filter { it.name.endsWith(".jpg") }.sumOf { it.length() }
        val job = ProcessingJob(
            type = JobType.PHOTOGRAMMETRY, quality = ProcQuality.DETAILED,
            estimate = JobEstimate(imageCount = spinRecs.size + walkRecs.size, imageBytes = bytes), workDir = hub.workDir(),
            objectBox = box, supportPlane = plane,
            prebuilt = { dest -> SpinJobBuilder.build(dest, SpinJobInput(SpinJobMode.HYBRID, spinDir, box, plane, meta, walkDir, cloud)) },
        )
        startPhotoJob(box, plane, job)
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
        val box = pendingBox
        val plane = pendingPlane
        val units = session.state.value.unit
        val photoJob = job.type == JobType.PHOTOGRAMMETRY
        val mesh: TriMesh?
        var textured: TexturedObject? = null
        if (photoJob) {
            // the PC's textured mesh (mesh.obj with UVs + texture.png) replaces any phone result
            val zip = done.resultZip
            val photo = pendingPhotos?.let { (dir, recs) ->
                withContext(Dispatchers.IO) { runCatching { KeyframeLoader.bestPhoto(dir, recs, rotateDeg = photoRotationDeg()) }.getOrNull() }
            }
            val pc = zip?.let { z -> withContext(Dispatchers.IO) { runCatching { PcTexturedResult.read(z, box, plane, photo) }.getOrNull() } }
            mesh = pc?.mesh ?: zip?.let { loadResultMesh(it) }
            textured = pc?.textured
        } else {
            mesh = if (done.backend == Backend.PHONE) job.mesh else done.resultZip?.let { loadResultMesh(it) }
        }
        var extras = emptyList<Pair<String, String>>()
        if (box != null && plane != null) {
            val ctx = ResultContext(mesh, job.isolated ?: FloatArray(0), box, plane, summaryOf(outcome))
            extras = resultAnnotators.flatMap { a -> runCatching { a.annotate(ctx, units) }.getOrDefault(emptyList()) }
            if (!photoJob) textured = textureProviders.firstNotNullOfOrNull { p -> runCatching { p.bake(ctx) }.getOrNull() }
        }
        extras = extras + ObjectCardText.fusionLines(units, done.result)
        objResult = ObjectResultState(outcome, mesh, null, extras, textured, textured?.bestPhoto, captureVideo)
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
        spinCapture.discard()
        stopCaptureVideo()
        captureVideo = null
        pendingBox = null
        pendingPlane = null
        pendingPhotos = null
        chosenQuality = ProcQuality.QUICK
        mlTapInput.set(null)
        mlLabel = null
        captureObservers.forEach { it.onCaptureReset() }
        objSaveOpen = false
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

    private fun objectSnapshot(r: ObjectResultState, id: String, projectId: String?, mesh: TriMesh, name: String?) = ScanSnapshot(
        id, projectId, System.currentTimeMillis(), FloatArray(0), FloatArray(0), emptyList(), null,
        mesh, ScanSnapshot.KIND_OBJECT, summaryOf(r.outcome),
        name = name,
        method = ObjectCardText.method(r.outcome),
        extras = r.extras.map { (k, v) -> "$k: $v" },
    )

    private fun onObjectView3D(r: ObjectResultState) {
        val mesh = r.mesh ?: return toast("No mesh was produced")
        val id = r.savedId ?: java.util.UUID.randomUUID().toString()
        scan3dSnapshot = objectSnapshot(r, id, null, mesh, null)
        scan3dTextured = r.textured?.viewData
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

    /** Names of the objects already saved in [projectId] (null: a new project, so none). */
    private fun objectNames(projectId: String?): List<String> =
        if (projectId == null) emptyList()
        else ScanFiles.list(scansRoot(this), projectId).filter { it.kind == ScanSnapshot.KIND_OBJECT }.mapNotNull { it.name }

    /** Saved objects of [projectId] (all projects when null), newest first, with their project names. */
    private fun objectItems(projectId: String?, projects: List<Project>): List<ObjectItem> =
        ScanFiles.list(scansRoot(this), projectId)
            .filter { it.kind == ScanSnapshot.KIND_OBJECT }
            .map { info -> ObjectItem(info, projects.firstOrNull { it.id == info.projectId }?.name) }

    /**
     * Saves the finished object: scan files + thumbnail (the mesh render, or the texture drone's photo) + textured mesh +
     * capture video into the chosen project, then copies everything to Download/ARMeasure/<project>.
     */
    private fun onObjectSave(r: ObjectResultState, name: String, choice: ProjectChoice) {
        val mesh = r.mesh ?: return toast("No mesh to save")
        if (r.savedId != null) return toast("Already saved")
        val project: Project = try {
            when (choice) {
                is ProjectChoice.Existing -> repo.project(choice.id) ?: return toast("Project not found")
                is ProjectChoice.New -> repo.createProject(choice.name)
            }
        } catch (e: IOException) {
            return toast("Storage error: ${e.message}")
        }
        lastUsedProjectId = project.id
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_PROJECT, project.id).apply()
        lifecycleScope.launch {
            val id = java.util.UUID.randomUUID().toString()
            val snap = objectSnapshot(r, id, project.id, mesh, name)
            val root = scansRoot(this@MainActivity)
            withContext(Dispatchers.IO) {
                runCatching {
                    ScanFiles.save(root, snap)
                    ObjectThumbnail.save(root, id, mesh, r.thumbnailOverride)
                    r.textured?.let { ScanFiles.saveTextured(root, id, it) }
                    r.video?.let { ScanFiles.saveVideo(root, id, it) }
                }
            }.onSuccess {
                objResult = r.copy(savedId = id)
                scanListVersion++
                if (settings.copyToDownloads.value) {
                    val info = exportInfoOf(snap, project.name)
                    publicExport(project.name) { it.exportObject(info, mesh, r.textured, session.state.value.unit, r.video) }
                } else {
                    toast("Saved to ${project.name}")
                }
            }.onFailure { toast("Could not save the object: ${it.message}") }
        }
    }

    private fun exportInfoOf(s: ScanSnapshot, projectName: String) = ObjectExportInfo(
        name = s.name ?: "Object",
        createdAt = s.createdAt,
        projectName = projectName,
        summary = s.objectSummary ?: ObjectSummary(0f, 0f, 0f, 0f, 0f, 0f),
        method = s.method,
        notes = s.notes,
        extras = s.extras.map { line ->
            val i = line.indexOf(": ")
            if (i > 0) line.substring(0, i) to line.substring(i + 2) else "Note" to line
        },
    )

    @Composable
    private fun BoxScope.ObjectResultCard(r: ObjectResultState, units: Units) {
        val texture = r.textured?.let { listOf("Texture" to if (it.hasAtlas) "photos baked onto the mesh" else "colour per vertex") }.orEmpty()
        val lines = ObjectCardText.lines(units, r.outcome) + r.extras + texture +
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
                if (r.mesh != null) CardAction(
                    if (r.savedId != null) "Saved" else "Save", Color(0xFF34C759),
                    { if (r.savedId == null) objSaveOpen = true },
                ) else null,
            ),
        )
    }

    // ---- saved objects: detail page, public export, snackbar ----

    @Composable
    private fun ObjectDetailHost(scanId: String, projects: List<Project>, units: Units) {
        val root = scansRoot(this)
        val snap = remember(scanId, scanListVersion) { ScanFiles.load(root, scanId) }
        val mesh = snap?.mesh
        if (snap == null || mesh == null) {
            LaunchedEffect(Unit) { toast("Object not found"); screen = objectReturnTo }
            return
        }
        val projectName = projects.firstOrNull { it.id == snap.projectId }?.name ?: ExportNames.DEFAULT_PROJECT
        val hasVideo = remember(scanId, scanListVersion) { ScanFiles.videoFile(root, scanId).isFile }
        val textured = rememberSavedTextured(scanId)
        ObjectDetailScreen(
            snapshot = snap, projectName = projects.firstOrNull { it.id == snap.projectId }?.name, units = units, hasVideo = hasVideo,
            textured = textured,
            onBack = { screen = objectReturnTo },
            onRename = { n -> ScanFiles.updateMeta(root, scanId, name = n.trim().ifEmpty { null }); scanListVersion++ },
            onSaveNotes = { n -> ScanFiles.updateMeta(root, scanId, notes = n); scanListVersion++; toast("Notes saved") },
            onShare = { f ->
                try {
                    ObjectShare.share(this, exportInfoOf(snap, projectName), mesh, ScanFiles.loadTextured(root, scanId), units, f)
                } catch (e: Exception) {
                    toast("Share failed: ${e.message}")
                }
            },
            onOpenDownloads = { openObjectInDownloads(snap, projectName, units) },
            onPlayVideo = {
                val name = ExportNames.plain(snap.name ?: "Object", "capture", "mp4")
                val uri = PublicStorage.findUri(this, projectName, name)
                if (!PublicStorage.playVideo(this, uri, ScanFiles.videoFile(root, scanId))) toastLong("No video player found for the capture video")
            },
            onFullScreen = { scan3dSnapshot = snap; scan3dTextured = null; scan3dReturnTo = Screen.ObjectDetail(scanId); screen = Screen.Scan3D(scanId) },
            onDelete = { ScanFiles.delete(root, scanId); scanListVersion++; screen = objectReturnTo },
        )
    }

    /** The saved object's photo-textured mesh, loaded and parsed off the main thread (null while loading, or when the object is grey). */
    @Composable
    private fun rememberSavedTextured(scanId: String): TexturedMeshData? {
        val loaded by produceState<TexturedMeshData?>(null, scanId, scanListVersion) {
            value = withContext(Dispatchers.IO) {
                runCatching { ScanFiles.loadTextured(scansRoot(this@MainActivity), scanId)?.meshData() }.getOrNull()
            }
        }
        return loaded
    }

    /** Exports the object to Downloads when it is not there yet, then opens the folder. */
    private fun openObjectInDownloads(snap: ScanSnapshot, projectName: String, units: Units) {
        val mesh = snap.mesh ?: return
        runWithStoragePermission {
            lifecycleScope.launch {
                val present = withContext(Dispatchers.IO) {
                    PublicStorage.findUri(this@MainActivity, projectName, ExportNames.plain(snap.name ?: "Object", "mesh", "obj")) != null
                }
                if (!present) {
                    val root = scansRoot(this@MainActivity)
                    val video = ScanFiles.videoFile(root, snap.id)
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            PublicStorage.exporter(this@MainActivity)
                                .exportObject(exportInfoOf(snap, projectName), mesh, ScanFiles.loadTextured(root, snap.id), units, video)
                        }
                    }
                    res.onFailure { return@launch toastLong("Could not copy to Downloads: ${it.message}") }
                }
                if (!PublicStorage.openFolder(this@MainActivity, projectName)) {
                    toastLong("Open the Files app, then Download > ARMeasure > $projectName")
                }
            }
        }
    }

    /** Runs [action] once the Downloads copy may write: at once on Android 10+, after the storage permission on 9 and older. */
    private fun runWithStoragePermission(action: () -> Unit) {
        if (PublicStorage.needsLegacyPermission(this)) {
            pendingStorageAction = action
            askStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    /**
     * Copies a save into Download/ARMeasure/<project> when 'Copy saves to Downloads' is on, then shows the snackbar
     * 'Saved to Download/ARMeasure/<project>' with Open folder and Share. [block] runs off the main thread.
     */
    private fun publicExport(projectName: String, block: (PublicExporter) -> ExportResult) {
        if (!settings.copyToDownloads.value) return
        runWithStoragePermission {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching { block(PublicStorage.exporter(this@MainActivity)) } }
                    .onSuccess { saveNote = SaveNote(ExportNames.savedMessage(projectName), it) }
                    .onFailure { toastLong("Could not copy to Downloads: ${it.message}") }
            }
        }
    }

    private fun onSnackOpenFolder(note: SaveNote) {
        saveNote = null
        if (!PublicStorage.openFolder(this, note.result.project)) toastLong("Open the Files app, then ${note.result.folder.replace("/", " > ")}")
    }

    private fun onSnackShare(note: SaveNote) {
        saveNote = null
        try { PublicStorage.share(this, note.result) } catch (e: Exception) { toast("Share failed: ${e.message}") }
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
            scan3dTextured = null
            scan3dReturnTo = Screen.Measure
            scanListVersion++
            exportScanCopy(snap)
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
            if (settings.copyToDownloads.value) {
                val saved = repo.project(project.id)
                if (saved != null) publicExport(project.name) { it.exportRoom(saved, room, state0Unit()) }
            } else {
                toast("Saved to ${project.name}")
            }
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

    /** Copies a saved room scan (cloud + surfaces) to Download/ARMeasure/<project>. */
    private fun exportScanCopy(snap: ScanSnapshot) {
        val name = snap.projectId?.let { repo.project(it)?.name } ?: return
        publicExport(name) { it.exportScan(name, snap, "Scan") }
    }

    private fun state0Unit(): Units = session.state.value.unit

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun toastLong(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PLAYBACK_URI = "playback_uri"
        private const val PREFS = "plan"
        private const val KEY_LAST_PROJECT = "last_project"

        /** Dome markers are redrawn at most this often (2 Hz). */
        private const val DOME_INTERVAL_MS = 500L

        /** The ML label of the tapped object stays on screen this long. */
        private const val ML_LABEL_MS = 4000L

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
private data class ObjectResultState(
    val outcome: ObjectOutcome,
    val mesh: TriMesh?,
    val savedId: String?,
    /** Extra card lines from the [ResultAnnotator] hooks (primitive fit). */
    val extras: List<Pair<String, String>> = emptyList(),
    /** The textured mesh from the [TextureProvider] hook, null while the mesh is grey. */
    val textured: TexturedObject? = null,
    /** HOOK: replaces the mesh render as the gallery thumbnail (the texture drone's best keyframe photo). */
    val thumbnailOverride: android.graphics.Bitmap? = null,
    /** The capture video of this scan, if one was recorded. */
    val video: File? = null,
)

/** A room-scan result that came back (from the PC, or the phone through the service) with optional planes and mesh. */
private class ScanPcResult(val result: ResultJson, val planes: List<SnapshotPlane>, val mesh: TriMesh?)

/** A PC upload waiting for the user's yes (mobile data, over 20 MB). */
private class PendingUpload(val message: String, val run: () -> Unit)
