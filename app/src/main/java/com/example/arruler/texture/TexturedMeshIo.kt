package com.example.arruler.texture

import android.graphics.Bitmap
import com.example.arruler.objscan.TriMesh
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure text / binary generation for textured OBJ + MTL and vertex-coloured PLY. */
object TexturedMeshText {

    /**
     * OBJ with `mtllib`, `v`, `vt`, `vn` and `f a/a/a b/b/b c/c/c` (1-based; the baked mesh has one uv and one normal per
     * vertex). [uvs] has the origin at the top-left (see [BakedMesh]); OBJ wants the bottom-left, so v is written as 1 - v.
     */
    fun obj(mesh: TriMesh, uvs: FloatArray, mtlFile: String, name: String = "object"): String {
        require(uvs.size == mesh.vertexCount * 2) { "need two uv floats per vertex" }
        val sb = StringBuilder(mesh.vertexCount * 80 + mesh.triangleCount * 30)
        sb.append("# ARMeasure textured object scan, meters\nmtllib ").append(mtlFile).append("\no ").append(name).append("\nusemtl scan\n")
        for (i in 0 until mesh.vertexCount) sb.append("v ").append(mesh.vertices[i * 3]).append(' ').append(mesh.vertices[i * 3 + 1]).append(' ').append(mesh.vertices[i * 3 + 2]).append('\n')
        for (i in 0 until mesh.vertexCount) sb.append("vt ").append(uvs[i * 2]).append(' ').append(1f - uvs[i * 2 + 1]).append('\n')
        for (i in 0 until mesh.vertexCount) sb.append("vn ").append(mesh.normals[i * 3]).append(' ').append(mesh.normals[i * 3 + 1]).append(' ').append(mesh.normals[i * 3 + 2]).append('\n')
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[t * 3] + 1; val b = mesh.indices[t * 3 + 1] + 1; val c = mesh.indices[t * 3 + 2] + 1
            sb.append("f ").append(a).append('/').append(a).append('/').append(a).append(' ')
                .append(b).append('/').append(b).append('/').append(b).append(' ')
                .append(c).append('/').append(c).append('/').append(c).append('\n')
        }
        return sb.toString()
    }

    fun mtl(textureFile: String): String =
        "newmtl scan\nKa 1 1 1\nKd 1 1 1\nKs 0 0 0\nd 1\nillum 1\nmap_Kd $textureFile\n"

    /** Binary little-endian PLY: float x y z nx ny nz, uchar red green blue per vertex, then "uchar 3, int a b c" faces. */
    fun plyColoured(mesh: TriMesh, rgb: IntArray): ByteArray {
        require(rgb.size == mesh.vertexCount) { "need one colour per vertex" }
        val header = "ply\nformat binary_little_endian 1.0\ncomment ARMeasure object scan, meters\n" +
            "element vertex ${mesh.vertexCount}\nproperty float x\nproperty float y\nproperty float z\n" +
            "property float nx\nproperty float ny\nproperty float nz\n" +
            "property uchar red\nproperty uchar green\nproperty uchar blue\n" +
            "element face ${mesh.triangleCount}\nproperty list uchar int vertex_indices\nend_header\n"
        val h = header.toByteArray(Charsets.US_ASCII)
        val bb = ByteBuffer.allocate(h.size + mesh.vertexCount * 27 + mesh.triangleCount * 13).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(h)
        for (i in 0 until mesh.vertexCount) {
            for (k in 0..2) bb.putFloat(mesh.vertices[i * 3 + k])
            for (k in 0..2) bb.putFloat(mesh.normals[i * 3 + k])
            bb.put((rgb[i] shr 16).toByte()).put((rgb[i] shr 8).toByte()).put(rgb[i].toByte())
        }
        for (t in 0 until mesh.triangleCount) {
            bb.put(3.toByte()).putInt(mesh.indices[t * 3]).putInt(mesh.indices[t * 3 + 1]).putInt(mesh.indices[t * 3 + 2])
        }
        return bb.array()
    }
}

/** Thin Android wrapper: writes `<base>.obj`, `<base>.mtl`, `<base>.png` (atlas) or `<base>_colour.ply`. */
object TexturedMeshWriter {
    fun writeTextured(dir: File, base: String, baked: BakedMesh): List<File> {
        dir.mkdirs()
        val obj = File(dir, "$base.obj"); val mtl = File(dir, "$base.mtl"); val png = File(dir, "$base.png")
        obj.writeText(TexturedMeshText.obj(baked.mesh, baked.uvs, mtl.name, base), Charsets.UTF_8)
        mtl.writeText(TexturedMeshText.mtl(png.name), Charsets.UTF_8)
        val bmp = atlasBitmap(baked)
        try { FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bmp.recycle() }
        return listOf(obj, mtl, png)
    }

    fun writeColoured(dir: File, base: String, mesh: TriMesh, rgb: IntArray): File {
        dir.mkdirs()
        return File(dir, "${base}_colour.ply").also { it.writeBytes(TexturedMeshText.plyColoured(mesh, rgb)) }
    }

    /** The atlas as an ARGB_8888 bitmap (for the viewer; the caller recycles it). */
    fun atlasBitmap(baked: BakedMesh): Bitmap =
        Bitmap.createBitmap(baked.atlas, baked.atlasSize, baked.atlasSize, Bitmap.Config.ARGB_8888)
}
