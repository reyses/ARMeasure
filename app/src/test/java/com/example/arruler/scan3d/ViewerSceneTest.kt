package com.example.arruler.scan3d

import com.example.arruler.depth.PlaneExtractor
import com.example.arruler.depth.PlaneKind
import com.example.arruler.depth.RoomFromPlanes
import com.example.arruler.depth.ScanAnalysis
import com.example.arruler.depth.ScanLogic
import com.example.arruler.depth.VoxelCloud
import com.example.arruler.objscan.TexturedMeshData
import com.example.arruler.objscan.TriMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sqrt

class ViewerSceneTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun snap(points: FloatArray, planes: List<SnapshotPlane>, room: SnapshotRoom? = null, mesh: TriMesh? = null, kind: String = ScanSnapshot.KIND_ROOM) =
        ScanSnapshot("id", null, 0L, points, FloatArray(points.size / 3) { (it % 11) / 10f }, planes, room, mesh, kind)

    private fun allBuilds(s: ScanSnapshot, textured: TexturedMeshData? = null): List<ViewerScene> =
        listOf(true, false).flatMap { pts ->
            listOf(true, false).flatMap { surf ->
                ColourMode.values().flatMap { mode -> listOf(false, true).map { big -> ViewerScene.build(s, textured, pts, surf, mode, big) } }
            }
        }

    private fun assertAllDrawable(scene: ViewerScene) {
        for (p in scene.parts) assertTrue("part ${p.key} not drawable", ViewerGeometry.isDrawable(p.arrays))
        assertEquals("duplicate part keys", scene.parts.size, scene.parts.map { it.key }.toSet().size)
    }

    // ---- the owner's real scan: 8 near-vertical planes, no floor, no ceiling, no room ----

    @Test fun realScanFixtureHasEightPlanesAndNoFloorOrCeiling() {
        val planes = RealScanFixture.planes()
        assertEquals(8, planes.size)
        assertEquals(113, planes.sumOf { it.vertexCount })
        assertEquals(5, planes.count { it.kind == PlaneKind.WALL })
        assertEquals(3, planes.count { it.kind == PlaneKind.OTHER })
        assertTrue(planes.none { it.kind == PlaneKind.FLOOR || it.kind == PlaneKind.CEILING })
        for (p in planes) assertEquals(1f, sqrt(p.nx * p.nx + p.ny * p.ny + p.nz * p.nz), 1e-4f)
    }

    @Test fun realScanPlanesOnlySnapshotBuildsEveryViewerPart() {
        val planes = RealScanFixture.planes()
        val s = snap(RealScanFixture.samplePoints(400), planes)
        assertEquals("Floor and ceiling not captured - no room yet", ViewerScene.missingNote(s))
        for (scene in allBuilds(s)) assertAllDrawable(scene)
        val full = ViewerScene.build(s, null, showPoints = true, showSurfaces = true, mode = ColourMode.KIND, bigDots = false)
        assertEquals(8, full.parts.count { it.key.startsWith("plane-") })
        assertTrue(full.parts.any { it.key.startsWith("pts-") })
        assertEquals("Floor and ceiling not captured - no room yet", full.note)
        val f = ViewerScene.framing(s)
        assertTrue(f.all { it.isFinite() })
        assertTrue(f[3] >= 1f)
    }

    @Test fun realScanWithNoPointsStillShowsThePlanes() {
        val s = snap(FloatArray(0), RealScanFixture.planes())
        val scene = ViewerScene.build(s, null, true, true, ColourMode.QUALITY, false)
        assertFalse(scene.isEmpty)
        assertEquals(8, scene.parts.size)
        assertAllDrawable(scene)
    }

    /** The phone path end to end: depth points on the owner's planes -> voxel cloud -> plane extraction -> no room -> snapshot -> viewer -> files. */
    @Test fun realScanThroughTheAnalyzeAndView3dPath() {
        val pts = RealScanFixture.samplePoints(3000)
        val cloud = VoxelCloud()
        cloud.addAll(pts)
        cloud.addAll(pts) // two hits per voxel, as ANALYZE_MIN_HITS asks
        val analysis = ScanLogic.analyze(cloud.points(ScanLogic.ANALYZE_MIN_HITS))
        assertTrue(analysis is ScanAnalysis.Incomplete)
        val missing = (analysis as ScanAnalysis.Incomplete).missing.joinToString()
        assertTrue(missing, missing.contains("floor") && missing.contains("ceiling"))

        val planes = PlaneExtractor().extract(cloud.points(ScanLogic.ANALYZE_MIN_HITS))
        assertTrue(planes.isNotEmpty())
        val room = RoomFromPlanes.build(planes)
        assertNull(room)
        val s = ScanSnapshot.from(cloud, planes, room, "real", "p1")
        assertNull(s.room)
        for (scene in allBuilds(s)) assertAllDrawable(scene)
        assertNotNull(ViewerScene.missingNote(s))

        val root = tmp.newFolder("scans")
        ScanFiles.save(root, s)
        val back = ScanFiles.load(root, "real")
        assertNotNull(back)
        assertNull(back!!.room)
        assertEquals(s.planes.size, back.planes.size)
        for (scene in allBuilds(back)) assertAllDrawable(scene)
        assertTrue(ScanFiles.objText(s).contains("g "))
    }

    // ---- empty and degenerate input ----

    @Test fun emptySnapshotIsNothingToShow() {
        val s = snap(FloatArray(0), emptyList())
        for (scene in allBuilds(s)) assertTrue(scene.isEmpty)
        assertEquals("Floor and ceiling not captured - no room yet", ViewerScene.missingNote(s))
        val f = ViewerScene.framing(s)
        assertTrue(f.all { it.isFinite() })
    }

    @Test fun missingNoteNamesWhatIsAbsent() {
        fun plane(kind: PlaneKind) = SnapshotPlane(kind, 0f, 1f, 0f, 0f, floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), 10)
        val walls = List(3) { plane(PlaneKind.WALL) }
        assertEquals("Floor not captured - no room yet", ViewerScene.missingNote(snap(FloatArray(0), walls + plane(PlaneKind.CEILING))))
        assertEquals("Ceiling not captured - no room yet", ViewerScene.missingNote(snap(FloatArray(0), walls + plane(PlaneKind.FLOOR))))
        assertEquals("Fewer than 3 walls captured - no room yet",
            ViewerScene.missingNote(snap(FloatArray(0), listOf(plane(PlaneKind.FLOOR), plane(PlaneKind.CEILING)))))
        assertEquals("Walls do not close into a room yet",
            ViewerScene.missingNote(snap(FloatArray(0), walls + plane(PlaneKind.FLOOR) + plane(PlaneKind.CEILING))))
        val room = SnapshotRoom(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f), 0f, 2.5f, 4)
        assertNull(ViewerScene.missingNote(snap(FloatArray(0), walls, room)))
        assertNull(ViewerScene.missingNote(snap(FloatArray(0), emptyList(), kind = ScanSnapshot.KIND_OBJECT)))
    }

    @Test fun nonFinitePointsAreDroppedAndAllBadIsEmpty() {
        val pts = floatArrayOf(0f, 0f, 0f, Float.NaN, 1f, 1f, 1f, Float.POSITIVE_INFINITY, 0f, 2f, 2f, 2f)
        val s = snap(pts, emptyList())
        val a = ViewerGeometry.points(s, intArrayOf(0, 1, 2, 3))!!
        assertEquals(2, a.vertexCount)
        assertTrue(ViewerGeometry.isDrawable(a))
        assertNull(ViewerGeometry.points(s, intArrayOf(1, 2)))
        assertNull(ViewerGeometry.tetra(s, intArrayOf(1, 2), 0.01f))
        assertNull(ViewerGeometry.points(s, intArrayOf()))
        val bad = snap(floatArrayOf(Float.NaN, 0f, 0f), emptyList())
        for (scene in allBuilds(bad)) assertTrue(scene.isEmpty)
    }

    @Test fun degeneratePlanesAreSkipped() {
        assertNull(ViewerGeometry.plane(SnapshotPlane(PlaneKind.WALL, 1f, 0f, 0f, 0f, floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f), 5)))
        assertNull(ViewerGeometry.plane(SnapshotPlane(PlaneKind.WALL, 0f, 0f, 0f, 0f, floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 5)))
        assertNull(ViewerGeometry.plane(SnapshotPlane(PlaneKind.WALL, 1f, 0f, 0f, 0f, floatArrayOf(0f, 0f, 0f, 0f, Float.NaN, 0f, 0f, 0f, 1f), 5)))
        val ok = ViewerGeometry.plane(SnapshotPlane(PlaneKind.WALL, 2f, 0f, 0f, 0f, floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f), 5))!!
        assertEquals(4, ok.vertexCount)
        assertEquals(12, ok.indices.size) // two triangles, both windings
        assertEquals(1f, ok.normals!![0], 1e-6f) // normalised
    }

    @Test fun isDrawableRejectsWhatFilamentCannotTake() {
        val pos = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        assertTrue(ViewerGeometry.isDrawable(MeshArrays(Primitive.TRIANGLES, pos, null, null, intArrayOf(0, 1, 2))))
        assertFalse(ViewerGeometry.isDrawable(MeshArrays(Primitive.TRIANGLES, FloatArray(0), null, null, intArrayOf())))
        assertFalse(ViewerGeometry.isDrawable(MeshArrays(Primitive.TRIANGLES, pos, null, null, intArrayOf())))
        assertFalse(ViewerGeometry.isDrawable(MeshArrays(Primitive.TRIANGLES, pos, null, null, intArrayOf(0, 1, 3))))
        assertFalse(ViewerGeometry.isDrawable(MeshArrays(Primitive.TRIANGLES, pos, null, null, intArrayOf(0, 1, -1))))
        assertFalse(ViewerGeometry.isDrawable(MeshArrays(Primitive.TRIANGLES, pos, null, null, intArrayOf(0, 1))))
        assertTrue(ViewerGeometry.isDrawable(MeshArrays(Primitive.POINTS, pos, null, null, intArrayOf(0, 1))))
        assertFalse(ViewerGeometry.isDrawable(MeshArrays(Primitive.TRIANGLES, pos, FloatArray(6), null, intArrayOf(0, 1, 2))))
        assertFalse(ViewerGeometry.isDrawable(MeshArrays(Primitive.TRIANGLES, pos, null, FloatArray(5), intArrayOf(0, 1, 2))))
        val nan = pos.copyOf().also { it[4] = Float.NaN }
        assertFalse(ViewerGeometry.isDrawable(MeshArrays(Primitive.TRIANGLES, nan, null, null, intArrayOf(0, 1, 2))))
    }

    // ---- meshes (OBJECT mode) ----

    @Test fun meshWithBrokenNormalsOrUvIsRepaired() {
        val v = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 5f, 5f, 5f) // vertex 3 unused
        val idx = intArrayOf(0, 1, 2)
        val zeroNormals = ViewerGeometry.mesh(TriMesh(v, FloatArray(12), idx), null)!!
        assertTrue(ViewerGeometry.usableNormals(zeroNormals.normals!!, 4))
        assertEquals(1f, zeroNormals.normals[2], 1e-6f) // +Z for a CCW triangle in the xy plane
        assertEquals(1f, zeroNormals.normals[10], 1e-6f) // the unused vertex gets +Y, never 0
        val shortNormals = ViewerGeometry.mesh(TriMesh(v, FloatArray(3), idx), FloatArray(3))!!
        assertEquals(12, shortNormals.normals!!.size)
        assertEquals(8, shortNormals.uvs!!.size) // a uv array of the wrong size is replaced
        assertNull(ViewerGeometry.mesh(TriMesh(FloatArray(0), FloatArray(0), IntArray(0)), null))
        assertNull(ViewerGeometry.mesh(TriMesh(v, FloatArray(12), IntArray(0)), null))
        assertNull(ViewerGeometry.mesh(TriMesh(v, FloatArray(12), intArrayOf(0, 1, 9)), null))
    }

    /** SceneView 4.52 uploads UINT indices, so a mesh past the 16-bit range must keep its large indices intact. */
    @Test fun meshPastUshortRangeKeepsItsIndices() {
        val n = 70_000
        val v = FloatArray(n * 3) { (it % 3).toFloat() + it / 3 * 1e-4f }
        val idx = intArrayOf(0, 1, n - 1, n - 3, n - 2, n - 1)
        val a = ViewerGeometry.mesh(TriMesh(v, FloatArray(0), idx), null)!!
        assertEquals(n, a.vertexCount)
        assertEquals(n - 1, a.indices.max())
        assertTrue(ViewerGeometry.isDrawable(a))
    }

    @Test fun texturedAndVertexColouredMeshesBuild() {
        val v = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f)
        val idx = intArrayOf(0, 1, 2, 1, 3, 2)
        val m = TriMesh(v, FloatArray(0), idx)
        val obj = snap(FloatArray(0), emptyList(), mesh = m, kind = ScanSnapshot.KIND_OBJECT)
        val grey = ViewerScene.build(obj, null, false, true, ColourMode.QUALITY, false)
        assertEquals(listOf("mesh"), grey.parts.map { it.key })
        val coloured = TexturedMeshData(m, vertexRgb = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF))
        val vc = ViewerScene.build(obj, coloured, false, true, ColourMode.QUALITY, false)
        assertTrue(vc.parts.isNotEmpty() && vc.parts.all { it.key.startsWith("vc-") })
        assertAllDrawable(vc)
        // vertex colours of the wrong length fall back to the grey mesh instead of indexing out of range
        val wrong = ViewerScene.build(obj, TexturedMeshData(m, vertexRgb = IntArray(2)), false, true, ColourMode.QUALITY, false)
        assertEquals(listOf("mesh"), wrong.parts.map { it.key })
        val emptyObj = snap(FloatArray(0), emptyList(), mesh = TriMesh(FloatArray(0), FloatArray(0), IntArray(0)), kind = ScanSnapshot.KIND_OBJECT)
        for (scene in allBuilds(emptyObj)) assertTrue(scene.isEmpty)
    }

    @Test fun subMeshCompactsVertices() {
        val v = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f)
        val m = TriMesh(v, FloatArray(0), intArrayOf(0, 1, 2, 1, 3, 2))
        val a = ViewerGeometry.subMesh(m, intArrayOf(1))!!
        assertEquals(3, a.vertexCount)
        assertEquals(listOf(0, 1, 2), a.indices.toList())
        assertNull(ViewerGeometry.subMesh(m, intArrayOf(7)))
    }
}
