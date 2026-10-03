package com.example.arruler.depth

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.BoxDim
import com.example.arruler.objscan.CoverageDome
import com.example.arruler.objscan.DomeBin
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.ObjectPlacement
import com.example.arruler.objscan.ObjectQuality
import com.example.arruler.objscan.ObjectVoxelCloud
import com.example.arruler.objscan.SupportPlane
import com.google.ar.core.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ObjectPhase {
    /** No box yet: tap the surface the object stands on. */
    IDLE,

    /** Box placed; sizes, rotation and Fit are editable, depth around the tap is collected for Fit. */
    PLACED,

    CAPTURING,
    PAUSED,

    /** Isolation / measures / mesh running (phone or PC). */
    WORKING,
    RESULT,
}

/** Everything the OBJECT mode UI draws from. */
data class ObjectUiState(
    val phase: ObjectPhase = ObjectPhase.IDLE,
    val box: ObjectBox? = null,
    val quality: ObjectQuality = ObjectQuality.QUICK,
    val voxels: Int = 0,
    val points: Long = 0,
    val coverage: Float = 0f,
    val hint: String = "",
    val fitting: Boolean = false,
    /** Voxels collected around the tap for Fit. */
    val fitVoxels: Int = 0,
)

/** The raw capture handed to isolation / the PC. */
class ObjectCapture(
    val points: FloatArray,
    val hits: IntArray,
    val box: ObjectBox,
    val plane: SupportPlane,
    val quality: ObjectQuality,
)

/**
 * Live state of the OBJECT mode: the placed box, the Fit pre-scan cloud, the capture cloud
 * ([ObjectVoxelCloud], one per capture, box locked), the coverage dome and the phase. Depth sampling goes
 * through the same [ThrottledDepthSampler] as [ScanController]. Edits and [onFrame] are main-thread; the
 * clouds are touched on Dispatchers.Default under [lock].
 */
class ObjectScanController(private val scope: CoroutineScope) {

    private val sampler = ThrottledDepthSampler(scope)
    private val lock = Mutex()

    private var plane: SupportPlane? = null
    private var searchBox: ObjectBox? = null
    private var fitCloud: ObjectVoxelCloud? = null
    private var cloud: ObjectVoxelCloud? = null
    private var dome: CoverageDome? = null
    @Volatile private var generation = 0
    @Volatile private var totalPoints = 0L
    private var frameCount = 0

    private val _state = MutableStateFlow(ObjectUiState())
    val state: StateFlow<ObjectUiState> = _state.asStateFlow()

    val phase: ObjectPhase get() = _state.value.phase

    /** Drops the box on the horizontal plane through [tap] (also moves an already placed box, keeping its size and yaw). */
    fun place(tap: Vec3) {
        val keep = _state.value.box
        val box = keep?.moveTo(tap) ?: ObjectPlacement.defaultBox(tap)
        plane = ObjectPlacement.supportPlane(tap)
        generation++
        val sb = ObjectPlacement.searchBox(tap)
        searchBox = sb
        fitCloud = ObjectVoxelCloud(sb, ObjectQuality.QUICK.voxelSize, FIT_MAX_VOXELS)
        _state.value = ObjectUiState(ObjectPhase.PLACED, box, _state.value.quality)
    }

    fun resize(dim: BoxDim, deltaM: Float) = editBox { ObjectPlacement.resize(it, dim, deltaM) }

    fun rotate(deg: Float = ObjectPlacement.ROTATE_STEP_DEG) = editBox { ObjectPlacement.rotate(it, deg) }

    private fun editBox(f: (ObjectBox) -> ObjectBox) {
        val s = _state.value
        val b = s.box ?: return
        if (s.phase != ObjectPhase.PLACED) return
        _state.value = s.copy(box = f(b))
    }

    fun setQuality(q: ObjectQuality) {
        _state.value = _state.value.copy(quality = q)
    }

    /**
     * Fit: grows / shrinks the box to the depth points above the plane within 40 cm of the tap. [onDone] gets
     * false when too little was seen around the tap yet (move the phone slowly around it and try again).
     * [onDone] runs on a background thread.
     */
    fun fit(onDone: (Boolean) -> Unit) {
        val s = _state.value
        val sb = searchBox ?: return onDone(false)
        val pl = plane ?: return onDone(false)
        val fc = fitCloud ?: return onDone(false)
        if (s.phase != ObjectPhase.PLACED || s.fitting) return
        _state.value = s.copy(fitting = true)
        val gen = generation
        scope.launch(Dispatchers.Default) {
            val pts = lock.withLock { fc.points(1) }
            val fitted = ObjectPlacement.fit(pts, sb, pl)
            val cur = _state.value
            _state.value = if (gen == generation && cur.phase == ObjectPhase.PLACED) {
                cur.copy(box = fitted ?: cur.box, fitting = false)
            } else {
                cur.copy(fitting = false)
            }
            onDone(fitted != null)
        }
    }

