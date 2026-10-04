package com.example.arruler.scan3d

import com.example.arruler.depth.PlaneKind
import com.example.arruler.geometry.ColorRamp
import com.example.arruler.objscan.TexturedMeshData
import com.example.arruler.objscan.TriMesh
import kotlin.math.sqrt

/** How the point cloud is coloured. */
enum class ColourMode(val label: String) { QUALITY("Colour by quality"), KIND("Colour by kind") }

/** Primitive of one drawable part (mapped to Filament's PrimitiveType by the viewer). */
enum class Primitive { POINTS, TRIANGLES }

/**
 * Plain arrays of one drawable mesh: packed xyz [positions], optional packed xyz [normals] and uv [uvs] (one per vertex)
 * and [indices]. Built off the main thread; only the Filament upload happens on it.
 */
class MeshArrays(
    val primitive: Primitive,
    val positions: FloatArray,
    val normals: FloatArray?,
    val uvs: FloatArray?,
    val indices: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
}

/** The material a part is drawn with. */
sealed interface PartMaterial {
    /** Unlit colour, 0xRRGGBB and alpha 0..1. */
    data class Unlit(val rgb: Int, val alpha: Float = 1f) : PartMaterial

    /** Lit colour (the grey object mesh). */
    data class Lit(val rgb: Int) : PartMaterial

    /** The photo atlas of [TexturedMeshData.atlas]. */
    data object Atlas : PartMaterial
}

/** One drawable part of the viewer scene; [key] is stable for the same content. */
class ScenePart(val key: String, val arrays: MeshArrays, val material: PartMaterial)

/**
 * Pure guards for what may reach Filament. SceneView 4.52's `Geometry.Builder` always uploads UINT indices (checked in its
 * bytecode: `IndexBuffer.Builder.bufferType(UINT)`), so the 65 535-vertex USHORT limit does not apply; but it calls
 * `vertices.first()` for the bounding box (an empty list throws NoSuchElementException during composition) and Filament
 * aborts the process natively on a zero-sized vertex or index buffer. Everything is therefore checked here first.
 */
object ViewerGeometry {
    /** True when [a] can be uploaded: vertices and indices present, indices in range (whole triangles), finite numbers, matching attribute sizes. */
    fun isDrawable(a: MeshArrays): Boolean {
        val n = a.vertexCount
        if (n == 0 || a.positions.size % 3 != 0 || a.indices.isEmpty()) return false
        if (a.primitive == Primitive.TRIANGLES && a.indices.size % 3 != 0) return false
        if (a.normals != null && a.normals.size != n * 3) return false
        if (a.uvs != null && a.uvs.size != n * 2) return false
        for (i in a.indices) if (i < 0 || i >= n) return false
        for (v in a.positions) if (!v.isFinite()) return false
        a.normals?.let { for (v in it) if (!v.isFinite()) return false }
        a.uvs?.let { for (v in it) if (!v.isFinite()) return false }
        return true
    }

    /** [a] when drawable, else null. */
    fun checked(a: MeshArrays): MeshArrays? = a.takeIf(::isDrawable)

    private fun finite(x: Float, y: Float, z: Float) = x.isFinite() && y.isFinite() && z.isFinite()

    /** POINTS: one vertex and one index per point of [idx] whose coordinates are finite; null when none are. */
    fun points(s: ScanSnapshot, idx: IntArray): MeshArrays? {
        val pos = FloatArray(idx.size * 3)
        var k = 0
        for (i in idx) {
            if (i < 0 || i >= s.pointCount) continue
            val x = s.points[i * 3]; val y = s.points[i * 3 + 1]; val z = s.points[i * 3 + 2]
            if (!finite(x, y, z)) continue
            pos[k * 3] = x; pos[k * 3 + 1] = y; pos[k * 3 + 2] = z
            k++
        }
        if (k == 0) return null
        return checked(MeshArrays(Primitive.POINTS, pos.copyOf(k * 3), null, null, IntArray(k) { it }))
    }

