package com.example.arruler.texture

import com.example.arruler.objscan.TriMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TexturedMeshIoTest {
    private val mesh = TriMesh(
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
        intArrayOf(0, 1, 2, 1, 3, 2)
    )
    private val uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 0.5f, 1f, 0.5f)

    @Test fun objHasMatchingVertexTextureNormalAndFaceLines() {
        val lines = TexturedMeshText.obj(mesh, uvs, "m.mtl", "cube").lines()
        assertTrue(lines.contains("mtllib m.mtl"))
        assertEquals(4, lines.count { it.startsWith("v ") })
        assertEquals(4, lines.count { it.startsWith("vt ") })
        assertEquals(4, lines.count { it.startsWith("vn ") })
        assertEquals(listOf("f 1/1/1 2/2/2 3/3/3", "f 2/2/2 4/4/4 3/3/3"), lines.filter { it.startsWith("f ") })
    }

    @Test fun objFlipsVToTheBottomLeftOrigin() {
        val vt = TexturedMeshText.obj(mesh, uvs, "m.mtl").lines().filter { it.startsWith("vt ") }
        assertEquals("vt 0.0 1.0", vt[0])      // top-left uv (0,0) is OBJ (0,1)
        assertEquals("vt 0.0 0.5", vt[2])
    }

    @Test fun mtlReferencesTheTexture() {
        assertTrue(TexturedMeshText.mtl("a.png").contains("map_Kd a.png"))
    }

    @Test fun plyColouredHeaderAndPayloadSizes() {
        val bytes = TexturedMeshText.plyColoured(mesh, intArrayOf(0xFF102030.toInt(), 0, 0, 0xFFAABBCC.toInt()))
        val text = String(bytes, Charsets.ISO_8859_1)
        val end = text.indexOf("end_header\n") + "end_header\n".length
        assertTrue(text.contains("element vertex 4") && text.contains("property uchar red") && text.contains("element face 2"))
        assertEquals(end + 4 * 27 + 2 * 13, bytes.size)
        val bb = ByteBuffer.wrap(bytes, end, bytes.size - end).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(end + 24)
        assertEquals(0x10, bb.get().toInt() and 0xFF); assertEquals(0x20, bb.get().toInt() and 0xFF); assertEquals(0x30, bb.get().toInt() and 0xFF)
    }

    @Test fun colourBucketsCapAtMaxAndKeepEveryTriangleOnce() {
        val n = 2000
        val v = FloatArray((n + 2) * 3); val nrm = FloatArray(v.size)
        val idx = IntArray(n * 3) { if (it % 3 == 0) it / 3 else if (it % 3 == 1) it / 3 + 1 else it / 3 + 2 }
        val rgb = IntArray(n + 2) { (0xFF shl 24) or (((it * 7) % 256) shl 16) or (((it * 13) % 256) shl 8) or ((it * 29) % 256) }
        val buckets = VertexColourBuckets.build(TriMesh(v, nrm, idx), rgb, 64)
        assertTrue(buckets.size in 2..64)
        val all = buckets.flatMap { it.triangles.toList() }.sorted()
        assertEquals((0 until n).toList(), all)
    }

    @Test fun uniformColourIsOneBucket() {
        val rgb = IntArray(4) { 0xFF336699.toInt() }
        val b = VertexColourBuckets.build(mesh, rgb, 64)
        assertEquals(1, b.size)
        assertEquals(0xFF336699.toInt(), b[0].rgb)
    }
}