    /** Locks the box and starts (or, from PAUSED, resumes) the capture. */
    fun start(quality: ObjectQuality = _state.value.quality) {
        val s = _state.value
        val b = s.box ?: return
        when (s.phase) {
            ObjectPhase.PLACED -> {
                generation++
                totalPoints = 0
                frameCount = 0
                cloud = ObjectVoxelCloud(b, quality.voxelSize, CAPTURE_MAX_VOXELS)
                val d = CoverageDome(b)
                dome = d
                fitCloud = null
                _state.value = s.copy(
                    phase = ObjectPhase.CAPTURING, quality = quality, voxels = 0, points = 0, coverage = 0f,
                    hint = ObjectPlacement.hint(d.bins(), 0f, null, windowOf(b)),
                )
            }
            ObjectPhase.PAUSED -> _state.value = s.copy(phase = ObjectPhase.CAPTURING)
            else -> {}
        }
    }

    fun pause() {
        val s = _state.value
        if (s.phase == ObjectPhase.CAPTURING) _state.value = s.copy(phase = ObjectPhase.PAUSED)
    }

    /** Dome bins for drawing (directions from the box centre), empty outside a capture. */
    fun domeBins(): List<DomeBin> =
        if (phase == ObjectPhase.CAPTURING || phase == ObjectPhase.PAUSED) dome?.bins().orEmpty() else emptyList()

    /** Main thread, once per AR frame; [camera] is the camera position in world meters. */
    fun onFrame(frame: Frame, camera: Vec3) {
        val s = _state.value
        when (s.phase) {
            ObjectPhase.PLACED -> {
                val fc = fitCloud ?: return
                val sb = searchBox ?: return
                val gen = generation
                sampler.onFrame(frame) { sample ->
                    lock.withLock {
                        if (gen == generation) {
                            val xyz = sample.xyz
                            for (i in 0 until sample.count) {
                                val x = xyz[i * 3]; val y = xyz[i * 3 + 1]; val z = xyz[i * 3 + 2]
                                if (sb.contains(x, y, z)) fc.add(x, y, z)
                            }
                            val n = fc.count
                            if (n != _state.value.fitVoxels) _state.value = _state.value.copy(fitVoxels = n)
                        }
                    }
                }
            }
            ObjectPhase.CAPTURING -> {
                val c = cloud ?: return
                val box = s.box ?: return
                val gen = generation
                sampler.onFrame(frame) { sample ->
                    lock.withLock {
                        if (gen == generation) {
                            val xyz = sample.xyz
                            for (i in 0 until sample.count) {
                                val x = xyz[i * 3]; val y = xyz[i * 3 + 1]; val z = xyz[i * 3 + 2]
                                if (box.contains(x, y, z, CAPTURE_MARGIN)) c.add(x, y, z)
                            }
                            totalPoints += sample.count
                            val cur = _state.value
                            if (cur.phase == ObjectPhase.CAPTURING) _state.value = cur.copy(voxels = c.count, points = totalPoints)
                        }
                    }
                }
                val d = dome ?: return
                d.observe(camera)
                if (++frameCount % HINT_EVERY_N_FRAMES == 0) {
                    val cov = d.coverage()
                    val dist = camera.distanceTo(box.volumeCentre())
                    _state.value = _state.value.copy(coverage = cov, hint = ObjectPlacement.hint(d.bins(), cov, dist, windowOf(box)))
                }
            }
            else -> {}
        }
    }

    private fun windowOf(b: ObjectBox) = CoverageDome.windowFor(b.maxDimension())

    /** The capture so far (off the main thread); null when there is nothing or no box. */
    suspend fun capture(): ObjectCapture? {
        val s = _state.value
        val b = s.box ?: return null
        val pl = plane ?: return null
        val c = cloud ?: return null
        return withContext(Dispatchers.Default) {
            lock.withLock {
                if (c.count == 0) return@withLock null
                val pts = c.points(1)
                ObjectCapture(pts, c.hitsFor(pts), b, pl, s.quality)
            }
        }
    }

    fun working() {
        val s = _state.value
        if (s.phase == ObjectPhase.CAPTURING || s.phase == ObjectPhase.PAUSED) _state.value = s.copy(phase = ObjectPhase.WORKING)
    }

    /** After a failed or cancelled job: back to PAUSED so the capture can be finished again or resumed. */
    fun backToPaused() {
        if (_state.value.phase == ObjectPhase.WORKING) _state.value = _state.value.copy(phase = ObjectPhase.PAUSED)
    }

    fun showResult() {
        if (_state.value.phase == ObjectPhase.WORKING) _state.value = _state.value.copy(phase = ObjectPhase.RESULT)
    }

    /** Back to IDLE: forgets the box, clouds and result. */
    fun reset() {
        generation++
        val q = _state.value.quality
        val c = cloud
        val f = fitCloud
        cloud = null; fitCloud = null; dome = null; plane = null; searchBox = null
        totalPoints = 0
        _state.value = ObjectUiState(quality = q)
        scope.launch(Dispatchers.Default) { lock.withLock { c?.clear(); f?.clear() } }
    }

    companion object {
        const val CAPTURE_MARGIN = 0.15f
        const val CAPTURE_MAX_VOXELS = 1_000_000
        const val FIT_MAX_VOXELS = 300_000
        const val HINT_EVERY_N_FRAMES = 6
    }
}