    /** One small tetrahedron (both windings) of half-size [h] meters per finite point: for renderers that draw POINTS at 1 px. */
    fun tetra(s: ScanSnapshot, idx: IntArray, h: Float): MeshArrays? {
        val pos = ArrayList<Float>(idx.size * 12)
        val ind = ArrayList<Int>(idx.size * 24)
        var k = 0
        for (i in idx) {
            if (i < 0 || i >= s.pointCount) continue
            val x = s.points[i * 3]; val y = s.points[i * 3 + 1]; val z = s.points[i * 3 + 2]
            if (!finite(x, y, z)) continue
            pos += x + h; pos += y + h; pos += z + h
            pos += x + h; pos += y - h; pos += z - h
            pos += x - h; pos += y + h; pos += z - h
            pos += x - h; pos += y - h; pos += z + h
            val b = k * 4
            for (t in TETRA) ind += b + t
            k++
        }
        if (k == 0) return null
        return checked(MeshArrays(Primitive.TRIANGLES, pos.toFloatArray(), null, null, ind.toIntArray()))
    }

    private val TETRA = intArrayOf(0, 1, 2, 0, 3, 1, 0, 2, 3, 1, 3, 2, 0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3)

    /**
     * Fan-triangulated plane polygon, both windings; null for fewer than 3 outline vertices or a non-finite outline or normal.
     * A non-unit normal is normalised; a zero normal drops the plane.
     */
    fun plane(p: SnapshotPlane): MeshArrays? {
        val n = p.vertexCount
        if (n < 3 || p.outline.size != n * 3) return null
        val len = sqrt(p.nx * p.nx + p.ny * p.ny + p.nz * p.nz)
        if (!len.isFinite() || len < 1e-6f) return null
        val nx = p.nx / len; val ny = p.ny / len; val nz = p.nz / len
        val normals = FloatArray(n * 3) { when (it % 3) { 0 -> nx; 1 -> ny; else -> nz } }
        val ind = IntArray((n - 2) * 6)
        var k = 0
        for (i in 1 until n - 1) { ind[k++] = 0; ind[k++] = i; ind[k++] = i + 1 }
        for (i in 1 until n - 1) { ind[k++] = 0; ind[k++] = i + 1; ind[k++] = i }
        return checked(MeshArrays(Primitive.TRIANGLES, p.outline.copyOf(), normals, FloatArray(n * 2), ind))
    }

    /**
     * Indexed triangle mesh. Normals that are missing, of the wrong size or not finite are recomputed from the faces;
     * [uv] of the wrong size is dropped (the mesh then draws without a texture). Null when the mesh has nothing to draw.
     */
    fun mesh(m: TriMesh, uv: FloatArray?): MeshArrays? {
        val n = m.vertexCount
        if (n == 0 || m.triangleCount == 0) return null
        val normals = m.normals.takeIf { usableNormals(it, n) } ?: faceNormals(m)
        val uvs = uv?.takeIf { it.size == n * 2 && it.all(Float::isFinite) } ?: FloatArray(n * 2)
        return checked(MeshArrays(Primitive.TRIANGLES, m.vertices, normals, uvs, m.indices.copyOf(m.triangleCount * 3)))
    }

    /** The triangles [triangles] of [m] with their vertices compacted (one colour bucket). */
    fun subMesh(m: TriMesh, triangles: IntArray): MeshArrays? {
        if (m.vertexCount == 0) return null
        val normals = m.normals.takeIf { usableNormals(it, m.vertexCount) } ?: faceNormals(m)
        val remap = HashMap<Int, Int>()
        val pos = ArrayList<Float>()
        val nrm = ArrayList<Float>()
        val ind = ArrayList<Int>(triangles.size * 3)
        for (t in triangles) {
            if (t < 0 || t >= m.triangleCount) continue
            for (c in 0..2) {
                val v = m.indices[t * 3 + c]
                if (v < 0 || v >= m.vertexCount) return null
                ind += remap.getOrPut(v) {
                    for (j in 0..2) { pos += m.vertices[v * 3 + j]; nrm += normals[v * 3 + j] }
                    remap.size
                }
            }
        }
        if (ind.isEmpty()) return null
        val nv = pos.size / 3
        return checked(MeshArrays(Primitive.TRIANGLES, pos.toFloatArray(), nrm.toFloatArray(), FloatArray(nv * 2), ind.toIntArray()))
    }

