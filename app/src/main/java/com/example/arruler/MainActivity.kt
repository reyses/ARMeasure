package com.example.arruler

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.arruler.ar.RecordingFiles
import com.example.arruler.ar.TrackEvent
import com.example.arruler.measure.MeasureMode
import com.example.arruler.measure.MeasurePoint
import com.example.arruler.measure.MeasureState
import com.example.arruler.measure.MeasurementSession
import com.example.arruler.measure.Phase
import com.example.arruler.measure.Units
import com.example.arruler.measure.toVec3
import com.example.arruler.nav.Screen
import com.example.arruler.plan.ExportFormat
import com.example.arruler.plan.PlanShare
import com.example.arruler.store.PlanFrame
import com.example.arruler.store.Project
import com.example.arruler.store.ProjectRepository
import com.example.arruler.store.RoomCapture
import com.example.arruler.ui.AreaControls
import com.example.arruler.ui.ArRulerTheme
import com.example.arruler.ui.ControlsBar
import com.example.arruler.ui.MeasureOverlay
import com.example.arruler.ui.PlanScreen
import com.example.arruler.ui.PlaybackBadge
import com.example.arruler.ui.ProjectChoice
import com.example.arruler.ui.ProjectsButton
import com.example.arruler.ui.ProjectsScreen
import com.example.arruler.ui.RecordControl
import com.example.arruler.ui.SaveRoomDraft
import com.example.arruler.ui.SaveRoomPill
import com.example.arruler.ui.SaveRoomSheet
import com.google.ar.core.PlaybackStatus
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Thin wiring: AR session + measurement state + renderer + Compose UI + navigation. */
class MainActivity : AppCompatActivity() {

    private lateinit var ar: ArSessionController
    private val renderer = ArRenderer()
    private val session = MeasurementSession()
    private var pointCount = 0

    // ---- plan / projects ----
    private lateinit var repo: ProjectRepository
    private var screen by mutableStateOf<Screen>(Screen.Measure)
    private var saveDraft by mutableStateOf<SaveRoomDraft?>(null)
    private var lastUsedProjectId by mutableStateOf<String?>(null)

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

        setContent {
            val state by session.state.collectAsState()
            val hasSurface by ar.hasSurface.collectAsState()
            val recState by ar.recorder.state.collectAsState()
            val playback by ar.playbackStatus.collectAsState()
            val projects by repo.projects.collectAsState()
            val view = LocalView.current
            DisposableEffect(view) {
                haptic = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
                onDispose { haptic = {} }
            }
            ArRulerTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    // The AR view stays composed under the other screens so the ARCore session,
                    // anchors and the shared plan frame survive a visit to Projects/Plan.
                    ArSceneHost(ar, renderer, Modifier.fillMaxSize())
                    if (screen == Screen.Measure) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                            MeasureOverlay(state, hasSurface)
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
                                onClear = ::clearAll,
                            )
                            AreaControls(state, ::onSetMode, ::onAreaClose, ::onAreaUndo, ::onAreaHeight)
                            ProjectsButton { screen = Screen.Projects }
                            if (state.mode == MeasureMode.AREA && state.closed && !state.heightActive &&
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
                            PlanScreen(
                                project = project,
                                units = state.unit,
                                onBack = { screen = Screen.Projects },
                                onRenameRoom = { rid, name -> guarded { repo.renameRoom(s.projectId, rid, name) } },
                                onDeleteRoom = { rid -> guarded { repo.deleteRoom(s.projectId, rid) } },
                                onExport = { format, angles ->
                                    project?.let { exportPlan(it, format, state.unit, angles) }
                                },
                            )
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
                    screen = if (screen is Screen.Plan) Screen.Projects else Screen.Measure
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
        areaFrame()
        if (session.state.value.phase == Phase.MEASURING) {
            ar.hitTestCenter()?.let { session.setLive(it.point) }
        }
    }

    private fun onArTap(x: Float, y: Float) {
        if (screen != Screen.Measure) return
        val s = session.state.value
        if (s.mode == MeasureMode.AREA) return
        if (s.phase != Phase.MEASURING || s.points.isEmpty()) return
        val hit = ar.hitTest(x, y) ?: return
        haptic()
        val p = ar.createAnchor(hit)
        logPoint(p)
        session.addPoint(p)
    }

    private fun onMainButton() {
        if (session.state.value.mode == MeasureMode.AREA) return onAreaShutter()
        if (session.state.value.phase == Phase.MEASURING) {
            session.stop()
            return
        }
        if (session.state.value.points.isNotEmpty()) clearAll()
        val hit = ar.hitTestCenter() ?: return
        haptic()
        val p = ar.createAnchor(hit)
        pointCount = 0
        logPoint(p)
        session.start(p)
    }

    private fun clearAll() {
        ar.releaseAnchors()
        session.clear()
    }

    // ---- AREA mode (additive) ----

    private fun renderMeasureState(s: MeasureState) {
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

    private fun onSetMode(mode: MeasureMode) {
        if (session.state.value.mode == mode) return
        clearAll()
        session.setMode(mode)
    }

    private fun onAreaShutter() {
        val s = session.state.value
        val hit = ar.hitTestCenter() ?: return
        when {
            s.heightActive -> { haptic(); session.commitHeight(ar.createAnchor(hit)) }
            s.closed -> { clearAll(); haptic(); session.start(ar.createAnchor(hit)) }
            s.phase == Phase.MEASURING && s.points.isNotEmpty() -> { haptic(); session.addPoint(ar.createAnchor(hit)) }
            else -> { clearAll(); haptic(); session.start(ar.createAnchor(hit)) }
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
