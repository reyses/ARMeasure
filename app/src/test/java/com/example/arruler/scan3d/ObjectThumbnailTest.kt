package com.example.arruler.scan3d

import com.example.arruler.objscan.TriMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObjectThumbnailTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A closed box [w] x [h] x [d] on y = 0. */
    private fun box(w: Float, h: Float, d: Float): TriMesh {
        val x = w / 2; val z = d / 2
        val v = floatArrayOf(
            -x, 0f, -z, x, 0f, -z, x, 0f, z, -x, 0f, z,
            -x, h, -z, x, h, -z, x, h, z, -x, h, z,
        )
        val i = intArrayOf(
            0, 2, 1, 0, 3, 2,       // bottom
            4, 5, 6, 4, 6, 7,       // top
            0, 1, 5, 0, 5, 4,       // back
            2, 3, 7, 2, 7, 6,       // front
            3, 0, 4, 3, 4, 7,       // left
            1, 2, 6, 1, 6, 5,       // right
        )
        return TriMesh.withNormals(v, i)
    }

    @Test fun emptyMeshGivesNothing() {
        assertTrue(MeshProjection.project(TriMesh(FloatArray(0), FloatArray(0), IntArray(0)), 512).isEmpty())
    }

    @Test fun projectionFitsAndCentresInsideTheMargin() {
        val size = 512
        val tris = MeshProjection.project(box(0.4f, 0.1f, 0.2f), size, margin = 0.08f)
        assertEquals(12, tris.size)
        val xs = tris.flatMap { listOf(it.x0, it.x1, it.x2) }
        val ys = tris.flatMap { listOf(it.y0, it.y1, it.y2) }
        val lo = size * 0.08f
        val hi = size * 0.92f
        assertTrue(xs.all { it >= lo - 0.5f && it <= hi + 0.5f })
        assertTrue(ys.all { it >= lo - 0.5f && it <= hi + 0.5f })
        // the larger of the two extents touches both margins, and the other is centred
        val wx = xs.max() - xs.min(); val wy = ys.max() - ys.min()
        assertEquals(size * 0.84f, maxOf(wx, wy), 1f)
        assertEquals(size / 2f, (xs.max() + xs.min()) / 2f, 1f)
        assertEquals(size / 2f, (ys.max() + ys.min()) / 2f, 1f)
    }

    @Test fun smallAndLargeObjectsFillTheSameFrame() {
        val a = MeshProjection.extent(MeshProjection.project(box(0.05f, 0.03f, 0.04f), 512), 512)
        val b = MeshProjection.extent(MeshProjection.project(box(2f, 1.2f, 1.6f), 512), 512)
        assertEquals(a, b, 1.5f)
    }

    @Test fun trianglesComeFarToNearAndShadesStayInRange() {
        val tris = MeshProjection.project(box(0.4f, 0.3f, 0.2f), 512)
        for (i in 1 until tris.size) assertTrue(tris[i].depth >= tris[i - 1].depth)
        assertTrue(tris.all { it.shade in 0.3f..1.0001f })
        assertTrue(tris.map { it.shade }.distinct().size > 1)   // faces are lit differently
    }

    @Test fun windingDoesNotChangeTheShade() {
        val m = box(0.4f, 0.3f, 0.2f)
        val flipped = TriMesh(m.vertices, m.normals, IntArray(m.indices.size) { i ->
            val t = i / 3; val k = i % 3
            m.indices[t * 3 + (if (k == 1) 2 else if (k == 2) 1 else 0)]
        })
        val a = MeshProjection.project(m, 256).map { it.shade }.sorted()
        val b = MeshProjection.project(flipped, 256).map { it.shade }.sorted()
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-4f)
    }

    @Test fun bigMeshesAreThinnedToTheTriangleBudget() {
        val n = 5000
        val v = FloatArray(n * 9)
        val idx = IntArray(n * 3) { it }
        val rng = java.util.Random(3)
        for (i in v.indices) v[i] = rng.nextFloat()
        val m = TriMesh(v, FloatArray(v.size), idx)
        assertTrue(MeshProjection.project(m, 128, maxTris = 1000).size <= 1000)
    }

    @Test fun metaFieldsRoundTripAndCanBeEditedInPlace() {
        val root = tmp.newFolder("scans")
        val snap = ScanSnapshot(
            "o1", "p1", 99L, FloatArray(0), FloatArray(0), emptyList(), null, box(0.3f, 0.2f, 0.1f), ScanSnapshot.KIND_OBJECT,
            ObjectSummary(0.3f, 0.1f, 0.2f, 0.005f, 0.007f, 0.006f), name = "Object 1", method = "phone, 2.0 s", extras = listOf("Shape: box"),
        )
        ScanFiles.save(root, snap)
        val loaded = ScanFiles.load(root, "o1")!!
        assertEquals("Object 1", loaded.name)
        assertEquals("phone, 2.0 s", loaded.method)
        assertEquals(listOf("Shape: box"), loaded.extras)
        val info = ScanFiles.list(root, "p1").single()
        assertEquals("Object 1", info.name)
        assertEquals(0.006f, info.summary!!.volumeM3, 0f)

        assertTrue(ScanFiles.updateMeta(root, "o1", name = "Footstool"))
        assertTrue(ScanFiles.updateMeta(root, "o1", notes = "worn"))
        val edited = ScanFiles.load(root, "o1")!!
        assertEquals("Footstool", edited.name)
        assertEquals("worn", edited.notes)
        assertEquals("phone, 2.0 s", edited.method)
        assertTrue(!ScanFiles.updateMeta(root, "missing", name = "x"))
    }

    @Test fun videoAndTexturedFilesLiveInTheScanDirectory() {
        val root = tmp.newFolder("scans2")
        val snap = ScanSnapshot("o2", null, 1L, FloatArray(0), FloatArray(0), emptyList(), null, box(0.1f, 0.1f, 0.1f), ScanSnapshot.KIND_OBJECT)
        ScanFiles.save(root, snap)
        val mp4 = tmp.newFile("c.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(ScanFiles.saveVideo(root, "o2", mp4))
        assertEquals(3, ScanFiles.videoFile(root, "o2").length())
        assertTrue(!ScanFiles.saveVideo(root, "o2", java.io.File(tmp.root, "nope.mp4")))
        assertTrue(ScanFiles.loadTextured(root, "o2") == null)
        ScanFiles.saveTextured(root, "o2", com.example.arruler.objscan.TexturedObject("o", "m", byteArrayOf(5)))
        val t = ScanFiles.loadTextured(root, "o2")!!
        assertEquals("o", t.obj); assertEquals("m", t.mtl); assertEquals(listOf<Byte>(5), t.png.toList())
        ScanFiles.delete(root, "o2")
        assertTrue(!ScanFiles.videoFile(root, "o2").exists())
    }
}