    /** One finite, non-zero normal per vertex (a zero normal makes SceneView's tangent frame NaN). */
    fun usableNormals(nrm: FloatArray, vertexCount: Int): Boolean {
        if (nrm.size != vertexCount * 3) return false
        for (i in 0 until vertexCount) {
            val x = nrm[i * 3]; val y = nrm[i * 3 + 1]; val z = nrm[i * 3 + 2]
            val l2 = x * x + y * y + z * z
            if (!l2.isFinite() || l2 < 1e-6f) return false
        }
        return true
    }

    /** Area-weighted vertex normals; a vertex with no usable face gets +Y. */
    fun faceNormals(m: TriMesh): FloatArray {
        val n = m.vertexCount
        val acc = FloatArray(n * 3)
        val v = m.vertices
        for (t in 0 until m.triangleCount) {
            val a = m.indices[t * 3]; val b = m.indices[t * 3 + 1]; val c = m.indices[t * 3 + 2]
            if (a !in 0 until n || b !in 0 until n || c !in 0 until n) continue
            val ux = v[b * 3] - v[a * 3]; val uy = v[b * 3 + 1] - v[a * 3 + 1]; val uz = v[b * 3 + 2] - v[a * 3 + 2]
            val wx = v[c * 3] - v[a * 3]; val wy = v[c * 3 + 1] - v[a * 3 + 1]; val wz = v[c * 3 + 2] - v[a * 3 + 2]
            val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
            if (!finite(cx, cy, cz)) continue
            for (i in intArrayOf(a, b, c)) { acc[i * 3] += cx; acc[i * 3 + 1] += cy; acc[i * 3 + 2] += cz }
        }
        for (i in 0 until n) {
            val x = acc[i * 3]; val y = acc[i * 3 + 1]; val z = acc[i * 3 + 2]
            val len = sqrt(x * x + y * y + z * z)
            if (len.isFinite() && len > 1e-12f) { acc[i * 3] = x / len; acc[i * 3 + 1] = y / len; acc[i * 3 + 2] = z / len }
            else { acc[i * 3] = 0f; acc[i * 3 + 1] = 1f; acc[i * 3 + 2] = 0f }
        }
        return acc
    }
}

/**
 * Pure grouping of the cloud into (colour, point indices) buckets, one rendered mesh per bucket, because
 * the SceneView colour materials take ONE uniform colour per material instance (no per-vertex colour material
 * ships with 4.52). Quality mode: [QUALITY_BUCKETS] buckets along the ramp. Kind mode: one bucket per plane kind plus grey.
 * The cloud is thinned evenly to at most [maxDisplay] points first.
 */
internal object ViewerGroups {
    const val QUALITY_BUCKETS = 10

    class Group(val rgb: Int, val indices: IntArray)

    fun build(s: ScanSnapshot, mode: ColourMode, maxDisplay: Int): List<Group> {
        val n = s.pointCount
        val take = minOf(n, maxDisplay)
        if (take <= 0) return emptyList()
        val src = IntArray(take) { if (take == n) it else (it.toLong() * n / take).toInt() }
        val buckets = HashMap<Int, MutableList<Int>>()
        if (mode == ColourMode.QUALITY) {
            for (i in src) {
                val q = s.quality[i].takeIf { it.isFinite() } ?: 0f
                val b = (q * QUALITY_BUCKETS).toInt().coerceIn(0, QUALITY_BUCKETS - 1)
                buckets.getOrPut(ColorRamp.rgb((b + 0.5f) / QUALITY_BUCKETS)) { ArrayList() }.add(i)
            }
        } else {
            val planeOf = s.planeIndexPerPoint()
            for (i in src) {
                val c = if (planeOf[i] < 0) KindColors.UNASSIGNED else KindColors.rgb(s.planes[planeOf[i]].kind)
                buckets.getOrPut(c) { ArrayList() }.add(i)
            }
        }
        return buckets.map { (c, l) -> Group(c, l.toIntArray()) }.sortedBy { it.rgb }
    }
}

/**
 * Everything the 3D viewer draws for one snapshot and one set of toggles, as plain arrays (no Filament): built off the main
 * thread, then uploaded part by part. An empty [parts] list means 'nothing to show yet'.
 */
