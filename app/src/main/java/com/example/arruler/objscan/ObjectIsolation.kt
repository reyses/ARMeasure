package com.example.arruler.objscan

import kotlin.math.floor
import kotlin.math.sqrt

/** Open-addressing Long -> Int map (no boxing). Keys must not be Long.MIN_VALUE. */
internal class LongIntMap(expected: Int) {
    private var cap = Integer.highestOneBit((expected.coerceAtLeast(8) * 2) - 1) shl 1
    private var keys = LongArray(cap) { EMPTY }
    private var vals = IntArray(cap)
    var size = 0
        private set

    private fun slot(k: Long, mask: Int): Int {
        var h = k * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return h.toInt() and mask
    }

    operator fun get(k: Long): Int {
        val mask = cap - 1
        var i = slot(k, mask)
        while (true) {
            val kk = keys[i]
            if (kk == k) return vals[i]
            if (kk == EMPTY) return -1
            i = (i + 1) and mask
        }
    }

    operator fun set(k: Long, v: Int) {
        if ((size + 1) * 2 > cap) grow()
        val mask = cap - 1
        var i = slot(k, mask)
        while (true) {
            val kk = keys[i]
            if (kk == k) { vals[i] = v; return }
            if (kk == EMPTY) { keys[i] = k; vals[i] = v; size++; return }
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val ok = keys; val ov = vals
        cap *= 2
        keys = LongArray(cap) { EMPTY }; vals = IntArray(cap); size = 0
        for (j in ok.indices) if (ok[j] != EMPTY) set(ok[j], ov[j])
    }

    companion object { const val EMPTY = Long.MIN_VALUE }
}

/** Packs three non-negative cell indices (each < 2^21) into one Long key. */
internal fun cellKey(ix: Int, iy: Int, iz: Int): Long =
    (ix.toLong() shl 42) or (iy.toLong() shl 21) or iz.toLong()

/**
 * Uniform-grid spatial hash over a packed xyz array: cells are [cell] meters; points of a cell are
 * stored contiguously (counting sort), cell lookup is O(1).
 */
internal class PointGrid(private val pts: FloatArray, private val n: Int, val cell: Float) {
    private val minX: Float; private val minY: Float; private val minZ: Float
    private val map: LongIntMap
    private val start: IntArray
    private val items: IntArray
    private val ci = IntArray(n * 3)

    init {
        var x0 = Float.MAX_VALUE; var y0 = x0; var z0 = x0
        for (i in 0 until n) {
            x0 = minOf(x0, pts[i * 3]); y0 = minOf(y0, pts[i * 3 + 1]); z0 = minOf(z0, pts[i * 3 + 2])
        }
        // two cells of padding so neighbour probes never go negative
        minX = x0 - 2 * cell; minY = y0 - 2 * cell; minZ = z0 - 2 * cell
        map = LongIntMap(n / 2 + 8)
        val cellOf = IntArray(n)
        var cells = 0
        val counts = ArrayList<Int>()
        val inv = 1f / cell
        for (i in 0 until n) {
            val ix = floor((pts[i * 3] - minX) * inv).toInt()
            val iy = floor((pts[i * 3 + 1] - minY) * inv).toInt()
            val iz = floor((pts[i * 3 + 2] - minZ) * inv).toInt()
            ci[i * 3] = ix; ci[i * 3 + 1] = iy; ci[i * 3 + 2] = iz
            val key = cellKey(ix, iy, iz)
            var id = map[key]
            if (id < 0) { id = cells++; map[key] = id; counts.add(0) }
            counts[id] = counts[id] + 1
            cellOf[i] = id
        }
        start = IntArray(cells + 1)
        for (c in 0 until cells) start[c + 1] = start[c] + counts[c]
        val fill = IntArray(cells)
        items = IntArray(n)
        for (i in 0 until n) { val c = cellOf[i]; items[start[c] + fill[c]++] = i }
    }

    /**
     * Indices of all points within [radius] of point [i] (including i), written to [out] (capped at its size);
     * requires radius <= cell. Returns the count.
     */
    fun within(i: Int, radius: Float, out: IntArray): Int {
        val px = pts[i * 3]; val py = pts[i * 3 + 1]; val pz = pts[i * 3 + 2]
        val cx = ci[i * 3]; val cy = ci[i * 3 + 1]; val cz = ci[i * 3 + 2]
        val r2 = radius * radius
        var c = 0
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val id = map[cellKey(cx + dx, cy + dy, cz + dz)]
            if (id < 0) continue
            for (s in start[id] until start[id + 1]) {
                val j = items[s]
                val ex = pts[j * 3] - px; val ey = pts[j * 3 + 1] - py; val ez = pts[j * 3 + 2] - pz
                if (ex * ex + ey * ey + ez * ez <= r2 && c < out.size) out[c++] = j
            }
        }
        return c
    }

