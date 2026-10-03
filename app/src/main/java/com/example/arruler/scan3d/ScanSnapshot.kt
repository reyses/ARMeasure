package com.example.arruler.scan3d

import com.example.arruler.depth.ExtractedPlane
import com.example.arruler.depth.PlaneKind
import com.example.arruler.depth.RoomModel
import com.example.arruler.depth.VoxelCloud
import kotlin.math.abs
import kotlin.math.roundToInt

/** A detected plane in world space (meters, +Y up): unit normal, `n . p = d`, outline polygon as packed xyz. */
class SnapshotPlane(
    val kind: PlaneKind,
    val nx: Float, val ny: Float, val nz: Float,
    val d: Float,
    /** World-space outline vertices, packed xyz, in order around the polygon. */
    val outline: FloatArray,
    val inlierCount: Int,
) {
    val vertexCount: Int get() = outline.size / 3

    fun distanceTo(x: Float, y: Float, z: Float): Float = nx * x + ny * y + nz * z - d

    companion object {
        fun from(p: ExtractedPlane): SnapshotPlane {
            val o3 = p.outline3d()
            val packed = FloatArray(o3.size * 3)
            o3.forEachIndexed { i, v -> packed[i * 3] = v.x; packed[i * 3 + 1] = v.y; packed[i * 3 + 2] = v.z }
            return SnapshotPlane(p.kind, p.normal.x, p.normal.y, p.normal.z, p.d, packed, p.inlierCount)
        }
    }
}

/** The room reconstructed from the planes: floor polygon packed (x, z), heights in meters. */
class SnapshotRoom(
    val outlineXZ: FloatArray,
    val floorY: Float,
    val ceilingY: Float,
    val wallCount: Int,
) {
    val height: Float get() = ceilingY - floorY

    /** Floor area in square meters (shoelace). */
    val areaM2: Float
        get() {
            val n = outlineXZ.size / 2
            var s = 0.0
            for (i in 0 until n) {
                val j = (i + 1) % n
                s += outlineXZ[i * 2].toDouble() * outlineXZ[j * 2 + 1] - outlineXZ[j * 2].toDouble() * outlineXZ[i * 2 + 1]
            }
            return abs(s / 2).toFloat()
        }

    companion object {
        fun from(m: RoomModel): SnapshotRoom {
            val pts = m.outline.points
            val a = FloatArray(pts.size * 2)
            pts.forEachIndexed { i, p -> a[i * 2] = p.x; a[i * 2 + 1] = p.y }
            return SnapshotRoom(a, m.floorY, m.ceilingY, m.wallCount)
        }
    }
}

/**
 * Immutable record of one scan, for viewing and export.
 *
 * [points] packed xyz in meters (world frame, +Y up); [quality] one value in 0..1 per point (see [Quality]).
 * Hit counts and confidence are inputs of [quality] only and are not kept (the PLY stores quality).
 */
