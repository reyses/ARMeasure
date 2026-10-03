package com.example.arruler.objscan

import com.example.arruler.geometry.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Indexed triangle mesh: [vertices] and [normals] packed xyz, [indices] 3 per triangle (counter-clockwise from outside). */
class TriMesh(val vertices: FloatArray, val normals: FloatArray, val indices: IntArray) {
    val vertexCount: Int get() = vertices.size / 3
    val triangleCount: Int get() = indices.size / 3

    /** Enclosed volume (m^3) by the divergence theorem: sum of signed tetrahedra to the centroid; positive for outward windings. */
    fun volume(): Double {
        if (triangleCount == 0) return 0.0
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (i in 0 until vertexCount) { cx += vertices[i * 3]; cy += vertices[i * 3 + 1]; cz += vertices[i * 3 + 2] }
        cx /= vertexCount; cy /= vertexCount; cz /= vertexCount
        var v = 0.0
        for (t in 0 until triangleCount) {
            val a = indices[t * 3] * 3; val b = indices[t * 3 + 1] * 3; val c = indices[t * 3 + 2] * 3
            val ax = vertices[a] - cx; val ay = vertices[a + 1] - cy; val az = vertices[a + 2] - cz
            val bx = vertices[b] - cx; val by = vertices[b + 1] - cy; val bz = vertices[b + 2] - cz
            val ccx = vertices[c] - cx; val ccy = vertices[c + 1] - cy; val ccz = vertices[c + 2] - cz
            v += ax * (by * ccz - bz * ccy) - ay * (bx * ccz - bz * ccx) + az * (bx * ccy - by * ccx)
        }
        return v / 6.0
    }