    /**
     * Mean distance to the [k] nearest other points, or +Infinity when fewer than k were found within
     * [maxRing] cell rings. [best] is caller scratch of size >= k.
     */
    fun meanKnnDistance(i: Int, k: Int, maxRing: Int, best: DoubleArray): Double {
        val px = pts[i * 3]; val py = pts[i * 3 + 1]; val pz = pts[i * 3 + 2]
        val cx = ci[i * 3]; val cy = ci[i * 3 + 1]; val cz = ci[i * 3 + 2]
        var found = 0
        for (r in 0..maxRing) {
            for (dx in -r..r) for (dy in -r..r) {
                val edge = dx == -r || dx == r || dy == -r || dy == r
                var dz = -r
                while (dz <= r) {
                    val id = map[cellKey(cx + dx, cy + dy, cz + dz)]
                    if (id >= 0) {
                        for (s in start[id] until start[id + 1]) {
                            val j = items[s]
                            if (j == i) continue
                            val ex = (pts[j * 3] - px).toDouble()
                            val ey = (pts[j * 3 + 1] - py).toDouble()
                            val ez = (pts[j * 3 + 2] - pz).toDouble()
                            val d2 = ex * ex + ey * ey + ez * ez
                            if (found < k) {
                                var p = found++
                                while (p > 0 && best[p - 1] > d2) { best[p] = best[p - 1]; p-- }
                                best[p] = d2
                            } else if (d2 < best[k - 1]) {
                                var p = k - 1
                                while (p > 0 && best[p - 1] > d2) { best[p] = best[p - 1]; p-- }
                                best[p] = d2
                            }
                        }
                    }
                    dz += if (edge || dz == r) 1 else if (dz == -r) 2 * r else 1
                }
            }
            if (found == k) {
                val reach = r * cell.toDouble()
                if (best[k - 1] <= reach * reach) break
            }
        }
        if (found < k) return Double.POSITIVE_INFINITY
        var s = 0.0
        for (q in 0 until k) s += sqrt(best[q])
        return s / k
    }
}

/** Counts of points kept after each [ObjectIsolation] stage. Removed per stage = previous - this. */
data class IsolationStats(
    val input: Int,
    val afterHits: Int,
    val afterBox: Int,
    val afterSupport: Int,
    val afterOutliers: Int,
    val afterComponent: Int
) {
    val removedByHits get() = input - afterHits
    val removedByBox get() = afterHits - afterBox
    val removedBySupport get() = afterBox - afterSupport
    val removedByOutliers get() = afterSupport - afterOutliers
    val removedByComponent get() = afterOutliers - afterComponent
}

class IsolationResult(val points: FloatArray, val hits: IntArray, val stats: IsolationStats) {
    val count: Int get() = points.size / 3
}

/**
 * Cuts an object out of a world point cloud.
 *
 * Stages: (0) drop voxels with fewer than [minHits] hits; (1) keep points inside the user box;
 * (2) drop points within [supportMargin] of the support plane; (3) statistical outlier removal: mean distance
 * to the [k] nearest neighbours (voxel-hash search, rings of 2*[voxelSize] cells, at most 5 rings; a point
 * without k neighbours in reach is an outlier), drop where it exceeds mean + [stdRatio] * std of that statistic;
 * (4) keep the largest 26-connected set of voxels of edge [voxelSize].
 *
 * Complexity: O(n) hashing plus O(n * c) for the neighbour search (c = probes of a few cell rings, a few
 * hundred), no sorting. [voxelSize] is the voxel size of the cloud that produced the points.
 */
