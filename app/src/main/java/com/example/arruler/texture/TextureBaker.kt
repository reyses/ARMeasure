package com.example.arruler.texture

import com.example.arruler.objscan.TriMesh
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A decoded keyframe: [pose] camera to world (16 floats column-major, OpenGL axes), [intrinsics] for the [argb] size. */
class Keyframe(val pose: FloatArray, val intrinsics: Intrinsics, val argb: IntArray) {
    init {
        require(pose.size == 16) { "pose must have 16 floats" }
        require(argb.size == intrinsics.width * intrinsics.height) { "pixel count must match the intrinsics size" }
    }
}

data class BakeOptions(
    val maxAtlas: Int = 2048,
    /** Empty pixels around every chart, filled by gutter dilation. */
    val padding: Int = 2,
    /** Long side of the occlusion z-buffer in pixels. */
    val zBufferLongSide: Int = 160,
    /** Triangles seen at a cosine below this (about 81 degrees) are not usable from that view. */
    val minCos: Float = 0.15f,
    val nearM: Float = 0.02f,
    /** A triangle must lie at least this many pixels inside the image. */
    val marginPx: Float = 1f,
    val solidCellPx: Int = 4,
)

class BakeStats(val seenTriangles: Int, val unseenTriangles: Int, val charts: Int, val solidCells: Int, val scale: Float, val atlasSize: Int, val millis: Long)

/**
 * Result of [TextureBaker.bake]: [mesh] has seam vertices duplicated and its triangles in the SAME ORDER as the source;
 * [uvs] is 2 floats per vertex with the origin at the TOP-LEFT of [atlas] (the Filament / glTF convention; OBJ export flips v);
 * [atlas] is [atlasSize] x [atlasSize] ARGB; [sourceVertex] maps each output vertex to its source vertex.
 */
class BakedMesh(
    val mesh: TriMesh, val uvs: FloatArray, val atlas: IntArray, val atlasSize: Int, val sourceVertex: IntArray, val stats: BakeStats,
    /** Per output triangle: index of the keyframe it was textured from, or -1 if unseen (flat colour from neighbours). */
    val triangleView: IntArray = IntArray(0),
)

/** Bilinear sample of a keyframe at continuous pixel position (u, v) (pixel i covers [i, i+1]); returns opaque ARGB. */
internal fun sampleBilinear(kf: Keyframe, u: Float, v: Float): Int {
    val w = kf.intrinsics.width; val h = kf.intrinsics.height
    val x = (u - 0.5f).coerceIn(0f, w - 1f); val y = (v - 0.5f).coerceIn(0f, h - 1f)
    val x0 = x.toInt(); val y0 = y.toInt()
    val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
    val fx = x - x0; val fy = y - y0
    val a = kf.argb[y0 * w + x0]; val b = kf.argb[y0 * w + x1]; val c = kf.argb[y1 * w + x0]; val d = kf.argb[y1 * w + x1]
    var out = 0xFF shl 24
    for (shift in intArrayOf(16, 8, 0)) {
        val top = ((a shr shift) and 0xFF) * (1 - fx) + ((b shr shift) and 0xFF) * fx
        val bot = ((c shr shift) and 0xFF) * (1 - fx) + ((d shr shift) and 0xFF) * fx
        out = out or ((top * (1 - fy) + bot * fy + 0.5f).toInt().coerceIn(0, 255) shl shift)
    }
    return out
}

/** Camera of one keyframe with the per-vertex projection of a mesh. */
internal class ViewProjection(val kf: Keyframe, vertexCount: Int) {
    private val m = kf.pose
    private val k = kf.intrinsics
    val camX = m[12]; val camY = m[13]; val camZ = m[14]
    val u = FloatArray(vertexCount); val v = FloatArray(vertexCount); val d = FloatArray(vertexCount)

    fun projectAll(verts: FloatArray, n: Int) {
        for (i in 0 until n) project(verts[i * 3], verts[i * 3 + 1], verts[i * 3 + 2], i)
    }

    private fun project(x: Float, y: Float, z: Float, i: Int) {
        val dx = x - camX; val dy = y - camY; val dz = z - camZ
        val xc = m[0] * dx + m[1] * dy + m[2] * dz
        val yc = m[4] * dx + m[5] * dy + m[6] * dz
        val depth = -(m[8] * dx + m[9] * dy + m[10] * dz)
        d[i] = depth
        if (depth > 1e-6f) { u[i] = k.cx + k.fx * xc / depth; v[i] = k.cy - k.fy * yc / depth } else { u[i] = 0f; v[i] = 0f }
    }

