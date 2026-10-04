package com.example.arruler.tandem

import com.example.arruler.objscan.TriMesh
import com.example.arruler.texture.BakeStats
import com.example.arruler.texture.BakedMesh
import kotlin.math.sqrt

/**
 * Which phone should texture which triangle: the one whose keyframes see it best (largest cosine between the triangle normal and the
 * direction to a camera, closer cameras break ties). A phone then bakes only its own triangles with its own keyframes, so no JPEG
 * ever crosses the link. Camera centres are in the SAME frame as the mesh (the helper's already moved with [Rigid.applyToPose]).
 */
object TriangleOwnership {
    /** Returns 0 = leader, 1 = helper per triangle. A triangle neither phone saw goes to the leader. */
    fun assign(mesh: TriMesh, leaderCams: FloatArray, helperCams: FloatArray): IntArray {
        val out = IntArray(mesh.triangleCount)
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[t * 3] * 3; val b = mesh.indices[t * 3 + 1] * 3; val c = mesh.indices[t * 3 + 2] * 3
            val v = mesh.vertices
            val cx = (v[a] + v[b] + v[c]) / 3f; val cy = (v[a + 1] + v[b + 1] + v[c + 1]) / 3f; val cz = (v[a + 2] + v[b + 2] + v[c + 2]) / 3f
            val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
            val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-14f) continue
            nx /= l; ny /= l; nz /= l
            val sl = score(leaderCams, cx, cy, cz, nx, ny, nz)
            val sh = score(helperCams, cx, cy, cz, nx, ny, nz)
            out[t] = if (sh > sl) 1 else 0
        }
        return out
    }

    private fun score(cams: FloatArray, cx: Float, cy: Float, cz: Float, nx: Float, ny: Float, nz: Float): Float {
        var best = -1f
        for (i in 0 until cams.size / 3) {
            val dx = cams[i * 3] - cx; val dy = cams[i * 3 + 1] - cy; val dz = cams[i * 3 + 2] - cz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < 1e-6f) continue
            val cos = (dx * nx + dy * ny + dz * nz) / d
            val s = cos - 0.05f * d // distance penalty: 5 % cosine per metre
            if (s > best) best = s
        }
        return best
    }

    /** Fraction of triangles owned by the helper. */
    fun helperShare(owner: IntArray): Double = if (owner.isEmpty()) 0.0 else owner.count { it == 1 }.toDouble() / owner.size
}

/** Triangle subsets of a mesh. */
object MeshSplit {
    /** The mesh made of the triangles [ids] (in that order), vertices re-indexed. */
    fun subMesh(mesh: TriMesh, ids: IntArray): TriMesh {
        val map = IntArray(mesh.vertexCount) { -1 }
        var nv = 0
        val idx = IntArray(ids.size * 3)
        for ((k, t) in ids.withIndex()) for (e in 0..2) {
            val s = mesh.indices[t * 3 + e]
            if (map[s] < 0) map[s] = nv++
            idx[k * 3 + e] = map[s]
        }
        val v = FloatArray(nv * 3); val n = FloatArray(nv * 3)
        for (s in map.indices) if (map[s] >= 0) {
            val d = map[s] * 3
            v[d] = mesh.vertices[s * 3]; v[d + 1] = mesh.vertices[s * 3 + 1]; v[d + 2] = mesh.vertices[s * 3 + 2]
            n[d] = mesh.normals[s * 3]; n[d + 1] = mesh.normals[s * 3 + 1]; n[d + 2] = mesh.normals[s * 3 + 2]
        }
        return TriMesh(v, n, idx)
    }

    fun idsOf(owner: IntArray, who: Int): IntArray = owner.indices.filter { owner[it] == who }.toIntArray()
}

/** One textured mesh: [uvs] with the origin at the TOP-LEFT of the [width] x [height] ARGB [atlas] (Filament / glTF convention). */
class PackedTexturedMesh(val mesh: TriMesh, val uvs: FloatArray, val atlas: IntArray, val width: Int, val height: Int, val seenTriangles: Int, val unseenTriangles: Int)

/** Packs the atlases of the two halves into one image side by side and concatenates the meshes. */
object AtlasPacker {
    fun pack(a: BakedMesh, b: BakedMesh): PackedTexturedMesh {
        val w = a.atlasSize + b.atlasSize
        val h = maxOf(a.atlasSize, b.atlasSize)
        val atlas = IntArray(w * h)
        blit(a.atlas, a.atlasSize, atlas, w, 0)
        blit(b.atlas, b.atlasSize, atlas, w, a.atlasSize)

        val va = a.mesh.vertexCount
        val verts = a.mesh.vertices + b.mesh.vertices
        val nrm = a.mesh.normals + b.mesh.normals
        val idx = a.mesh.indices + IntArray(b.mesh.indices.size) { b.mesh.indices[it] + va }
        val uvs = FloatArray((va + b.mesh.vertexCount) * 2)
        for (i in 0 until va) {
            uvs[i * 2] = a.uvs[i * 2] * a.atlasSize / w
            uvs[i * 2 + 1] = a.uvs[i * 2 + 1] * a.atlasSize / h
        }
        for (i in 0 until b.mesh.vertexCount) {
            uvs[(va + i) * 2] = (b.uvs[i * 2] * b.atlasSize + a.atlasSize) / w
            uvs[(va + i) * 2 + 1] = b.uvs[i * 2 + 1] * b.atlasSize / h
        }
        return PackedTexturedMesh(
            TriMesh(verts, nrm, idx), uvs, atlas, w, h,
            a.stats.seenTriangles + b.stats.seenTriangles, a.stats.unseenTriangles + b.stats.unseenTriangles,
        )
    }

    private fun blit(src: IntArray, size: Int, dst: IntArray, dstW: Int, x0: Int) {
        for (y in 0 until size) System.arraycopy(src, y * size, dst, y * dstW + x0, size)
    }

    /** Test helper: a [BakeStats] for synthetic halves. */
    fun stats(seen: Int, unseen: Int, atlasSize: Int) = BakeStats(seen, unseen, 0, 0, 1f, atlasSize, 0)
}
