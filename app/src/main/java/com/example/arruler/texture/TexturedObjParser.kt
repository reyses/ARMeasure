package com.example.arruler.texture

import com.example.arruler.objscan.TriMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A UV-mapped triangle mesh with one position, one normal and one uv per vertex; [uvs] have the origin at the TOP-LEFT. */
class ParsedTextured(val mesh: TriMesh, val uvs: FloatArray)

/**
 * Reads textured meshes back from text and binary files. Pure JVM.
 *
 * [parseObj] accepts the app's own baked OBJ (`f a/a/a`) and the PC's OpenMVS OBJ (`mesh.obj` of a photogrammetry result:
 * `v`, `vt`, optional `vn`, faces `v/vt` or `v/vt/vn` with a separate uv index per corner, `mtllib` / `usemtl` already
 * stripped by the server). Every distinct (position, uv) pair becomes one vertex, so the result draws as an indexed
 * mesh. OBJ v runs up from the bottom, the returned uvs run down from the top (1 - v), the convention of the viewer and of [TextureBaker].
 */
object TexturedObjParser {
    private const val BITS = 21
    private const val MAX_INDEX = (1 shl BITS) - 1

    /**
     * [keep] decides per triangle (given its centroid) whether it stays; the PC's mesh contains the whole scene, the
     * caller crops it to the object's box. Null when the text has no usable textured triangles.
     */
    fun parseObj(text: String, keep: ((Float, Float, Float) -> Boolean)? = null): ParsedTextured? {
        var pos = FloatArray(3 * 1024); var nPos = 0
        var tex = FloatArray(2 * 1024); var nTex = 0
        val outV = FloatList(); val outUv = FloatList(); val tris = IntList()
        val remap = HashMap<Long, Int>()
        val corner = IntArray(64); val cornerTex = IntArray(64)
        for (line in text.lineSequence()) {
            if (line.length < 2) continue
            val c0 = line[0]
            if (c0 == 'v' && line[1] == ' ') {
                val t = splitWs(line)
                if (t.size < 4) continue
                if (nPos * 3 + 3 > pos.size) pos = pos.copyOf(pos.size * 2)
                pos[nPos * 3] = t[1].toFloat(); pos[nPos * 3 + 1] = t[2].toFloat(); pos[nPos * 3 + 2] = t[3].toFloat()
                nPos++
            } else if (c0 == 'v' && line[1] == 't') {
                val t = splitWs(line)
                if (t.size < 3) continue
                if (nTex * 2 + 2 > tex.size) tex = tex.copyOf(tex.size * 2)
                tex[nTex * 2] = t[1].toFloat(); tex[nTex * 2 + 1] = t[2].toFloat()
                nTex++
            } else if (c0 == 'f' && line[1] == ' ') {
                val t = splitWs(line)
                val n = minOf(t.size - 1, corner.size)
                if (n < 3) continue
                var ok = true
                for (k in 0 until n) {
                    val parts = t[k + 1].split('/')
                    val vi = parts[0].toIntOrNull() ?: run { ok = false; 0 }
                    val ti = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toIntOrNull() ?: run { ok = false; 0 } else 0
                    corner[k] = if (vi < 0) nPos + vi else vi - 1
                    cornerTex[k] = if (ti == 0) -1 else if (ti < 0) nTex + ti else ti - 1
                    if (corner[k] !in 0 until nPos || cornerTex[k] >= nTex) ok = false
                }
                if (!ok) continue
                for (k in 1 until n - 1) {
                    val ids = intArrayOf(0, k, k + 1)
                    if (keep != null) {
                        var cx = 0f; var cy = 0f; var cz = 0f
                        for (i in ids) { val p = corner[i] * 3; cx += pos[p]; cy += pos[p + 1]; cz += pos[p + 2] }
                        if (!keep(cx / 3f, cy / 3f, cz / 3f)) continue
                    }
                    for (i in ids) {
                        val p = corner[i]; val q = cornerTex[i]
                        if (p > MAX_INDEX || q > MAX_INDEX) return null
                        val key = (p.toLong() shl (BITS + 1)) or (q + 1).toLong()
                        val id = remap.getOrPut(key) {
                            outV.add(pos[p * 3]); outV.add(pos[p * 3 + 1]); outV.add(pos[p * 3 + 2])
                            if (q >= 0) { outUv.add(tex[q * 2]); outUv.add(1f - tex[q * 2 + 1]) } else { outUv.add(0f); outUv.add(0f) }
                            outV.size / 3 - 1
                        }
                        tris.add(id)
                    }
                }
            }
        }
        if (tris.size == 0 || nTex == 0) return null
        val mesh = TriMesh.withNormals(outV.toArray(), tris.toArray())
        return ParsedTextured(mesh, outUv.toArray())
    }

    /** Reads the binary PLY written by [TexturedMeshText.plyColoured]; null for anything else. */
    fun readColouredPly(bytes: ByteArray): Pair<TriMesh, IntArray>? = try {
        val marker = "end_header\n".toByteArray(Charsets.US_ASCII)
        var end = -1
        outer@ for (i in 0..bytes.size - marker.size) {
            for (j in marker.indices) if (bytes[i + j] != marker[j]) continue@outer
            end = i + marker.size
            break
        }
        if (end < 0) null else {
            val header = String(bytes, 0, end, Charsets.US_ASCII)
            val nv = Regex("element vertex (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
            val nf = Regex("element face (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
            if (nv == 0 || nf == 0 || !header.contains("property uchar red")) null else {
                val bb = ByteBuffer.wrap(bytes, end, bytes.size - end).order(ByteOrder.LITTLE_ENDIAN)
                val v = FloatArray(nv * 3); val n = FloatArray(nv * 3); val rgb = IntArray(nv)
                for (i in 0 until nv) {
                    for (k in 0..2) v[i * 3 + k] = bb.float
                    for (k in 0..2) n[i * 3 + k] = bb.float
                    val r = bb.get().toInt() and 0xFF; val g = bb.get().toInt() and 0xFF; val b = bb.get().toInt() and 0xFF
                    rgb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                val idx = IntArray(nf * 3)
                for (t in 0 until nf) {
                    bb.get()
                    for (k in 0..2) idx[t * 3 + k] = bb.int
                }
                if (idx.any { it < 0 || it >= nv }) null else TriMesh(v, n, idx) to rgb
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun splitWs(line: String): List<String> = line.trim().split(' ', '\t').filter { it.isNotEmpty() }

    private class FloatList {
        private var a = FloatArray(3 * 1024)
        var size = 0; private set
        fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray(): FloatArray = a.copyOf(size)
    }

    private class IntList {
        private var a = IntArray(3 * 1024)
        var size = 0; private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray(): IntArray = a.copyOf(size)
    }
}