    /** Projects a world point: returns false if behind the camera, else fills [out] with u, v, depth. */
    fun projectPoint(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val dx = x - camX; val dy = y - camY; val dz = z - camZ
        val depth = -(m[8] * dx + m[9] * dy + m[10] * dz)
        if (depth <= 1e-6f) return false
        out[0] = k.cx + k.fx * (m[0] * dx + m[1] * dy + m[2] * dz) / depth
        out[1] = k.cy - k.fy * (m[4] * dx + m[5] * dy + m[6] * dz) / depth
        out[2] = depth
        return true
    }
}

/** Coarse inverse-depth buffer for occlusion tests (front-facing triangles only; larger inverse depth wins). */
internal class ZBuffer(imageW: Int, imageH: Int, longSide: Int) {
    val w: Int; val h: Int
    val sx: Float; val sy: Float
    private val iz: FloatArray

    init {
        val scale = longSide.toFloat() / max(imageW, imageH)
        w = max(8, (imageW * scale).toInt()); h = max(8, (imageH * scale).toInt())
        sx = w.toFloat() / imageW; sy = h.toFloat() / imageH
        iz = FloatArray(w * h)
    }

    fun clear() = iz.fill(0f)

    fun draw(u0: Float, v0: Float, d0: Float, u1: Float, v1: Float, d1: Float, u2: Float, v2: Float, d2: Float) {
        val ax = u0 * sx; val ay = v0 * sy; val bx = u1 * sx; val by = v1 * sy; val cx = u2 * sx; val cy = v2 * sy
        val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (abs(area) < 1e-8f) return
        val minX = max(0, floor(min(ax, min(bx, cx)) - 0.5f).toInt()); val maxX = min(w - 1, ceil(max(ax, max(bx, cx))).toInt())
        val minY = max(0, floor(min(ay, min(by, cy)) - 0.5f).toInt()); val maxY = min(h - 1, ceil(max(ay, max(by, cy))).toInt())
        val i0 = 1f / d0; val i1 = 1f / d1; val i2 = 1f / d2
        val inv = 1f / area
        for (y in minY..maxY) {
            val py = y + 0.5f
            for (x in minX..maxX) {
                val px = x + 0.5f
                val w0 = ((cx - bx) * (py - by) - (cy - by) * (px - bx)) * inv
                val w1 = ((ax - cx) * (py - cy) - (ay - cy) * (px - cx)) * inv
                val w2 = 1f - w0 - w1
                if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue
                val z = w0 * i0 + w1 * i1 + w2 * i2
                if (z > iz[y * w + x]) iz[y * w + x] = z
            }
        }
    }

    /** True if a surface point at image position (u, v) and [depth] is not hidden behind something drawn near it. */
    fun visible(u: Float, v: Float, depth: Float, tol: Float): Boolean {
        val bx = (u * sx).toInt().coerceIn(0, w - 1); val by = (v * sy).toInt().coerceIn(0, h - 1)
        var best = 0f
        for (y in max(0, by - 1)..min(h - 1, by + 1)) for (x in max(0, bx - 1)..min(w - 1, bx + 1)) if (iz[y * w + x] > best) best = iz[y * w + x]
        if (best <= 0f) return true
        return depth <= 1f / best + tol
    }
}

/** Triangle edge-neighbour lists in CSR form ([start] has triangleCount + 1 entries). */
internal class Adjacency(val start: IntArray, val nbr: IntArray)

