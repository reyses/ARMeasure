package com.example.arruler.tandem

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.MarchingCubes
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.SupportPlane
import com.example.arruler.objscan.TriMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * The scalar field [ObjectMeshBuilder] meshes, in the box-local grid: node (i, j, k) at index i + nx*(j + ny*k), world position
 * = box.toWorld(origin + (i, j, k) * cell). Values above the iso level are inside.
 */
class FieldGrid(
    val field: FloatArray, val nx: Int, val ny: Int, val nz: Int, val cell: Float,
    val ox: Float, val oy: Float, val oz: Float, val box: ObjectBox,
)

/**
 * Marching cubes split into z slabs that can run on different phones and be welded back together deterministically.
 *
 * Slab s owns the cube layers [k0, k1); it needs the node layers k0..k1 (ONE node layer of overlap with the neighbour, which is
 * exactly the shared cut plane). Each slab is meshed in grid-index space (cell = 1, origin 0) and shifted by k0, so a vertex on
 * the cut plane gets bit-identical coordinates from both sides (its position depends only on the same two field values and
 * integer offsets, and the two slabs see the cut-plane edges with the same orientation). [stitch] welds vertices by exact
 * coordinates; every cut-plane edge then belongs to exactly one triangle of each slab, so the result is as watertight as the unsplit mesh.
 */
object SlabMesher {

    /** Stages 1-4 of [com.example.arruler.objscan.ObjectMeshBuilder.build] (occupancy, closing, column fill, blur), returned as a field. */
    fun buildField(
        points: FloatArray, box: ObjectBox, plane: SupportPlane, voxelSize: Float,
        dilate: Int = 1, blurPasses: Int = 1, maxCells: Int = 40_000_000,
    ): FieldGrid? {
        val n = points.size / 3
        if (n < 4) return null
        val lx = FloatArray(n); val lz = FloatArray(n); val hy = FloatArray(n)
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        var h1 = 0f
        for (i in 0 until n) {
            val l = box.toLocal(points[i * 3], points[i * 3 + 1], points[i * 3 + 2])
            lx[i] = l.x; lz[i] = l.z
            hy[i] = maxOf(0f, plane.signedDistance(points[i * 3], points[i * 3 + 1], points[i * 3 + 2]))
            x0 = minOf(x0, l.x); x1 = maxOf(x1, l.x); z0 = minOf(z0, l.z); z1 = maxOf(z1, l.z); h1 = maxOf(h1, hy[i])
        }
        val pad = dilate + 2
        val nx = floor((x1 - x0) / voxelSize).toInt() + 1 + 2 * pad
        val nz = floor((z1 - z0) / voxelSize).toInt() + 1 + 2 * pad
        val ny = floor(h1 / voxelSize).toInt() + 1 + 2 * pad
        if (nx.toLong() * ny * nz > maxCells) return null
        val gx0 = x0 - pad * voxelSize; val gz0 = z0 - pad * voxelSize
        var occ = BooleanArray(nx * ny * nz)
        fun at(i: Int, j: Int, k: Int) = i + nx * (j + ny * k)
        for (p in 0 until n) {
            occ[at(floor((lx[p] - gx0) / voxelSize).toInt(), floor(hy[p] / voxelSize).toInt() + pad, floor((lz[p] - gz0) / voxelSize).toInt())] = true
        }
        occ = morph(occ, nx, ny, nz, dilate, true, pad)
        for (k in 0 until nz) for (i in 0 until nx) {
            var top = -1
            for (j in ny - 1 downTo pad) if (occ[at(i, j, k)]) { top = j; break }
            for (j in 0 until ny) occ[at(i, j, k)] = top >= 0 && j in pad..top
        }
        occ = morph(occ, nx, ny, nz, dilate, false, pad)
        for (k in 0 until nz) for (i in 0 until nx) for (j in 0 until pad) occ[at(i, j, k)] = false
        var f = FloatArray(occ.size) { if (occ[it]) 1f else 0f }
        repeat(blurPasses) { f = blur(f, nx, ny, nz) }
        return FieldGrid(f, nx, ny, nz, voxelSize, gx0 + voxelSize / 2, (0.5f - pad) * voxelSize, gz0 + voxelSize / 2, box)
    }