    fun surfaceArea(): Double {
        var s = 0.0
        for (t in 0 until triangleCount) {
            val a = indices[t * 3] * 3; val b = indices[t * 3 + 1] * 3; val c = indices[t * 3 + 2] * 3
            val ux = vertices[b] - vertices[a]; val uy = vertices[b + 1] - vertices[a + 1]; val uz = vertices[b + 2] - vertices[a + 2]
            val vx = vertices[c] - vertices[a]; val vy = vertices[c + 1] - vertices[a + 1]; val vz = vertices[c + 2] - vertices[a + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            s += 0.5 * sqrt((cx * cx + cy * cy + cz * cz).toDouble())
        }
        return s
    }

    private fun edgeUse(): HashMap<Long, IntArray> {
        // value: [0] = number of triangles using the undirected edge, [1] = (# a->b) - (# b->a) for a<b
        val m = HashMap<Long, IntArray>()
        for (t in 0 until triangleCount) {
            for (e in 0..2) {
                val a = indices[t * 3 + e]; val b = indices[t * 3 + (e + 1) % 3]
                val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
                val r = m.getOrPut(lo * vertexCount + hi) { IntArray(2) }
                r[0]++
                r[1] += if (a < b) 1 else -1
            }
        }
        return m
    }

    /** Undirected edges not used by exactly 2 triangles (0 for a closed 2-manifold). */
    fun badEdgeCount(): Int = edgeUse().values.count { it[0] != 2 }

    /** Edges whose two triangles traverse it in the same direction (0 when the winding is consistent). */
    fun inconsistentEdgeCount(): Int = edgeUse().values.count { it[0] == 2 && it[1] != 0 }

    /** Wavefront OBJ text: v, vn, then f a//a b//b c//c (1-based, one line per triangle). */
    fun toObj(name: String = "object"): String {
        val sb = StringBuilder(vertexCount * 48 + triangleCount * 24)
        sb.append("# ARMeasure object scan, meters\no ").append(name).append('\n')
        for (i in 0 until vertexCount) sb.append("v ").append(vertices[i * 3]).append(' ').append(vertices[i * 3 + 1]).append(' ').append(vertices[i * 3 + 2]).append('\n')
        for (i in 0 until vertexCount) sb.append("vn ").append(normals[i * 3]).append(' ').append(normals[i * 3 + 1]).append(' ').append(normals[i * 3 + 2]).append('\n')
        for (t in 0 until triangleCount) {
            val a = indices[t * 3] + 1; val b = indices[t * 3 + 1] + 1; val c = indices[t * 3 + 2] + 1
            sb.append("f ").append(a).append("//").append(a).append(' ').append(b).append("//").append(b).append(' ').append(c).append("//").append(c).append('\n')
        }
        return sb.toString()
    }

    /** Binary little-endian PLY: float x y z nx ny nz per vertex, then "uchar 3, int a b c" faces. */
    fun toBinaryPly(): ByteArray {
        val header = "ply\nformat binary_little_endian 1.0\ncomment ARMeasure object scan, meters\n" +
            "element vertex $vertexCount\nproperty float x\nproperty float y\nproperty float z\n" +
            "property float nx\nproperty float ny\nproperty float nz\n" +
            "element face $triangleCount\nproperty list uchar int vertex_indices\nend_header\n"
        val hb = header.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(hb.size + vertexCount * 24 + triangleCount * 13).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(hb)
        for (i in 0 until vertexCount) {
            buf.putFloat(vertices[i * 3]).putFloat(vertices[i * 3 + 1]).putFloat(vertices[i * 3 + 2])
            buf.putFloat(normals[i * 3]).putFloat(normals[i * 3 + 1]).putFloat(normals[i * 3 + 2])
        }
        for (t in 0 until triangleCount) {
            buf.put(3.toByte()).putInt(indices[t * 3]).putInt(indices[t * 3 + 1]).putInt(indices[t * 3 + 2])
        }
        return buf.array()
    }

    companion object {
        /** Mesh with area-weighted per-vertex normals computed from the triangles. */
        fun withNormals(vertices: FloatArray, indices: IntArray): TriMesh {
            val nrm = FloatArray(vertices.size)
            for (t in 0 until indices.size / 3) {
                val a = indices[t * 3] * 3; val b = indices[t * 3 + 1] * 3; val c = indices[t * 3 + 2] * 3
                val ux = vertices[b] - vertices[a]; val uy = vertices[b + 1] - vertices[a + 1]; val uz = vertices[b + 2] - vertices[a + 2]
                val vx = vertices[c] - vertices[a]; val vy = vertices[c + 1] - vertices[a + 1]; val vz = vertices[c + 2] - vertices[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (o in intArrayOf(a, b, c)) { nrm[o] += nx; nrm[o + 1] += ny; nrm[o + 2] += nz }
            }
            for (i in 0 until vertices.size / 3) {
                val l = sqrt(nrm[i * 3] * nrm[i * 3] + nrm[i * 3 + 1] * nrm[i * 3 + 1] + nrm[i * 3 + 2] * nrm[i * 3 + 2])
                if (l > 1e-20f) { nrm[i * 3] /= l; nrm[i * 3 + 1] /= l; nrm[i * 3 + 2] /= l }
            }
            return TriMesh(vertices, nrm, indices)
        }
    }
}

/**
 * Builds a watertight-ish mesh of the isolated object.
 *
 * Pipeline in the box frame (x, height above plane, z), cells of [voxelSize]:
 *  1. splat the points into a boolean occupancy grid (padded by dilate + 2 cells; [supportMargin] cells already
 *     removed by isolation are restored by the column fill);
 *  2. morphological closing with radius [dilate] cells (dilate, fill, erode) so single missing voxels of a noisy shell do not
 *     leave holes, without growing the object;
 *  3. column fill: every occupied (x, z) column is solid from the support plane to its topmost occupied cell (a shell of
 *     surface points would otherwise mesh as a hollow double wall; like the occupancy volume this fills overhangs);
 *  4. [blurPasses] separable [1 2 1]/4 Gaussian-like passes of the 0/1 field;
 *  5. marching cubes at [iso]. The splat marks the cell that CONTAINS the surface, so a 0.5 iso would put the
 *     face at the outer cell border (on average half a voxel too far out); a flat face blurs to 0.75 on its last
 *     filled cell and 0.25 beyond, so iso 0.7 puts it ~0.4 voxel back, close to the cell centre where the real surface is on average.
 * The mesh is transformed to world space. Grid cap: [maxCells] (returns null above it, ~4 bytes/cell for the field).
 */
object ObjectMeshBuilder {
    fun build(
        points: FloatArray, box: ObjectBox, plane: SupportPlane, voxelSize: Float,
        dilate: Int = 1, blurPasses: Int = 1, iso: Float = 0.7f, maxCells: Int = 40_000_000
    ): TriMesh? {
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
        val ny = floor(h1 / voxelSize).toInt() + 1 + 2 * pad   // pad layers below the plane and above the top
        if (nx.toLong() * ny * nz > maxCells) return null
        val gx0 = x0 - pad * voxelSize; val gz0 = z0 - pad * voxelSize
        var occ = BooleanArray(nx * ny * nz)
        fun at(i: Int, j: Int, k: Int) = i + nx * (j + ny * k)
        for (p in 0 until n) {
            val i = floor((lx[p] - gx0) / voxelSize).toInt()
            val j = floor(hy[p] / voxelSize).toInt() + pad
            val k = floor((lz[p] - gz0) / voxelSize).toInt()
            occ[at(i, j, k)] = true
        }
        // 2a. dilate
        occ = morph(occ, nx, ny, nz, dilate, true, pad)
        // 3. column fill from the plane layer (j = pad) up to the top
        for (k in 0 until nz) for (i in 0 until nx) {
            var top = -1
            for (j in ny - 1 downTo pad) if (occ[at(i, j, k)]) { top = j; break }
            for (j in 0 until ny) occ[at(i, j, k)] = top >= 0 && j in pad..top
        }
        // 2b. erode (ground below the plane counts as solid so the base is not eaten)
        occ = morph(occ, nx, ny, nz, dilate, false, pad)
        for (k in 0 until nz) for (i in 0 until nx) for (j in 0 until pad) occ[at(i, j, k)] = false

        var f = FloatArray(occ.size) { if (occ[it]) 1f else 0f }
        repeat(blurPasses) { f = blur(f, nx, ny, nz) }
        val local = MarchingCubes.extract(
            f, nx, ny, nz, iso, voxelSize,
            gx0 + voxelSize / 2, (0.5f - pad) * voxelSize, gz0 + voxelSize / 2
        )
        if (local.triangleCount == 0) return null
        val wv = FloatArray(local.vertices.size)
        for (i in 0 until local.vertexCount) {
            val w = box.toWorld(Vec3(local.vertices[i * 3], local.vertices[i * 3 + 1], local.vertices[i * 3 + 2]))
            wv[i * 3] = w.x; wv[i * 3 + 1] = w.y; wv[i * 3 + 2] = w.z
        }
        return TriMesh.withNormals(wv, local.indices)
    }

    /** Cube-structuring-element dilation (grow = true) or erosion; separable per axis. */
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
                    else if (jj < 0) !grow   // below the grid: ground (solid) for erosion, empty for dilation
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
                val a = when (axis) { 0 -> if (i > 0) cur[i - 1 + nx * (j + ny * k)] else 0f
                    1 -> if (j > 0) cur[i + nx * (j - 1 + ny * k)] else 0f
                    else -> if (k > 0) cur[i + nx * (j + ny * (k - 1))] else 0f }
                val b = when (axis) { 0 -> if (i < nx - 1) cur[i + 1 + nx * (j + ny * k)] else 0f
                    1 -> if (j < ny - 1) cur[i + nx * (j + 1 + ny * k)] else 0f
                    else -> if (k < nz - 1) cur[i + nx * (j + ny * (k + 1))] else 0f }
                dst[i + nx * (j + ny * k)] = 0.25f * a + 0.5f * c + 0.25f * b
            }
            cur = dst
        }
        return cur
    }
}