internal fun triangleAdjacency(indices: IntArray, vertexCount: Int): Adjacency {
    val nT = indices.size / 3
    require(vertexCount.toLong() * vertexCount < (1L shl 40) && nT < (1 shl 22)) { "mesh too large for adjacency packing" }
    val keys = LongArray(nT * 3)
    for (t in 0 until nT) for (e in 0..2) {
        val a = indices[t * 3 + e]; val b = indices[t * 3 + (e + 1) % 3]
        keys[t * 3 + e] = ((min(a, b).toLong() * vertexCount + max(a, b)) shl 22) or t.toLong()
    }
    keys.sort()
    val deg = IntArray(nT + 1)
    val mask = (1L shl 22) - 1
    var i = 0
    while (i < keys.size) {
        var j = i
        while (j + 1 < keys.size && (keys[j + 1] shr 22) == (keys[i] shr 22)) j++
        if (j > i && j - i < 8) for (p in i..j) for (q in i..j) if (p != q) deg[(keys[p] and mask).toInt() + 1]++
        i = j + 1
    }
    for (t in 0 until nT) deg[t + 1] += deg[t]
    val fill = deg.copyOf()
    val nbr = IntArray(deg[nT])
    i = 0
    while (i < keys.size) {
        var j = i
        while (j + 1 < keys.size && (keys[j + 1] shr 22) == (keys[i] shr 22)) j++
        if (j > i && j - i < 8) for (p in i..j) for (q in i..j) if (p != q) nbr[fill[(keys[p] and mask).toInt()]++] = (keys[q] and mask).toInt()
        i = j + 1
    }
    return Adjacency(deg.copyOf(nT + 1), nbr)
}

private fun nextPow2(v: Int): Int { var p = 1; while (p < v) p = p shl 1; return p }

object TextureBaker {

    private fun tolFor(cos: Float, depth: Float, fBuf: Float): Float {
        val tan = min(4f, sqrt(max(0f, 1f - cos * cos)) / max(cos, 1e-3f))
        return depth * (2f / fBuf) * tan + 0.003f * depth + 0.0005f
    }