class ScanSnapshot(
    val id: String,
    val projectId: String?,
    /** Epoch milliseconds. */
    val createdAt: Long,
    val points: FloatArray,
    val quality: FloatArray,
    val planes: List<SnapshotPlane>,
    val room: SnapshotRoom?,
) {
    init {
        require(points.size % 3 == 0) { "points must be packed xyz" }
        require(quality.size == points.size / 3) { "one quality per point" }
    }

    val pointCount: Int get() = quality.size

    /**
     * Per point: index into [planes] of the nearest plane within [PLANE_BAND_M] meters, or -1.
     * Used to colour the cloud "by kind". O(points x planes).
     */
    fun planeIndexPerPoint(): IntArray = IntArray(pointCount) { i ->
        val x = points[i * 3]; val y = points[i * 3 + 1]; val z = points[i * 3 + 2]
        var best = -1
        var bestD = PLANE_BAND_M
        for ((k, p) in planes.withIndex()) {
            val dist = abs(p.distanceTo(x, y, z))
            if (dist <= bestD) { bestD = dist; best = k }
        }
        best
    }

    /** Axis-aligned bounds as (minX, minY, minZ, maxX, maxY, maxZ), or null when empty. */
    fun bounds(): FloatArray? {
        if (pointCount == 0 && planes.isEmpty()) return null
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        fun add(x: Float, y: Float, z: Float) {
            if (x < b[0]) b[0] = x
            if (y < b[1]) b[1] = y
            if (z < b[2]) b[2] = z
            if (x > b[3]) b[3] = x
            if (y > b[4]) b[4] = y
            if (z > b[5]) b[5] = z
        }
        for (i in 0 until pointCount) add(points[i * 3], points[i * 3 + 1], points[i * 3 + 2])
        for (p in planes) for (i in 0 until p.vertexCount) add(p.outline[i * 3], p.outline[i * 3 + 1], p.outline[i * 3 + 2])
        return b
    }

    companion object {
        const val PLANE_BAND_M = 0.03f

        /**
         * Builds a snapshot from the live [cloud]. Voxels with at least [minHits] hits are kept (default 1 so
         * single-hit, red voxels are visible); if more than [maxPoints] remain they are thinned evenly.
         * Per-point hit count is read from the voxel containing the point (>= 1). [confidence], when given,
         * must have one value in 0..1 per kept point (after thinning) - ARCore depth confidence is the intended source.
         */
        fun from(
            cloud: VoxelCloud,
            planes: List<ExtractedPlane>,
            room: RoomModel?,
            id: String,
            projectId: String? = null,
            createdAt: Long = System.currentTimeMillis(),
            minHits: Int = 1,
            maxPoints: Int = 200_000,
            confidence: FloatArray? = null,
        ): ScanSnapshot {
            val all = cloud.points(minHits)
            val n = all.size / 3
            val take = minOf(n, maxPoints)
            val pts = FloatArray(take * 3)
            val hits = IntArray(take)
            for (i in 0 until take) {
                val k = if (take == n) i else (i.toLong() * n / take).toInt()
                val x = all[k * 3]; val y = all[k * 3 + 1]; val z = all[k * 3 + 2]
                pts[i * 3] = x; pts[i * 3 + 1] = y; pts[i * 3 + 2] = z
                hits[i] = cloud.hitsAt(x, y, z).coerceAtLeast(1)
            }
            require(confidence == null || confidence.size == take) { "confidence needs one value per kept point" }
            val q = FloatArray(take) { Quality.of(hits[it], confidence?.get(it)) }
            return ScanSnapshot(id, projectId, createdAt, pts, q, planes.map(SnapshotPlane::from), room?.let(SnapshotRoom::from))
        }
    }
}

/**
 * Quality of one point, 0 (weak) .. 1 (measured well).
 *
 * hitScore = clamp((hits - 1) / 9, 0, 1): one observation = 0, ten or more = 1 (each hit is a separate
 * depth sample that landed in the same 2 cm voxel, so ten agreeing samples is a well-measured surface).
 * quality = hitScore                     when no confidence is available,
 *         = hitScore * (0.5 + 0.5 * c)   with depth confidence c in 0..1 (low confidence halves the score at most).
 */
object Quality {
    const val FULL_HITS = 10

    fun of(hits: Int, confidence: Float? = null): Float {
        val hitScore = ((hits - 1).toFloat() / (FULL_HITS - 1)).coerceIn(0f, 1f)
        return if (confidence == null) hitScore else hitScore * (0.5f + 0.5f * confidence.coerceIn(0f, 1f))
    }
}

/** The red -> yellow -> green ramp of the quality overlay, 0..255 channels (red at 0, yellow at 0.5, green at 1). */
object QualityRamp {
    fun r(q: Float): Int = (255 * (2f * (1f - q.coerceIn(0f, 1f))).coerceAtMost(1f)).roundToInt()
    fun g(q: Float): Int = (255 * (2f * q.coerceIn(0f, 1f)).coerceAtMost(1f)).roundToInt()

    /** Packed 0xRRGGBB. */
    fun rgb(q: Float): Int = (r(q) shl 16) or (g(q) shl 8)
}

/** Fill colours of the surfaces by kind, packed 0xRRGGBB: floor green, wall blue, ceiling purple, other grey. */
object KindColors {
    const val UNASSIGNED = 0x8E8E93

    fun rgb(kind: PlaneKind): Int = when (kind) {
        PlaneKind.FLOOR -> 0x34C759
        PlaneKind.WALL -> 0x3478F6
        PlaneKind.CEILING -> 0xAF52DE
        PlaneKind.OTHER -> UNASSIGNED
    }

    fun label(kind: PlaneKind): String = when (kind) {
        PlaneKind.FLOOR -> "floor"
        PlaneKind.WALL -> "wall"
        PlaneKind.CEILING -> "ceiling"
        PlaneKind.OTHER -> "other"
    }
}
