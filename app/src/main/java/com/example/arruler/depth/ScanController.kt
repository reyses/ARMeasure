package com.example.arruler.depth

import com.example.arruler.geometry.Vec3
import com.example.arruler.measure.MeasurePoint
import com.example.arruler.objscan.TriMesh
import com.example.arruler.processing.CloudData
import com.example.arruler.scan3d.ScanSnapshot
import com.example.arruler.scan3d.SnapshotPlane
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

/** Live counters of the scan: occupied voxels and depth points inserted so far. */
data class ScanStats(val voxels: Int = 0, val points: Long = 0)

/** Outcome of Analyze: a room, or the pieces the sweep still lacks. */
sealed interface ScanAnalysis {
    class Room(val model: RoomModel) : ScanAnalysis {
        val areaM2: Float get() = model.outline.area()
        val perimeterM: Float get() = model.outline.perimeter()
        val heightM: Float get() = model.height
        val volumeM3: Float get() = areaM2 * heightM
    }

    class Incomplete(val missing: List<String>) : ScanAnalysis
}

/** Pure decisions and conversions of the scan, kept free of ARCore so they are unit-testable. */
object ScanLogic {
    const val SAMPLE_EVERY_N_FRAMES = 3
    const val PREVIEW_INTERVAL_MS = 500L
    const val PREVIEW_MIN_HITS = 2
    const val PREVIEW_MAX_POINTS = 500
    const val ANALYZE_MIN_HITS = 2

    /** Sample this frame? Every [every]-th frame while scanning and never with a sample still in flight. */
    fun shouldSample(frameIndex: Long, every: Int, scanning: Boolean, inFlight: Boolean): Boolean =
        scanning && !inFlight && frameIndex % every == 0L

    /** Refresh the on-screen cloud? At most once per [minIntervalMs], one at a time. */
    fun shouldRefreshPreview(nowMs: Long, lastMs: Long, minIntervalMs: Long, inFlight: Boolean): Boolean =
        !inFlight && nowMs - lastMs >= minIntervalMs

    /** Evenly thins packed xyz to at most [max] points. */
    fun thin(packed: FloatArray, max: Int): List<MeasurePoint> {
        val n = packed.size / 3
        if (n == 0 || max <= 0) return emptyList()
        val take = minOf(n, max)
        return List(take) { i ->
            val k = (i.toLong() * n / take).toInt()
            MeasurePoint(packed[k * 3], packed[k * 3 + 1], packed[k * 3 + 2])
        }
    }

    /** What the sweep still lacks for [RoomFromPlanes.build] to succeed; empty when nothing obvious. */
    fun missingPieces(planes: List<ExtractedPlane>): List<String> {
        val out = ArrayList<String>()
        if (planes.none { it.kind == PlaneKind.FLOOR }) out += "no floor (sweep the floor)"
        if (planes.none { it.kind == PlaneKind.CEILING }) out += "no ceiling (sweep the ceiling)"
        val walls = planes.count { it.kind == PlaneKind.WALL }
        if (walls < 3) out += "fewer than 3 walls ($walls found; sweep the walls)"
        return out
    }

    fun analyze(points: FloatArray): ScanAnalysis {
        val planes = PlaneExtractor(gpu = com.example.arruler.gpu.GpuGate.ransacContext(), gpuPolicy = com.example.arruler.gpu.GpuGate.profile()).extract(points)
        RoomFromPlanes.build(planes)?.let { return ScanAnalysis.Room(it) }
        val missing = missingPieces(planes)
        return ScanAnalysis.Incomplete(
            missing.ifEmpty { listOf("walls do not close into a room (sweep the corners)") }
        )
    }
}

/** The room's floor outline lifted back to 3D at [RoomModel.floorY] (outline is x, z). */
fun RoomModel.floorPolygon3d(): List<Vec3> = outline.points.map { Vec3(it.x, floorY, it.y) }

/**
 * Owns the voxel cloud of a depth scan. [onFrame] is called on the AR frame callback: it acquires
 * and copies the depth images there (they belong to the frame) and hands arrays to Dispatchers.Default
 * for the unprojection + insert. All cloud access is serialised by a mutex.
 */
class ScanController(private val scope: CoroutineScope) {

    private val cloud = VoxelCloud()
    private val sampler = ThrottledDepthSampler(scope)
    private val lock = Mutex()

    @Volatile private var generation = 0
    @Volatile private var previewInFlight = false
    @Volatile private var totalPoints = 0L
    private var lastPreviewMs = 0L

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _stats = MutableStateFlow(ScanStats())
    val stats: StateFlow<ScanStats> = _stats.asStateFlow()