class ObjectIsolation(
    val voxelSize: Float,
    val supportMargin: Float = 0.015f,
    val k: Int = 8,
    val stdRatio: Float = 2.0f,
    val minHits: Int = 1,
    val denoiseRadius: Float = 0f,
    val denoiseIterations: Int = 3
) {
    constructor(quality: ObjectQuality) : this(
        quality.voxelSize, quality.supportMargin, quality.outlierK, quality.outlierStdRatio, 1, quality.denoiseRadius
    )

    /** [points] packed xyz (world); [hits] per point (null = all 1). */
    fun isolate(input: FloatArray, hits: IntArray?, box: ObjectBox, plane: SupportPlane): IsolationResult {
        val points = if (denoiseRadius > 0f) input.copyOf() else input   // denoise writes in place
        val n0 = points.size / 3
        require(hits == null || hits.size == n0) { "hits size must match the point count" }
        var keep = IntArray(n0) { it }
        var m = n0
        // 0: hit count
        if (minHits > 1 && hits != null) {
            m = filter(keep, m) { hits[it] >= minHits }
        }
        val afterHits = m
        // 1: box (grown by the denoise radius first so wall fuzz keeps its neighbours), denoise, then the exact box
        if (denoiseRadius > 0f) {
            m = filter(keep, m) { box.contains(points[it * 3], points[it * 3 + 1], points[it * 3 + 2], denoiseRadius) }
            val sub = FloatArray(m * 3)
            for (q in 0 until m) { val i = keep[q]; sub[q * 3] = points[i * 3]; sub[q * 3 + 1] = points[i * 3 + 1]; sub[q * 3 + 2] = points[i * 3 + 2] }
            val sm = ObjectDenoise.smooth(sub, denoiseRadius, denoiseIterations)
            for (q in 0 until m) { val i = keep[q]; points[i * 3] = sm[q * 3]; points[i * 3 + 1] = sm[q * 3 + 1]; points[i * 3 + 2] = sm[q * 3 + 2] }
        }
        m = filter(keep, m) { box.contains(points[it * 3], points[it * 3 + 1], points[it * 3 + 2]) }
        val afterBox = m
        // 2: support plane
        m = filter(keep, m) { plane.signedDistance(points[it * 3], points[it * 3 + 1], points[it * 3 + 2]) > supportMargin }
        val afterSupport = m
        // 3: outliers
        if (m > k + 1) {
            val sub = FloatArray(m * 3)
            for (q in 0 until m) {
                val i = keep[q]
                sub[q * 3] = points[i * 3]; sub[q * 3 + 1] = points[i * 3 + 1]; sub[q * 3 + 2] = points[i * 3 + 2]
            }
            val keepSub = outlierMask(sub, m)
            var w = 0
            for (q in 0 until m) if (keepSub[q]) keep[w++] = keep[q]
            m = w
        }
        val afterOutliers = m
        // 4: largest connected component
        if (m > 0) {
            val sub = FloatArray(m * 3)
            for (q in 0 until m) {
                val i = keep[q]
                sub[q * 3] = points[i * 3]; sub[q * 3 + 1] = points[i * 3 + 1]; sub[q * 3 + 2] = points[i * 3 + 2]
            }
            val inLargest = largestComponent(sub, m)
            var w = 0
            for (q in 0 until m) if (inLargest[q]) keep[w++] = keep[q]
            m = w
        }
        val out = FloatArray(m * 3)
        val outHits = IntArray(m)
        for (q in 0 until m) {
            val i = keep[q]
            out[q * 3] = points[i * 3]; out[q * 3 + 1] = points[i * 3 + 1]; out[q * 3 + 2] = points[i * 3 + 2]
            outHits[q] = hits?.get(i) ?: 1
        }
        return IsolationResult(out, outHits, IsolationStats(n0, afterHits, afterBox, afterSupport, afterOutliers, m))
    }

    private inline fun filter(keep: IntArray, m: Int, pred: (Int) -> Boolean): Int {
        var w = 0
        for (q in 0 until m) { val i = keep[q]; if (pred(i)) keep[w++] = i }
        return w
    }

    /** true = keep. */
    internal fun outlierMask(pts: FloatArray, n: Int): BooleanArray {
        val grid = PointGrid(pts, n, voxelSize * 2f)
        val best = DoubleArray(k)
        val md = DoubleArray(n) { grid.meanKnnDistance(it, k, 5, best) }
        var sum = 0.0; var cnt = 0
        for (v in md) if (v.isFinite()) { sum += v; cnt++ }
        val mask = BooleanArray(n)
        if (cnt == 0) return mask
        val mean = sum / cnt
        var ss = 0.0
        for (v in md) if (v.isFinite()) ss += (v - mean) * (v - mean)
        val std = sqrt(ss / cnt)
        val thr = mean + stdRatio * std
        for (i in 0 until n) mask[i] = md[i].isFinite() && md[i] <= thr
        return mask
    }

    /** true = member of the component with the most points (26-neighbourhood of [voxelSize] voxels). */
    internal fun largestComponent(pts: FloatArray, n: Int): BooleanArray {
        var x0 = Float.MAX_VALUE; var y0 = x0; var z0 = x0
        for (i in 0 until n) {
            x0 = minOf(x0, pts[i * 3]); y0 = minOf(y0, pts[i * 3 + 1]); z0 = minOf(z0, pts[i * 3 + 2])
        }
        val inv = 1f / voxelSize
        val map = LongIntMap(n / 2 + 8)
        val vx = ArrayList<IntArray>()
        val pv = IntArray(n)
        for (i in 0 until n) {
            val ix = floor((pts[i * 3] - x0) * inv).toInt() + 1
            val iy = floor((pts[i * 3 + 1] - y0) * inv).toInt() + 1
            val iz = floor((pts[i * 3 + 2] - z0) * inv).toInt() + 1
            val key = cellKey(ix, iy, iz)
            var id = map[key]
            if (id < 0) { id = vx.size; map[key] = id; vx.add(intArrayOf(ix, iy, iz)) }
            pv[i] = id
        }
        val parent = IntArray(vx.size) { it }
        fun find(a: Int): Int {
            var r = a
            while (parent[r] != r) r = parent[r]
            var c = a
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        for (id in vx.indices) {
            val (ix, iy, iz) = vx[id]
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                // half of the 26 neighbours is enough (the other half finds this voxel)
                if (dx < 0 || (dx == 0 && (dy < 0 || (dy == 0 && dz <= 0)))) continue
                val o = map[cellKey(ix + dx, iy + dy, iz + dz)]
                if (o >= 0) { val a = find(id); val b = find(o); if (a != b) parent[a] = b }
            }
        }
        val size = IntArray(vx.size)
        for (i in 0 until n) size[find(pv[i])]++
        var bestRoot = 0
        for (r in size.indices) if (size[r] > size[bestRoot]) bestRoot = r
        return BooleanArray(n) { find(pv[it]) == bestRoot }
    }
}