    /**
     * Bakes a texture atlas for [mesh] (counter-clockwise outward triangles) from [keyframes].
     * Per triangle the best view maximises cos(normal, view direction) x projected area among views in which it is
     * front-facing, fully inside the image and not occluded (coarse z-buffer of the mesh from that view).
     * Adjacent triangles with the same view form a chart; charts are shelf-packed into a square atlas of at most
     * [BakeOptions.maxAtlas] pixels (the chart scale shrinks if they do not fit), colours are sampled bilinearly,
     * gutters are dilated by [BakeOptions.padding] pixels; unseen triangles get the average colour of their neighbours.
     */
    fun bake(
        mesh: TriMesh,
        keyframes: List<Keyframe>,
        options: BakeOptions = BakeOptions(),
        progress: ((Float) -> Unit)? = null,
        cancelled: (() -> Boolean)? = null,
    ): BakedMesh {
        val t0 = System.nanoTime()
        val nT = mesh.triangleCount; val nV = mesh.vertexCount
        val verts = mesh.vertices; val idx = mesh.indices
        val nrm = FloatArray(nT * 3); val cen = FloatArray(nT * 3)
        for (t in 0 until nT) {
            val a = idx[t * 3] * 3; val b = idx[t * 3 + 1] * 3; val c = idx[t * 3 + 2] * 3
            val ux = verts[b] - verts[a]; val uy = verts[b + 1] - verts[a + 1]; val uz = verts[b + 2] - verts[a + 2]
            val vx = verts[c] - verts[a]; val vy = verts[c + 1] - verts[a + 1]; val vz = verts[c + 2] - verts[a + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-14f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 0f; nz = 0f }
            nrm[t * 3] = nx; nrm[t * 3 + 1] = ny; nrm[t * 3 + 2] = nz
            cen[t * 3] = (verts[a] + verts[b] + verts[c]) / 3f
            cen[t * 3 + 1] = (verts[a + 1] + verts[b + 1] + verts[c + 1]) / 3f
            cen[t * 3 + 2] = (verts[a + 2] + verts[b + 2] + verts[c + 2]) / 3f
        }

        // 1. best view per triangle
        val bestScore = FloatArray(nT); val bestView = IntArray(nT) { -1 }
        val triUV = FloatArray(nT * 6)
        val cosBuf = FloatArray(nT)
        val tmp = FloatArray(3)
        var zbuf: ZBuffer? = null
        for ((ki, kf) in keyframes.withIndex()) {
            if (cancelled?.invoke() == true) throw CancellationException("bake cancelled")
            val k = kf.intrinsics
            val zb = zbuf?.takeIf { it.w == max(8, (k.width * options.zBufferLongSide.toFloat() / max(k.width, k.height)).toInt()) }
                ?: ZBuffer(k.width, k.height, options.zBufferLongSide).also { zbuf = it }
            val vw = ViewProjection(kf, nV)
            vw.projectAll(verts, nV)
            zb.clear()
            val fBuf = k.fx * zb.sx
            for (t in 0 until nT) {
                val dx = vw.camX - cen[t * 3]; val dy = vw.camY - cen[t * 3 + 1]; val dz = vw.camZ - cen[t * 3 + 2]
                val dl = sqrt(dx * dx + dy * dy + dz * dz)
                val c = if (dl > 1e-9f) (nrm[t * 3] * dx + nrm[t * 3 + 1] * dy + nrm[t * 3 + 2] * dz) / dl else 0f
                cosBuf[t] = c
                if (c <= 0f) continue
                val a = idx[t * 3]; val b = idx[t * 3 + 1]; val cc = idx[t * 3 + 2]
                if (vw.d[a] <= options.nearM || vw.d[b] <= options.nearM || vw.d[cc] <= options.nearM) continue
                zb.draw(vw.u[a], vw.v[a], vw.d[a], vw.u[b], vw.v[b], vw.d[b], vw.u[cc], vw.v[cc], vw.d[cc])
            }
            val mg = options.marginPx
            for (t in 0 until nT) {
                val c = cosBuf[t]
                if (c < options.minCos) continue
                val a = idx[t * 3]; val b = idx[t * 3 + 1]; val cc = idx[t * 3 + 2]
                if (vw.d[a] <= options.nearM || vw.d[b] <= options.nearM || vw.d[cc] <= options.nearM) continue
                val u0 = vw.u[a]; val v0 = vw.v[a]; val u1 = vw.u[b]; val v1 = vw.v[b]; val u2 = vw.u[cc]; val v2 = vw.v[cc]
                if (min(u0, min(u1, u2)) < mg || max(u0, max(u1, u2)) > k.width - mg) continue
                if (min(v0, min(v1, v2)) < mg || max(v0, max(v1, v2)) > k.height - mg) continue
                val area = 0.5f * abs((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0))
                val score = c * area
                if (score <= bestScore[t]) continue
                // occlusion: centroid, plus three inner points for triangles larger than ~2 z-buffer pixels
                val big = max(max(abs(u0 - u1), abs(u0 - u2)), max(abs(v0 - v1), abs(v0 - v2))) * zb.sx > 2f
                var ok = true
                val cx = cen[t * 3]; val cy = cen[t * 3 + 1]; val cz = cen[t * 3 + 2]
                val samples = if (big) 4 else 1
                for (s in 0 until samples) {
                    var px = cx; var py = cy; var pz = cz
                    if (s > 0) {
                        val vi = idx[t * 3 + s - 1] * 3
                        px = cx + 0.8f * (verts[vi] - cx); py = cy + 0.8f * (verts[vi + 1] - cy); pz = cz + 0.8f * (verts[vi + 2] - cz)
                    }
                    if (!vw.projectPoint(px, py, pz, tmp) || !zb.visible(tmp[0], tmp[1], tmp[2], tolFor(c, tmp[2], fBuf))) { ok = false; break }
                }
                if (!ok) continue
                bestScore[t] = score; bestView[t] = ki
                val o = t * 6
                triUV[o] = u0; triUV[o + 1] = v0; triUV[o + 2] = u1; triUV[o + 3] = v1; triUV[o + 4] = u2; triUV[o + 5] = v2
            }
            progress?.invoke(0.6f * (ki + 1) / keyframes.size)
        }

        // 2. triangle colours (centroid sample) and neighbour fill for unseen triangles
        val adj = triangleAdjacency(idx, nV)
        val colR = FloatArray(nT); val colG = FloatArray(nT); val colB = FloatArray(nT)
        val have = BooleanArray(nT)
        var seen = 0
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        for (t in 0 until nT) {
            val vi = bestView[t]
            if (vi < 0) continue
            seen++
            val o = t * 6
            val px = sampleBilinear(keyframes[vi], (triUV[o] + triUV[o + 2] + triUV[o + 4]) / 3f, (triUV[o + 1] + triUV[o + 3] + triUV[o + 5]) / 3f)
            colR[t] = ((px shr 16) and 0xFF).toFloat(); colG[t] = ((px shr 8) and 0xFF).toFloat(); colB[t] = (px and 0xFF).toFloat()
            have[t] = true
            sumR += colR[t]; sumG += colG[t]; sumB += colB[t]
        }
        val avgR = if (seen > 0) (sumR / seen).toFloat() else 128f
        val avgG = if (seen > 0) (sumG / seen).toFloat() else 128f
        val avgB = if (seen > 0) (sumB / seen).toFloat() else 128f
        var pending = IntArray(nT - seen); var np = 0
        for (t in 0 until nT) if (!have[t]) pending[np++] = t
        pending = pending.copyOf(np)
        val nr = FloatArray(nT); val ng = FloatArray(nT); val nb = FloatArray(nT)
        while (pending.isNotEmpty()) {
            val next = IntArray(pending.size); var nn = 0
            val done = IntArray(pending.size); var nd = 0
            for (t in pending) {
                var r = 0f; var g = 0f; var b = 0f; var n = 0
                for (q in adj.start[t] until adj.start[t + 1]) {
                    val o = adj.nbr[q]
                    if (have[o]) { r += colR[o]; g += colG[o]; b += colB[o]; n++ }
                }
                if (n > 0) { nr[t] = r / n; ng[t] = g / n; nb[t] = b / n; done[nd++] = t } else next[nn++] = t
            }
            if (nd == 0) { for (t in pending) { colR[t] = avgR; colG[t] = avgG; colB[t] = avgB; have[t] = true }; break }
            for (i in 0 until nd) { val t = done[i]; colR[t] = nr[t]; colG[t] = ng[t]; colB[t] = nb[t]; have[t] = true }
            pending = next.copyOf(nn)
        }

        // 3. charts: connected components of equal-view triangles
        val parent = IntArray(nT) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        for (t in 0 until nT) {
            if (bestView[t] < 0) continue
            for (q in adj.start[t] until adj.start[t + 1]) {
                val o = adj.nbr[q]
                if (bestView[o] == bestView[t]) { val ra = find(t); val rb = find(o); if (ra != rb) parent[ra] = rb }
            }
        }
        val chartOf = IntArray(nT) { -1 }
        val rootToChart = HashMap<Int, Int>()
        for (t in 0 until nT) if (bestView[t] >= 0) chartOf[t] = rootToChart.getOrPut(find(t)) { rootToChart.size }
        val nC = rootToChart.size
        val cMinU = FloatArray(nC) { Float.MAX_VALUE }; val cMinV = FloatArray(nC) { Float.MAX_VALUE }
        val cMaxU = FloatArray(nC) { -Float.MAX_VALUE }; val cMaxV = FloatArray(nC) { -Float.MAX_VALUE }
        for (t in 0 until nT) {
            val c = chartOf[t]; if (c < 0) continue
            for (i in 0..2) {
                val u = triUV[t * 6 + i * 2]; val v = triUV[t * 6 + i * 2 + 1]
                if (u < cMinU[c]) cMinU[c] = u; if (u > cMaxU[c]) cMaxU[c] = u
                if (v < cMinV[c]) cMinV[c] = v; if (v > cMaxV[c]) cMaxV[c] = v
            }
        }
        // solid cells for unseen triangles, quantised to 4 bits per channel
        val cellOf = IntArray(nT) { -1 }
        val cellKeys = HashMap<Int, Int>()
        val cellRgb = ArrayList<Int>()
        for (t in 0 until nT) {
            if (bestView[t] >= 0) continue
            val q = ((colR[t].toInt() shr 4) shl 8) or ((colG[t].toInt() shr 4) shl 4) or (colB[t].toInt() shr 4)
            cellOf[t] = cellKeys.getOrPut(q) {
                val r = ((q shr 8) and 15) * 17; val g = ((q shr 4) and 15) * 17; val b = (q and 15) * 17
                cellRgb.add((0xFF shl 24) or (r shl 16) or (g shl 8) or b); cellRgb.size - 1
            }
        }
        val nCells = cellRgb.size
        val pad = options.padding
        var totalArea = 0.0; var maxDim = 1f
        for (c in 0 until nC) {
            val w = cMaxU[c] - cMinU[c]; val h = cMaxV[c] - cMinV[c]
            totalArea += (w.toDouble() + 1) * (h + 1); maxDim = max(maxDim, max(w, h) + 1f)
        }
        val cellSide = options.solidCellPx + 2 * pad
        val solidArea = nCells.toDouble() * cellSide * cellSide

        // order by chart height, descending (independent of the scale)
        val items = nC + nCells
        val order = (0 until items).sortedByDescending { if (it < nC) cMaxV[it] - cMinV[it] else 0f }.toIntArray()
        val rx = IntArray(items); val ry = IntArray(items)
        fun tryPack(side: Int, s: Float): Boolean {
            var x = 0; var y = 0; var shelf = 0
            for (item in order) {
                val w: Int; val h: Int
                if (item < nC) { w = ceil((cMaxU[item] - cMinU[item]) * s).toInt() + 1 + 2 * pad; h = ceil((cMaxV[item] - cMinV[item]) * s).toInt() + 1 + 2 * pad }
                else { w = cellSide; h = cellSide }
                if (w > side || h > side) return false
                if (x + w > side) { y += shelf; x = 0; shelf = 0 }
                if (y + h > side) return false
                rx[item] = x; ry[item] = y; x += w; if (h > shelf) shelf = h
            }
            return true
        }
        var side = min(options.maxAtlas, max(64, nextPow2(ceil(sqrt((totalArea + solidArea) * 1.25)).toInt())))
        var scale = 1f
        while (!tryPack(side, scale)) {
            if (side < options.maxAtlas) { side = min(options.maxAtlas, side * 2); continue }
            val fit = sqrt(0.7 * side.toDouble() * side / max(1.0, totalArea)).toFloat()
            scale = min(min(scale * 0.93f, fit), (side - 2f * pad - 2f) / maxDim)
            if (scale < 0.02f) throw IllegalStateException("cannot pack texture charts")
        }

        // 4. rasterise the atlas
        val atlas = IntArray(side * side)
        val filled = BooleanArray(side * side)
        for (t in 0 until nT) {
            val c = chartOf[t]; if (c < 0) continue
            val kf = keyframes[bestView[t]]
            val ox = rx[c] + pad; val oy = ry[c] + pad
            val o = t * 6
            val ax = ox + (triUV[o] - cMinU[c]) * scale; val ay = oy + (triUV[o + 1] - cMinV[c]) * scale
            val bx = ox + (triUV[o + 2] - cMinU[c]) * scale; val by = oy + (triUV[o + 3] - cMinV[c]) * scale
            val cx = ox + (triUV[o + 4] - cMinU[c]) * scale; val cy = oy + (triUV[o + 5] - cMinV[c]) * scale
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (abs(area) < 1e-9f) continue
            val inv = 1f / area
            val x0 = max(0, floor(min(ax, min(bx, cx))).toInt()); val x1 = min(side - 1, ceil(max(ax, max(bx, cx))).toInt())
            val y0 = max(0, floor(min(ay, min(by, cy))).toInt()); val y1 = min(side - 1, ceil(max(ay, max(by, cy))).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val px = x + 0.5f; val py = y + 0.5f
                val w0 = ((cx - bx) * (py - by) - (cy - by) * (px - bx)) * inv
                val w1 = ((ax - cx) * (py - cy) - (ay - cy) * (px - cx)) * inv
                val w2 = 1f - w0 - w1
                if (w0 < -0.01f || w1 < -0.01f || w2 < -0.01f) continue
                atlas[y * side + x] = sampleBilinear(kf, cMinU[c] + (px - ox) / scale, cMinV[c] + (py - oy) / scale)
                filled[y * side + x] = true
            }
        }
        for (cell in 0 until nCells) {
            val id = nC + cell
            for (y in ry[id] until ry[id] + cellSide) for (x in rx[id] until rx[id] + cellSide) { atlas[y * side + x] = cellRgb[cell]; filled[y * side + x] = true }
        }
        // gutter dilation
        repeat(pad) {
            val add = ArrayList<Int>()
            val addColour = ArrayList<Int>()
            for (y in 0 until side) for (x in 0 until side) {
                if (filled[y * side + x]) continue
                var r = 0; var g = 0; var b = 0; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if ((dx != 0 || dy != 0) && xx in 0 until side && yy in 0 until side && filled[yy * side + xx]) {
                        val p = atlas[yy * side + xx]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                    }
                }
                if (n > 0) { add.add(y * side + x); addColour.add((0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)) }
            }
            for (i in add.indices) { atlas[add[i]] = addColour[i]; filled[add[i]] = true }
        }
        val bg = (0xFF shl 24) or (avgR.toInt() shl 16) or (avgG.toInt() shl 8) or avgB.toInt()
        for (i in atlas.indices) if (!filled[i]) atlas[i] = bg