    /** Cube-layer ranges [k0, k1) covering 0 until nz-1, sized in proportion to [weights] (each slab at least one layer). */
    fun ranges(nz: Int, weights: List<Double>): List<IntRange> {
        val layers = nz - 1
        require(layers >= 1 && weights.isNotEmpty()) { "nothing to split" }
        val parts = minOf(weights.size, layers)
        val w = weights.take(parts)
        val total = w.sum()
        val out = ArrayList<IntRange>()
        var start = 0; var acc = 0.0
        for (i in 0 until parts) {
            acc += w[i]
            val end = if (i == parts - 1) layers else maxOf(start + 1, minOf(layers - (parts - 1 - i), Math.round(layers * acc / total).toInt()))
            out += start until end   // cube layers start..end-1, stored as [start, end)
            start = end
        }
        return out
    }

    /** Node layers [k0, k1] of the field (k1 - k0 + 1 layers) as a standalone array, the payload of an MC_SLAB task. */
    fun slabField(g: FieldGrid, k0: Int, k1: Int): FloatArray = g.field.copyOfRange(k0 * g.nx * g.ny, (k1 + 1) * g.nx * g.ny)

    /**
     * Marching cubes on a slab: [sub] holds node layers k0..k0+[subLayers]-1. Returns a mesh in GLOBAL grid-index space
     * (x, y, z in cells, z already shifted by [k0]); normals are not meaningful.
     */
    fun extractIndexSpace(sub: FloatArray, nx: Int, ny: Int, subLayers: Int, k0: Int, iso: Float): TriMesh {
        val m = MarchingCubes.extract(sub, nx, ny, subLayers, iso, 1f)
        val v = m.vertices.copyOf()
        for (i in 0 until m.vertexCount) v[i * 3 + 2] += k0.toFloat()
        return TriMesh(v, m.normals, m.indices)
    }

    /** Welds slab meshes (index space) by exact coordinates and maps them into world space. */
    fun stitch(slabs: List<TriMesh>, g: FieldGrid): TriMesh {
        val ids = HashMap<Triple<Int, Int, Int>, Int>()
        var verts = FloatArray(3 * 1024); var nv = 0
        var idx = IntArray(3 * 1024); var ni = 0
        for (s in slabs) {
            val remap = IntArray(s.vertexCount)
            for (i in 0 until s.vertexCount) {
                val x = s.vertices[i * 3]; val y = s.vertices[i * 3 + 1]; val z = s.vertices[i * 3 + 2]
                val key = Triple(x.toRawBits(), y.toRawBits(), z.toRawBits())
                val got = ids[key]
                if (got != null) { remap[i] = got; continue }
                if ((nv + 1) * 3 > verts.size) verts = verts.copyOf(verts.size * 2)
                verts[nv * 3] = x; verts[nv * 3 + 1] = y; verts[nv * 3 + 2] = z
                ids[key] = nv; remap[i] = nv++
            }
            if (ni + s.indices.size > idx.size) idx = idx.copyOf(maxOf(idx.size * 2, ni + s.indices.size))
            for (t in s.indices) idx[ni++] = remap[t]
        }
        val wv = FloatArray(nv * 3)
        for (i in 0 until nv) {
            val lx = g.ox + verts[i * 3] * g.cell; val ly = g.oy + verts[i * 3 + 1] * g.cell; val lz = g.oz + verts[i * 3 + 2] * g.cell
            val w = g.box.toWorld(Vec3(lx, ly, lz))
            wv[i * 3] = w.x; wv[i * 3 + 1] = w.y; wv[i * 3 + 2] = w.z
        }
        return TriMesh.withNormals(wv, idx.copyOf(ni))
    }

    /** Whole job in one process: field, slabs by [weights], welded mesh. Null when the field is empty or the grid is too large. */
    fun mesh(
        points: FloatArray, box: ObjectBox, plane: SupportPlane, voxelSize: Float, weights: List<Double> = listOf(1.0),
        dilate: Int = 1, blurPasses: Int = 1, iso: Float = 0.7f, maxCells: Int = 40_000_000,
    ): TriMesh? {
        val g = buildField(points, box, plane, voxelSize, dilate, blurPasses, maxCells) ?: return null
        val pieces = ranges(g.nz, weights).map { r ->
            extractIndexSpace(slabField(g, r.first, r.last + 1), g.nx, g.ny, r.last + 2 - r.first, r.first, iso)
        }
        val m = stitch(pieces, g)
        return if (m.triangleCount == 0) null else m
    }

