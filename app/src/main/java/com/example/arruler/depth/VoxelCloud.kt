package com.example.arruler.depth

import com.example.arruler.geometry.Vec3
import kotlin.math.floor

/**
 * Point cloud for plane extraction with per-point observation data, all arrays aligned:
 * [xyz] packed meters, [hits] observations per point (null = 1 each), [range] mean depth in meters of the
 * frames that observed the point (null, or NaN per point, = unknown).
 */
class CloudObservations(val xyz: FloatArray, val hits: IntArray? = null, val range: FloatArray? = null) {
    val size: Int get() = xyz.size / 3

    init {
        require(hits == null || hits.size == size) { "hits must match points" }
        require(range == null || range.size == size) { "range must match points" }
    }
}

/**
 * Sparse voxel grid accumulating world-space points. Each occupied voxel keeps the running mean of
 * the points that fell in it, a hit count, and the running mean of the observation range (camera depth
 * of the frames that produced those points) when the caller supplies one; see [observations].
 *
 * Key packing: each axis index (floor(coord / voxelSize)) is biased by 512 and stored in 10 bits,
 * so the grid covers 512 voxels either side of the world origin (10.24 m at 2 cm; the AR session
 * origin is where tracking started). Points outside are rejected ([rejectedOutOfRange] counts them).
 *
 * Eviction rule when [maxVoxels] is reached and a NEW voxel arrives: [EVICTION_CANDIDATES] slots, evenly
 * spread over the pool (cursor + i * count / candidates), are compared and the one with the fewest hits is replaced (ties go to
 * the earlier slot from the cursor), so single-hit noise voxels go first and well-observed surface
 * voxels survive; the cursor then moves past the victim. A point that merges into an existing
 * voxel never evicts anything.
 *
 * Not thread-safe.
 */
class VoxelCloud(val voxelSize: Float = 0.02f, val maxVoxels: Int = 400_000) {

    private val index = HashMap<Int, Int>(1024)
    private var keys = IntArray(1024)
    private var xyz = FloatArray(1024 * 3)
    private var hits = IntArray(1024)
    private var rangeSum = FloatArray(1024)
    private var rangeHits = IntArray(1024)
    private var used = 0
    private var cursor = 0

    var rejectedOutOfRange = 0L
        private set
    var evicted = 0L
        private set

    /** Number of occupied voxels. */
    val count: Int get() = used

    /**
     * Adds one world point; returns false if it was outside the grid range. [range] is the depth (meters)
     * at which the point was observed, NaN when unknown (it then does not enter the voxel's mean range).
     */
    fun add(x: Float, y: Float, z: Float, range: Float = Float.NaN): Boolean {
        val inv = 1f / voxelSize
        val ix = floor(x * inv).toInt() + BIAS
        val iy = floor(y * inv).toInt() + BIAS
        val iz = floor(z * inv).toInt() + BIAS
        if (ix !in 0 until SPAN || iy !in 0 until SPAN || iz !in 0 until SPAN) {
            rejectedOutOfRange++
            return false
        }
        val key = (ix shl 20) or (iy shl 10) or iz
        val slot = index[key]
        if (slot != null) {
            val h = hits[slot] + 1
            hits[slot] = h
            val o = slot * 3
            xyz[o] += (x - xyz[o]) / h
            xyz[o + 1] += (y - xyz[o + 1]) / h
            xyz[o + 2] += (z - xyz[o + 2]) / h
            if (!range.isNaN()) { rangeSum[slot] += range; rangeHits[slot]++ }
            return true
        }
        val target: Int
        if (used < maxVoxels) {
            if (used == keys.size) grow()
            target = used++
        } else {
            target = pickVictim()
            index.remove(keys[target])
            evicted++
        }
        keys[target] = key
        hits[target] = 1
        if (range.isNaN()) { rangeSum[target] = 0f; rangeHits[target] = 0 } else { rangeSum[target] = range; rangeHits[target] = 1 }
        xyz[target * 3] = x; xyz[target * 3 + 1] = y; xyz[target * 3 + 2] = z
        index[key] = target
        return true
    }

    /** Adds [n] points from a packed xyz array; [ranges] (one per point) are their observation depths, if known. */
    fun addAll(points: FloatArray, n: Int = points.size / 3, ranges: FloatArray? = null) {
        if (ranges == null) for (i in 0 until n) add(points[i * 3], points[i * 3 + 1], points[i * 3 + 2])
        else for (i in 0 until n) add(points[i * 3], points[i * 3 + 1], points[i * 3 + 2], ranges[i])
    }