    private val _preview = MutableStateFlow<List<MeasurePoint>>(emptyList())
    /** Sparse subset of the cloud (<= [ScanLogic.PREVIEW_MAX_POINTS]) for [com.example.arruler.ar.ArRenderer.renderCloud]. */
    val preview: StateFlow<List<MeasurePoint>> = _preview.asStateFlow()

    private val _analyzing = MutableStateFlow(false)
    val analyzing: StateFlow<Boolean> = _analyzing.asStateFlow()

    private val _analysis = MutableStateFlow<ScanAnalysis?>(null)
    val analysis: StateFlow<ScanAnalysis?> = _analysis.asStateFlow()

    fun start() { _scanning.value = true }

    fun pause() { _scanning.value = false }

    /** Stops scanning and drops the cloud, the preview and any result. */
    fun reset() {
        _scanning.value = false
        generation++
        _analysis.value = null
        _preview.value = emptyList()
        _stats.value = ScanStats()
        totalPoints = 0
        scope.launch(Dispatchers.Default) { lock.withLock { cloud.clear() } }
    }

    /** Main thread, once per AR frame. */
    fun onFrame(frame: Frame, nowMs: Long) {
        val scanning = _scanning.value
        if (!scanning) return
        val sampleGen = generation
        sampler.onFrame(frame) { sample ->
            lock.withLock {
                if (sampleGen == generation) {
                    cloud.addAll(sample.xyz, sample.count)
                    totalPoints += sample.count
                    _stats.value = ScanStats(cloud.count, totalPoints)
                }
            }
        }
        if (ScanLogic.shouldRefreshPreview(nowMs, lastPreviewMs, ScanLogic.PREVIEW_INTERVAL_MS, previewInFlight)) {
            lastPreviewMs = nowMs
            previewInFlight = true
            val gen = generation
            scope.launch(Dispatchers.Default) {
                try {
                    val pts = lock.withLock { cloud.points(ScanLogic.PREVIEW_MIN_HITS) }
                    if (gen == generation) _preview.value = ScanLogic.thin(pts, ScanLogic.PREVIEW_MAX_POINTS)
                } finally {
                    previewInFlight = false
                }
            }
        }
    }

    /**
     * Cloud + planes + room as an immutable snapshot (off the main thread); null when the cloud is empty.
     * Costs one plane extraction and holds the cloud lock meanwhile, so call it while paused/analysed.
     */
    suspend fun snapshot(id: String, projectId: String?): ScanSnapshot? =
        withContext(Dispatchers.Default) {
            lock.withLock {
                if (cloud.count == 0) return@withLock null
                val planes = PlaneExtractor(gpu = com.example.arruler.gpu.GpuGate.ransacContext(), gpuPolicy = com.example.arruler.gpu.GpuGate.profile()).extract(cloud.points(ScanLogic.ANALYZE_MIN_HITS))
                ScanSnapshot.from(cloud, planes, RoomFromPlanes.build(planes), id, projectId)
            }
        }

    /** The voxel cloud as an upload payload for a PC job (hits per voxel, confidence unknown = 255); null when empty. */
    suspend fun cloudData(): CloudData? = withContext(Dispatchers.Default) {
        lock.withLock {
            if (cloud.count == 0) return@withLock null
            val pts = cloud.points(ScanLogic.ANALYZE_MIN_HITS)
            val n = pts.size / 3
            if (n == 0) return@withLock null
            CloudData(pts, IntArray(n) { cloud.hitsAt(pts[it * 3], pts[it * 3 + 1], pts[it * 3 + 2]) }, IntArray(n) { 255 })
        }
    }

    /** Snapshot with planes (and optionally a mesh) computed elsewhere, e.g. by the PC; no local plane extraction. */
    suspend fun snapshotWith(id: String, projectId: String?, planes: List<SnapshotPlane>, mesh: TriMesh?): ScanSnapshot? =
        withContext(Dispatchers.Default) {
            lock.withLock {
                if (cloud.count == 0) return@withLock null
                ScanSnapshot.fromSnapshotPlanes(cloud, planes, null, id, projectId, mesh = mesh)
            }
        }

    /** Runs plane extraction + room reconstruction off the main thread; result in [analysis]. */
    fun analyze() {
        if (_analyzing.value) return
        _analyzing.value = true
        _scanning.value = false
        val gen = generation
        scope.launch(Dispatchers.Default) {
            try {
                val pts = lock.withLock { cloud.points(ScanLogic.ANALYZE_MIN_HITS) }
                val result = ScanLogic.analyze(pts)
                if (gen == generation) _analysis.value = result
            } finally {
                _analyzing.value = false
            }
        }
    }
}
