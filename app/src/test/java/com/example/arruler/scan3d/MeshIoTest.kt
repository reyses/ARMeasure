package com.example.arruler.scan3d

import com.example.arruler.depth.PlaneKind
import com.example.arruler.objscan.TriMesh
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshIoTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A closed tetrahedron, outward counter-clockwise. */
    private fun tetra(): TriMesh {
        val v = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val i = intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3)
        return TriMesh.withNormals(v, i)
    }

    @Test fun ownPlyRoundTrips() {
        val m = tetra()
        val back = MeshIo.readPly(m.toBinaryPly())!!
        assertArrayEquals(m.vertices, back.vertices, 0f)
        assertArrayEquals(m.indices, back.indices)
        assertEquals(m.volume(), back.volume(), 1e-9)
    }

    @Test fun objRoundTripsWithSlashedIndices() {
        val m = tetra()
        val back = MeshIo.readObj(m.toObj())!!
        assertEquals(4, back.vertexCount); assertEquals(4, back.triangleCount)
        assertEquals(m.volume(), back.volume(), 1e-5)
        assertNull(MeshIo.readObj("garbage"))
        assertNull(MeshIo.readObj("v 0 0 0\nf 1 2 9\n"))
    }

    /** Open3D writes double x/y/z, double normals, uchar colours and `property list uchar int vertex_indices`. */
    @Test fun readsAnOpen3dStylePly() {
        val header = "ply\nformat binary_little_endian 1.0\ncomment Created by Open3D\nelement vertex 4\n" +
            "property double x\nproperty double y\nproperty double z\nproperty double nx\nproperty double ny\nproperty double nz\n" +
            "property uchar red\nproperty uchar green\nproperty uchar blue\nelement face 4\n" +
            "property list uchar int vertex_indices\nend_header\n"
        val bos = ByteArrayOutputStream()
        bos.write(header.toByteArray(Charsets.US_ASCII))
        val bb = ByteBuffer.allocate(4 * (6 * 8 + 3) + 4 * (1 + 12)).order(ByteOrder.LITTLE_ENDIAN)
        val v = arrayOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        for (p in v) { for (c in p) bb.putDouble(c); bb.putDouble(0.0); bb.putDouble(1.0); bb.putDouble(0.0); bb.put(1); bb.put(2); bb.put(3) }
        for (f in arrayOf(intArrayOf(0, 2, 1), intArrayOf(0, 1, 3), intArrayOf(0, 3, 2), intArrayOf(1, 2, 3))) {
            bb.put(3); for (i in f) bb.putInt(i)
        }
        bos.write(bb.array())
        val m = MeshIo.readPly(bos.toByteArray())!!
        assertEquals(4, m.vertexCount); assertEquals(4, m.triangleCount)
        assertEquals(1f, m.vertices[3], 0f)
        assertEquals(1f, m.normals[1], 0f)
        assertEquals(1.0 / 6.0, m.volume(), 1e-6)
    }

    @Test fun rejectsAsciiAndTruncatedPly() {
        assertNull(MeshIo.readPly("ply\nformat ascii 1.0\nelement vertex 0\nend_header\n".toByteArray()))
        val ok = tetra().toBinaryPly()
        assertNull(MeshIo.readPly(ok.copyOf(ok.size - 20)))
        assertNull(MeshIo.readPly(ByteArray(10)))
    }

    @Test fun objectScanSavesAndLoadsWithMeshAndKind() {
        val m = tetra()
        val s = ScanSnapshot(
            "o1", "p1", 99L, FloatArray(0), FloatArray(0), emptyList(), null,
            m, ScanSnapshot.KIND_OBJECT, ObjectSummary(0.2f, 0.1f, 0.05f, 0.0078f, 0.0083f, 0.008f),
        )
        val root = tmp.newFolder("scans")
        ScanFiles.save(root, s)
        val l = ScanFiles.load(root, "o1")!!
        assertEquals(ScanSnapshot.KIND_OBJECT, l.kind)
        assertNotNull(l.mesh)
        assertEquals(4, l.mesh!!.triangleCount)
        assertEquals(0.008f, l.objectSummary!!.volumeM3, 0f)
        val info = ScanFiles.list(root, "p1").single()
        assertEquals(ScanSnapshot.KIND_OBJECT, info.kind)
        assertEquals(0.008f, info.objectVolumeM3!!, 0f)
        // OBJ export of an object scan is its mesh, not planes
        assertTrue(ScanFiles.objText(l).contains("f "))
        assertEquals(m.vertices.size / 3, ScanFiles.objText(l).lines().count { it.startsWith("v ") })
        // bounds come from the mesh when there are no points
        assertNotNull(l.bounds())
    }

    @Test fun roomScansStayRooms() {
        val s = ScanSnapshot(
            "r1", null, 1L, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f), emptyList(), null,
        )
        val root = tmp.newFolder("scans2")
        ScanFiles.save(root, s)
        val l = ScanFiles.load(root, "r1")!!
        assertEquals(ScanSnapshot.KIND_ROOM, l.kind)
        assertNull(l.mesh)
        assertNull(l.objectSummary)
        assertEquals(PlaneKind.FLOOR, PlaneKind.FLOOR)
    }
}
