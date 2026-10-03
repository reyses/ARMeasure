package com.example.arruler.texture

import com.example.arruler.objscan.TriMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TexturedObjParserTest {
    private fun tetra(): TriMesh = TriMesh.withNormals(
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        intArrayOf(0, 1, 2, 0, 3, 1, 0, 2, 3, 1, 3, 2),
    )

    @Test fun bakedObjRoundTripsPositionsAndUvs() {
        val m = tetra()
        val uvs = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f)
        val p = TexturedObjParser.parseObj(TexturedMeshText.obj(m, uvs, "mesh.mtl"))!!
        assertEquals(m.vertexCount, p.mesh.vertexCount)
        assertEquals(m.triangleCount, p.mesh.triangleCount)
        for (i in m.vertices.indices) assertEquals(m.vertices[i], p.mesh.vertices[i], 1e-6f)
        for (i in uvs.indices) assertEquals("uv $i", uvs[i], p.uvs[i], 1e-5f)   // v is flipped on write and again on read
        assertEquals(m.indices.toList(), p.mesh.indices.toList())
    }

    @Test fun separateUvIndicesSplitTheVerticesAlongSeams() {
        // the PC's OpenMVS style: no mtllib, no vn, a uv index of its own per corner
        val obj = "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\n" +
            "vt 0 0\nvt 0.5 0\nvt 0.5 0.5\nvt 0.6 0.6\nvt 1 0.6\nvt 1 1\n" +
            "f 1/1 2/2 3/3\nf 1/4 3/5 4/6\n"
        val p = TexturedObjParser.parseObj(obj)!!
        assertEquals(2, p.mesh.triangleCount)
        assertEquals(6, p.mesh.vertexCount)         // vertices 1 and 3 carry two uvs each
        assertEquals(12, p.uvs.size)
        assertEquals(1f - 0.5f, p.uvs[2 * 2 + 1], 1e-6f)   // third output vertex is (v3, vt3): v flipped
        assertTrue(p.mesh.normals.all { it.isFinite() })
    }

    @Test fun quadsAreFannedAndNegativeIndicesCount() {
        val obj = "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nf -4/-4 -3/-3 -2/-2 -1/-1\n"
        val p = TexturedObjParser.parseObj(obj)!!
        assertEquals(2, p.mesh.triangleCount)
        assertEquals(4, p.mesh.vertexCount)
    }

    @Test fun keepCropsTrianglesByCentroid() {
        val obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nv 9 0 0\nv 10 0 0\nv 9 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3\nf 4/1 5/2 6/3\n"
        val all = TexturedObjParser.parseObj(obj)!!
        assertEquals(2, all.mesh.triangleCount)
        val near = TexturedObjParser.parseObj(obj) { x, _, _ -> x < 5f }!!
        assertEquals(1, near.mesh.triangleCount)
        assertEquals(3, near.mesh.vertexCount)
        assertNull(TexturedObjParser.parseObj(obj) { _, _, _ -> false })
    }

    @Test fun anObjWithoutUvsIsNotATexturedMesh() {
        assertNull(TexturedObjParser.parseObj("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"))
        assertNull(TexturedObjParser.parseObj("nothing useful"))
    }

    @Test fun colouredPlyRoundTrips() {
        val m = tetra()
        val rgb = IntArray(4) { (0xFF shl 24) or ((it * 60) shl 16) or ((255 - it * 60) shl 8) or (it * 10) }
        val (mesh, got) = TexturedObjParser.readColouredPly(TexturedMeshText.plyColoured(m, rgb))!!
        assertEquals(m.vertexCount, mesh.vertexCount)
        assertEquals(m.indices.toList(), mesh.indices.toList())
        assertEquals(rgb.toList(), got.toList())
        assertNull(TexturedObjParser.readColouredPly(m.toBinaryPly()))     // no colours: not ours
        assertNotNull(TexturedObjParser.readColouredPly(TexturedMeshText.plyColoured(m, rgb)))
    }
}
