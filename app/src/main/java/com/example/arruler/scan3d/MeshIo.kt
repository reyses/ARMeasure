package com.example.arruler.scan3d

import com.example.arruler.objscan.TriMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Readers for the triangle meshes that reach the app: our own binary PLY ([TriMesh.toBinaryPly]), the PC's
 * Open3D `mesh.ply` (binary little-endian, double coordinates, optional normals / colours, `vertex_indices`
 * lists) and `mesh.obj`. Pure JVM. Anything unreadable returns null (the caller then just shows no mesh).
 */
object MeshIo {

    private val WHITESPACE = Regex("\\s+")

    private class Prop(val name: String, val type: String, val listCount: String? = null) {
        val size: Int get() = typeSize(type)
    }

    private class Element(val name: String, val count: Int, val props: MutableList<Prop> = ArrayList())

    private fun typeSize(t: String): Int = when (t) {
        "char", "int8", "uchar", "uint8" -> 1
        "short", "int16", "ushort", "uint16" -> 2
        "int", "int32", "uint", "uint32", "float", "float32" -> 4
        "double", "float64" -> 8
        else -> -1
    }

    private fun ByteBuffer.read(t: String): Double = when (t) {
        "char", "int8" -> get().toDouble()
        "uchar", "uint8" -> (get().toInt() and 0xFF).toDouble()
        "short", "int16" -> short.toDouble()
        "ushort", "uint16" -> (short.toInt() and 0xFFFF).toDouble()
        "int", "int32" -> int.toDouble()
        "uint", "uint32" -> (int.toLong() and 0xFFFFFFFFL).toDouble()
        "float", "float32" -> float.toDouble()
        "double", "float64" -> double
        else -> throw IllegalArgumentException("type $t")
    }

    fun readPly(bytes: ByteArray): TriMesh? = try { parsePly(bytes) } catch (e: Exception) { null }

    private fun parsePly(bytes: ByteArray): TriMesh? {
        val marker = "end_header\n".toByteArray(Charsets.US_ASCII)
        var end = -1
        outer@ for (i in 0..bytes.size - marker.size) {
            for (j in marker.indices) if (bytes[i + j] != marker[j]) continue@outer
            end = i + marker.size
            break
        }
        if (end < 0) return null
        val header = String(bytes, 0, end, Charsets.US_ASCII).lines()
        if (header.firstOrNull()?.trim() != "ply") return null
        if (header.none { it.trim() == "format binary_little_endian 1.0" }) return null
        val elements = ArrayList<Element>()
        for (line in header) {
            val t = line.trim().split(WHITESPACE)
            when (t.firstOrNull()) {
                "element" -> elements += Element(t[1], t[2].toInt())
                "property" -> {
                    val el = elements.lastOrNull() ?: return null
                    if (t[1] == "list") el.props += Prop(t[4], t[3], listCount = t[2]) else el.props += Prop(t[2], t[1])
                }
            }
        }
        val vertexEl = elements.firstOrNull { it.name == "vertex" } ?: return null
        val buf = ByteBuffer.wrap(bytes, end, bytes.size - end).order(ByteOrder.LITTLE_ENDIAN)
        var verts: FloatArray? = null
        var normals: FloatArray? = null
        val tris = ArrayList<Int>()
        for (el in elements) {
            if (el.props.any { it.listCount == null && it.size < 0 }) return null
            if (el.name == "vertex") {
                val n = el.count
                val v = FloatArray(n * 3)
                val hasN = listOf("nx", "ny", "nz").all { name -> el.props.any { it.name == name } }
                val nr = if (hasN) FloatArray(n * 3) else null
                for (i in 0 until n) for (p in el.props) {
                    if (p.listCount != null) return null
                    val value = buf.read(p.type)
                    when (p.name) {
                        "x" -> v[i * 3] = value.toFloat()
                        "y" -> v[i * 3 + 1] = value.toFloat()
                        "z" -> v[i * 3 + 2] = value.toFloat()
                        "nx" -> nr?.set(i * 3, value.toFloat())
                        "ny" -> nr?.set(i * 3 + 1, value.toFloat())
                        "nz" -> nr?.set(i * 3 + 2, value.toFloat())
                    }
                }
                verts = v; normals = nr
            } else if (el.name == "face") {
                for (i in 0 until el.count) for (p in el.props) {
                    if (p.listCount == null) { buf.read(p.type); continue }
                    val cnt = buf.read(p.listCount).toInt()
                    val idx = IntArray(cnt) { buf.read(p.type).toInt() }
                    if (p.name == "vertex_indices" || p.name == "vertex_index") {
                        for (k in 1 until cnt - 1) { tris += idx[0]; tris += idx[k]; tris += idx[k + 1] }
                    }
                }
            } else {
                for (i in 0 until el.count) for (p in el.props) {
                    if (p.listCount != null) {
                        val cnt = buf.read(p.listCount).toInt()
                        buf.position(buf.position() + cnt * p.size)
                    } else buf.position(buf.position() + p.size)
                }
            }
        }
        return build(verts ?: return null, normals, tris.toIntArray())
    }

    /** Wavefront OBJ text: `v x y z` and `f a b c ...` (indices may carry `/vt/vn` parts or be negative). */
    fun readObj(text: String): TriMesh? = try {
        val v = ArrayList<Float>()
        val tris = ArrayList<Int>()
        for (line in text.lineSequence()) {
            val t = line.trim().split(WHITESPACE)
            when (t.firstOrNull()) {
                "v" -> { v += t[1].toFloat(); v += t[2].toFloat(); v += t[3].toFloat() }
                "f" -> {
                    val n = v.size / 3
                    val idx = t.drop(1).map { s ->
                        val i = s.substringBefore('/').toInt()
                        if (i < 0) n + i else i - 1
                    }
                    for (k in 1 until idx.size - 1) { tris += idx[0]; tris += idx[k]; tris += idx[k + 1] }
                }
            }
        }
        build(v.toFloatArray(), null, tris.toIntArray())
    } catch (e: Exception) {
        null
    }

    private fun build(vertices: FloatArray, normals: FloatArray?, indices: IntArray): TriMesh? {
        val n = vertices.size / 3
        if (n == 0 || indices.isEmpty() || indices.any { it < 0 || it >= n }) return null
        return if (normals != null) TriMesh(vertices, normals, indices) else TriMesh.withNormals(vertices, indices)
    }
}