    /**
     * Voxels with at least [minHits] hits as aligned arrays: mean positions, hit counts and mean observation
     * range (NaN where no range was ever supplied), in the same order as [points].
     */
    fun observations(minHits: Int = 1): CloudObservations {
        var n = 0
        for (i in 0 until used) if (hits[i] >= minHits) n++
        val out = FloatArray(n * 3)
        val h = IntArray(n)
        val r = FloatArray(n)
        var k = 0
        for (i in 0 until used) if (hits[i] >= minHits) {
            out[k * 3] = xyz[i * 3]; out[k * 3 + 1] = xyz[i * 3 + 1]; out[k * 3 + 2] = xyz[i * 3 + 2]
            h[k] = hits[i]
            r[k] = if (rangeHits[i] > 0) rangeSum[i] / rangeHits[i] else Float.NaN
            k++
        }
        return CloudObservations(out, h, r)
    }

    /** Mean observation range (meters) of the voxel containing the point; NaN if empty or never ranged. */
    fun rangeAt(x: Float, y: Float, z: Float): Float {
        val slot = slotOf(x, y, z) ?: return Float.NaN
        return if (rangeHits[slot] > 0) rangeSum[slot] / rangeHits[slot] else Float.NaN
    }

    /** Mean positions (packed xyz) of voxels with at least [minHits] hits. */
    fun points(minHits: Int = 1): FloatArray {
        var n = 0
        for (i in 0 until used) if (hits[i] >= minHits) n++
        val out = FloatArray(n * 3)
        var k = 0
        for (i in 0 until used) if (hits[i] >= minHits) {
            out[k++] = xyz[i * 3]; out[k++] = xyz[i * 3 + 1]; out[k++] = xyz[i * 3 + 2]
        }
        return out
    }

    /** Hit count of the voxel containing the point, or 0. */
    fun hitsAt(x: Float, y: Float, z: Float): Int {
        val slot = slotOf(x, y, z) ?: return 0
        return hits[slot]
    }

    private fun slotOf(x: Float, y: Float, z: Float): Int? {
        val inv = 1f / voxelSize
        val ix = floor(x * inv).toInt() + BIAS
        val iy = floor(y * inv).toInt() + BIAS
        val iz = floor(z * inv).toInt() + BIAS
        if (ix !in 0 until SPAN || iy !in 0 until SPAN || iz !in 0 until SPAN) return null
        return index[(ix shl 20) or (iy shl 10) or iz]
    }

    /** Axis-aligned bounds (min, max) of the voxel mean positions, or null when empty. */
    fun bounds(): Pair<Vec3, Vec3>? {
        if (used == 0) return null
        var x0 = Float.MAX_VALUE; var y0 = x0; var z0 = x0
        var x1 = -Float.MAX_VALUE; var y1 = x1; var z1 = x1
        for (i in 0 until used) {
            val x = xyz[i * 3]; val y = xyz[i * 3 + 1]; val z = xyz[i * 3 + 2]
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
            if (z < z0) z0 = z
            if (z > z1) z1 = z
        }
        return Vec3(x0, y0, z0) to Vec3(x1, y1, z1)
    }

    fun clear() {
        index.clear()
        used = 0; cursor = 0
        rejectedOutOfRange = 0; evicted = 0
    }

    private fun pickVictim(): Int {
        var best = cursor
        var bestHits = hits[best]
        val stride = (used / EVICTION_CANDIDATES).coerceAtLeast(1)
        for (i in 1 until EVICTION_CANDIDATES) {
            val c = (cursor + i * stride) % used
            if (hits[c] < bestHits) { best = c; bestHits = hits[c] }
        }
        cursor = (best + 1) % used
        return best
    }

    private fun grow() {
        val n = (keys.size * 2).coerceAtMost(maxVoxels)
        keys = keys.copyOf(n); hits = hits.copyOf(n); xyz = xyz.copyOf(n * 3)
        rangeSum = rangeSum.copyOf(n); rangeHits = rangeHits.copyOf(n)
    }

    companion object {
        private const val BIAS = 512
        private const val SPAN = 1024
        const val EVICTION_CANDIDATES = 8
    }
}