        // 5. output mesh: duplicate vertices per (source vertex, chart or cell)
        val outV = FloatArray(nT * 9); val outN = FloatArray(nT * 9); val outUV = FloatArray(nT * 6)
        val src = IntArray(nT * 3)
        val outIdx = IntArray(nT * 3)
        val map = HashMap<Long, Int>(nT * 2)
        var nOut = 0
        val sideF = side.toFloat()
        val haveNormals = mesh.normals.size == verts.size
        for (t in 0 until nT) {
            val chart = if (chartOf[t] >= 0) chartOf[t] else nC + cellOf[t]
            for (i in 0..2) {
                val v = idx[t * 3 + i]
                val key = chart.toLong() * nV + v
                var id = map[key]
                if (id == null) {
                    id = nOut++
                    map[key] = id
                    src[id] = v
                    outV[id * 3] = verts[v * 3]; outV[id * 3 + 1] = verts[v * 3 + 1]; outV[id * 3 + 2] = verts[v * 3 + 2]
                    if (haveNormals) { outN[id * 3] = mesh.normals[v * 3]; outN[id * 3 + 1] = mesh.normals[v * 3 + 1]; outN[id * 3 + 2] = mesh.normals[v * 3 + 2] }
                    else { outN[id * 3] = nrm[t * 3]; outN[id * 3 + 1] = nrm[t * 3 + 1]; outN[id * 3 + 2] = nrm[t * 3 + 2] }
                    if (chartOf[t] >= 0) {
                        val c = chartOf[t]
                        outUV[id * 2] = (rx[c] + pad + (triUV[t * 6 + i * 2] - cMinU[c]) * scale) / sideF
                        outUV[id * 2 + 1] = (ry[c] + pad + (triUV[t * 6 + i * 2 + 1] - cMinV[c]) * scale) / sideF
                    } else {
                        outUV[id * 2] = (rx[chart] + cellSide / 2f) / sideF
                        outUV[id * 2 + 1] = (ry[chart] + cellSide / 2f) / sideF
                    }
                }
                outIdx[t * 3 + i] = id
            }
        }
        for (i in 0 until nOut * 2) outUV[i] = outUV[i].coerceIn(0f, 1f)
        progress?.invoke(1f)
        val stats = BakeStats(seen, nT - seen, nC, nCells, scale, side, (System.nanoTime() - t0) / 1_000_000)
        return BakedMesh(
            TriMesh(outV.copyOf(nOut * 3), outN.copyOf(nOut * 3), outIdx),
            outUV.copyOf(nOut * 2), atlas, side, src.copyOf(nOut), stats, bestView
        )
    }

    /**
     * Cheap alternative for LOW-tier phones: one colour per vertex (0xFFRRGGBB) from the best view (maximum of
     * cos(vertex normal, view direction) / depth^2 among unoccluded views that contain it). Vertices no view sees
     * take the average of their neighbours.
     */
    fun bakeVertexColors(
        mesh: TriMesh,
        keyframes: List<Keyframe>,
        options: BakeOptions = BakeOptions(),
        cancelled: (() -> Boolean)? = null,
    ): IntArray {
        val nT = mesh.triangleCount; val nV = mesh.vertexCount
        val verts = mesh.vertices; val idx = mesh.indices
        val vn = FloatArray(nV * 3)
        for (t in 0 until nT) {
            val a = idx[t * 3] * 3; val b = idx[t * 3 + 1] * 3; val c = idx[t * 3 + 2] * 3
            val ux = verts[b] - verts[a]; val uy = verts[b + 1] - verts[a + 1]; val uz = verts[b + 2] - verts[a + 2]
            val vx = verts[c] - verts[a]; val vy = verts[c + 1] - verts[a + 1]; val vz = verts[c + 2] - verts[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (p in intArrayOf(a, b, c)) { vn[p] += nx; vn[p + 1] += ny; vn[p + 2] += nz }
        }
        for (i in 0 until nV) {
            val l = sqrt(vn[i * 3] * vn[i * 3] + vn[i * 3 + 1] * vn[i * 3 + 1] + vn[i * 3 + 2] * vn[i * 3 + 2])
            if (l > 1e-14f) { vn[i * 3] /= l; vn[i * 3 + 1] /= l; vn[i * 3 + 2] /= l }
        }
        val bestScore = FloatArray(nV); val bestView = IntArray(nV) { -1 }
        val bu = FloatArray(nV); val bv = FloatArray(nV)
        var zbuf: ZBuffer? = null
        val cosBuf = FloatArray(nV)
        for ((ki, kf) in keyframes.withIndex()) {
            if (cancelled?.invoke() == true) throw CancellationException("bake cancelled")
            val k = kf.intrinsics
            val zb = zbuf?.takeIf { it.w == max(8, (k.width * options.zBufferLongSide.toFloat() / max(k.width, k.height)).toInt()) }
                ?: ZBuffer(k.width, k.height, options.zBufferLongSide).also { zbuf = it }
            val vw = ViewProjection(kf, nV)
            vw.projectAll(verts, nV)
            zb.clear()
            val fBuf = k.fx * zb.sx
            for (i in 0 until nV) {
                val dx = vw.camX - verts[i * 3]; val dy = vw.camY - verts[i * 3 + 1]; val dz = vw.camZ - verts[i * 3 + 2]
                val dl = sqrt(dx * dx + dy * dy + dz * dz)
                cosBuf[i] = if (dl > 1e-9f) (vn[i * 3] * dx + vn[i * 3 + 1] * dy + vn[i * 3 + 2] * dz) / dl else 0f
            }
            for (t in 0 until nT) {
                val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
                if (vw.d[a] <= options.nearM || vw.d[b] <= options.nearM || vw.d[c] <= options.nearM) continue
                // front-facing: counter-clockwise outward in y-up becomes a negative cross on the y-down image
                val cross = (vw.u[b] - vw.u[a]) * (vw.v[c] - vw.v[a]) - (vw.u[c] - vw.u[a]) * (vw.v[b] - vw.v[a])
                if (cross >= 0f) continue
                zb.draw(vw.u[a], vw.v[a], vw.d[a], vw.u[b], vw.v[b], vw.d[b], vw.u[c], vw.v[c], vw.d[c])
            }
            for (i in 0 until nV) {
                val c = cosBuf[i]
                if (c < options.minCos || vw.d[i] <= options.nearM) continue
                if (vw.u[i] < options.marginPx || vw.u[i] > k.width - options.marginPx || vw.v[i] < options.marginPx || vw.v[i] > k.height - options.marginPx) continue
                val score = c / (vw.d[i] * vw.d[i])
                if (score <= bestScore[i]) continue
                if (!zb.visible(vw.u[i], vw.v[i], vw.d[i], tolFor(c, vw.d[i], fBuf))) continue
                bestScore[i] = score; bestView[i] = ki; bu[i] = vw.u[i]; bv[i] = vw.v[i]
            }
        }
        val out = IntArray(nV)
        val have = BooleanArray(nV)
        var sr = 0L; var sg = 0L; var sb = 0L; var ns = 0
        for (i in 0 until nV) if (bestView[i] >= 0) {
            val p = sampleBilinear(keyframes[bestView[i]], bu[i], bv[i])
            out[i] = p; have[i] = true
            sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; ns++
        }
        val avg = if (ns > 0) (0xFF shl 24) or ((sr / ns).toInt() shl 16) or ((sg / ns).toInt() shl 8) or (sb / ns).toInt() else (0xFF shl 24) or 0x808080
        if (ns == nV) return out
        // vertex neighbour lists
        val deg = IntArray(nV + 1)
        for (t in 0 until nT) for (e in 0..2) deg[idx[t * 3 + e] + 1] += 2
        for (i in 0 until nV) deg[i + 1] += deg[i]
        val fill = deg.copyOf(); val nb = IntArray(deg[nV])
        for (t in 0 until nT) for (e in 0..2) {
            val v = idx[t * 3 + e]
            nb[fill[v]++] = idx[t * 3 + (e + 1) % 3]; nb[fill[v]++] = idx[t * 3 + (e + 2) % 3]
        }
        var pending = (0 until nV).filter { !have[it] }.toIntArray()
        while (pending.isNotEmpty()) {
            val next = ArrayList<Int>(); val doneIdx = ArrayList<Int>(); val doneCol = ArrayList<Int>()
            for (v in pending) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (q in deg[v] until deg[v + 1]) { val o = nb[q]; if (have[o]) { val p = out[o]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++ } }
                if (n > 0) { doneIdx.add(v); doneCol.add((0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)) } else next.add(v)
            }
            if (doneIdx.isEmpty()) { for (v in pending) { out[v] = avg; have[v] = true }; break }
            for (i in doneIdx.indices) { out[doneIdx[i]] = doneCol[i]; have[doneIdx[i]] = true }
            pending = next.toIntArray()
        }
        return out
    }
}