class ViewerScene(val parts: List<ScenePart>, val note: String?) {
    val isEmpty: Boolean get() = parts.isEmpty()

    companion object {
        const val DOTS_MAX = 60_000
        const val BIG_DOTS_MAX = 25_000
        const val BIG_DOT_SIZE_M = 0.016f
        const val PLANE_ALPHA = 0.32f
        const val EMPTY_TEXT = "Nothing to show yet - scan more"

        fun build(
            s: ScanSnapshot,
            textured: TexturedMeshData?,
            showPoints: Boolean,
            showSurfaces: Boolean,
            mode: ColourMode,
            bigDots: Boolean,
            meshUv: FloatArray? = null,
        ): ViewerScene {
            val parts = ArrayList<ScenePart>()
            if (showPoints) {
                for (g in ViewerGroups.build(s, mode, if (bigDots) BIG_DOTS_MAX else DOTS_MAX)) {
                    val a = if (bigDots) ViewerGeometry.tetra(s, g.indices, BIG_DOT_SIZE_M) else ViewerGeometry.points(s, g.indices)
                    if (a != null) parts += ScenePart("pts-${g.rgb}-$bigDots-$mode", a, PartMaterial.Unlit(g.rgb))
                }
            }
            if (showSurfaces) {
                val photo = textured?.takeIf { (it.atlas != null && it.uvs != null) || it.vertexRgb != null }
                val atlasArrays = photo?.takeIf { it.atlas != null && it.uvs != null }?.let { p ->
                    p.uvs?.takeIf { it.size == p.mesh.vertexCount * 2 }?.let { ViewerGeometry.mesh(p.mesh, it) }
                }
                when {
                    atlasArrays != null -> parts += ScenePart("textured", atlasArrays, PartMaterial.Atlas)
                    photo?.vertexRgb != null && photo.vertexRgb.size == photo.mesh.vertexCount &&
                        ViewerGeometry.mesh(photo.mesh, null) != null -> {
                        for ((i, b) in com.example.arruler.texture.VertexColourBuckets.build(photo.mesh, photo.vertexRgb).withIndex()) {
                            ViewerGeometry.subMesh(photo.mesh, b.triangles)?.let { parts += ScenePart("vc-$i", it, PartMaterial.Unlit(b.rgb and 0xFFFFFF)) }
                        }
                    }
                    s.mesh != null -> ViewerGeometry.mesh(s.mesh, meshUv)?.let {
                        parts += ScenePart("mesh", it, PartMaterial.Lit(KindColors.rgb(PlaneKind.OTHER)))
                    }
                }
                for ((pi, p) in s.planes.withIndex()) {
                    ViewerGeometry.plane(p)?.let { parts += ScenePart("plane-$pi", it, PartMaterial.Unlit(KindColors.rgb(p.kind), PLANE_ALPHA)) }
                }
            }
            return ViewerScene(parts, missingNote(s))
        }

        /**
         * The banner of a room scan without a room: which pieces the planes lack (null for objects, a reconstructed room,
         * or when the planes have every piece and the walls just did not close).
         */
        fun missingNote(s: ScanSnapshot): String? {
            if (s.kind != ScanSnapshot.KIND_ROOM || s.room != null) return null
            val floor = s.planes.any { it.kind == PlaneKind.FLOOR }
            val ceiling = s.planes.any { it.kind == PlaneKind.CEILING }
            val walls = s.planes.count { it.kind == PlaneKind.WALL }
            return when {
                !floor && !ceiling -> "Floor and ceiling not captured - no room yet"
                !floor -> "Floor not captured - no room yet"
                !ceiling -> "Ceiling not captured - no room yet"
                walls < 3 -> "Fewer than 3 walls captured - no room yet"
                else -> "Walls do not close into a room yet"
            }
        }

        /** Camera framing: (target xyz, span) from the snapshot bounds; finite and at least 1 m even for odd input. */
        fun framing(s: ScanSnapshot): FloatArray {
            val b = s.bounds()?.takeIf { arr -> arr.all { it.isFinite() } && arr[3] >= arr[0] }
                ?: floatArrayOf(-1f, 0f, -1f, 1f, 2f, 1f)
            val span = maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2]).coerceAtLeast(1f)
            return floatArrayOf((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2, span)
        }
    }
}