    // ---- wire format of a field slab and a mesh piece (little endian) -------------------------------------------------

    /** 20 byte header, then the field floats deflated (the field is almost all 0 or 1, so it shrinks about 40x). */
    fun writeFieldSlab(nx: Int, ny: Int, subLayers: Int, k0: Int, iso: Float, sub: FloatArray): ByteArray {
        val raw = ByteBuffer.allocate(sub.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in sub) raw.putFloat(f)
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(nx).putInt(ny).putInt(subLayers).putInt(k0).putFloat(iso).array())
        java.util.zip.DeflaterOutputStream(out, java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)).use { it.write(raw.array()) }
        return out.toByteArray()
    }

    /** Runs the marching cubes of a slab payload and returns the piece payload. */
    fun processFieldSlab(payload: ByteArray): ByteArray {
        val h = ByteBuffer.wrap(payload, 0, 20).order(ByteOrder.LITTLE_ENDIAN)
        val nx = h.getInt(); val ny = h.getInt(); val layers = h.getInt(); val k0 = h.getInt(); val iso = h.getFloat()
        val rawBytes = java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(payload, 20, payload.size - 20)).use { it.readBytes() }
        val b = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val sub = FloatArray(nx * ny * layers) { b.getFloat() }
        return writeMeshPiece(extractIndexSpace(sub, nx, ny, layers, k0, iso))
    }

    fun writeMeshPiece(m: TriMesh): ByteArray {
        val b = ByteBuffer.allocate(8 + m.vertices.size * 4 + m.indices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(m.vertexCount).putInt(m.triangleCount)
        for (f in m.vertices) b.putFloat(f)
        for (i in m.indices) b.putInt(i)
        return b.array()
    }

    fun readMeshPiece(bytes: ByteArray): TriMesh {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val nv = b.getInt(); val nt = b.getInt()
        val v = FloatArray(nv * 3) { b.getFloat() }
        val idx = IntArray(nt * 3) { b.getInt() }
        return TriMesh(v, FloatArray(v.size), idx)
    }

    // ---- verbatim copies of the private helpers of ObjectMeshBuilder -----------------------------------------------------

    private fun morph(src: BooleanArray, nx: Int, ny: Int, nz: Int, r: Int, grow: Boolean, ground: Int): BooleanArray {
        if (r <= 0) return src
        var cur = src
        for (axis in 0..2) {
            val dst = BooleanArray(cur.size)
            for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
                var result = !grow
                for (d in -r..r) {
                    val ii = if (axis == 0) i + d else i
                    val jj = if (axis == 1) j + d else j
                    val kk = if (axis == 2) k + d else k
                    val v = if (ii < 0 || ii >= nx || kk < 0 || kk >= nz || jj >= ny) false
                    else if (jj < 0) !grow
                    else if (!grow && jj < ground) true
                    else cur[ii + nx * (jj + ny * kk)]
                    if (grow && v) { result = true; break }
                    if (!grow && !v) { result = false; break }
                }
                dst[i + nx * (j + ny * k)] = result
            }
            cur = dst
        }
        return cur
    }

    private fun blur(src: FloatArray, nx: Int, ny: Int, nz: Int): FloatArray {
        var cur = src
        for (axis in 0..2) {
            val dst = FloatArray(cur.size)
            for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
                val c = cur[i + nx * (j + ny * k)]
                val a = when (axis) {
                    0 -> if (i > 0) cur[i - 1 + nx * (j + ny * k)] else 0f
                    1 -> if (j > 0) cur[i + nx * (j - 1 + ny * k)] else 0f
                    else -> if (k > 0) cur[i + nx * (j + ny * (k - 1))] else 0f
                }
                val b = when (axis) {
                    0 -> if (i < nx - 1) cur[i + 1 + nx * (j + ny * k)] else 0f
                    1 -> if (j < ny - 1) cur[i + nx * (j + 1 + ny * k)] else 0f
                    else -> if (k < nz - 1) cur[i + nx * (j + ny * (k + 1))] else 0f
                }
                dst[i + nx * (j + ny * k)] = 0.25f * a + 0.5f * c + 0.25f * b
            }
            cur = dst
        }
        return cur
    }
}
