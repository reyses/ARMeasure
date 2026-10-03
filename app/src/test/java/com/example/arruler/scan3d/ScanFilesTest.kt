package com.example.arruler.scan3d

import com.example.arruler.depth.PlaneKind
import com.example.arruler.depth.VoxelCloud
import com.example.arruler.geometry.ColorRamp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun snapshot(): ScanSnapshot {
        val pts = floatArrayOf(0f, 0f, 0f, 1f, 2f, 3f, -1.5f, 0.25f, 4f)
        val q = floatArrayOf(0f, 0.5f, 1f)
        val floor = SnapshotPlane(PlaneKind.FLOOR, 0f, 1f, 0f, 0f, floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 2f, 0f, 2f, 0f, 0f, 2f), 500)
        val wall = SnapshotPlane(PlaneKind.WALL, 1f, 0f, 0f, 0f, floatArrayOf(0f, 0f, 0f, 0f, 0f, 2f, 0f, 2.5f, 2f), 400)
        val room = SnapshotRoom(floatArrayOf(0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f), 0f, 2.5f, 4)
        return ScanSnapshot("s1", "p1", 1234L, pts, q, listOf(floor, wall), room)
    }

    @Test fun plyHeaderAndLength() {
        val s = snapshot()
        val bytes = ScanFiles.plyBytes(s)
        val header = ScanFiles.plyHeader(3)
        assertTrue(header.startsWith("ply\nformat binary_little_endian 1.0\n"))
        assertTrue(header.contains("element vertex 3\n"))
        assertTrue(header.contains("property uchar blue\nproperty float quality\nend_header\n"))
        assertEquals(header.length + 3 * (3 * 4 + 3 + 4), bytes.size)
        assertEquals(header, String(bytes, 0, header.length, Charsets.US_ASCII))
    }

    @Test fun rampEnds() {
        assertEquals(0xFF0000, ColorRamp.rgb(0f))
        assertEquals(0x00FF00, ColorRamp.rgb(1f))
        assertEquals(0xFFFF00, ColorRamp.rgb(0.5f))
    }

    @Test fun roundTrip() {
        val s = snapshot()
        val root = tmp.newFolder("scans")
        ScanFiles.save(root, s)
        val l = ScanFiles.load(root, "s1") ?: error("scan s1 was not saved")
        assertEquals(s.id, l.id); assertEquals(s.projectId, l.projectId); assertEquals(s.createdAt, l.createdAt)
        assertArrayEquals(s.points, l.points, 0f)
        assertArrayEquals(s.quality, l.quality, 0f)
        assertEquals(s.planes.size, l.planes.size)
        for (i in s.planes.indices) {
            assertEquals(s.planes[i].kind, l.planes[i].kind)
            assertArrayEquals(s.planes[i].outline, l.planes[i].outline, 0f)
            assertEquals(s.planes[i].d, l.planes[i].d, 0f)
            assertEquals(s.planes[i].inlierCount, l.planes[i].inlierCount)
        }
        val sr = s.room ?: error("fixture has a room")
        val lr = l.room ?: error("loaded scan lost its room")
        assertArrayEquals(sr.outlineXZ, lr.outlineXZ, 0f)
        assertEquals(4f, lr.areaM2, 1e-5f)
        val info = ScanFiles.list(root, "p1").single()
        assertEquals(3, info.pointCount)
        assertEquals(4f, info.roomAreaM2!!, 1e-5f)
        assertTrue(ScanFiles.list(root, "other").isEmpty())
        assertTrue(ScanFiles.delete(root, "s1"))
        assertNull(ScanFiles.load(root, "s1"))
    }

    @Test fun objFaceCount() {
        val obj = ScanFiles.objText(snapshot())
        val lines = obj.lines()
        assertEquals(4 + 3, lines.count { it.startsWith("v ") })
        // quad -> 2 triangles, triangle -> 1
        assertEquals(3, lines.count { it.startsWith("f ") })
        assertTrue(lines.contains("g floor_1"))
        assertTrue(lines.contains("g wall_1"))
        assertTrue(lines.contains("f 5 6 7"))
    }

    @Test fun qualityFormula() {
        assertEquals(0f, Quality.of(1), 0f)
        assertEquals(1f, Quality.of(10), 0f)
        assertEquals(1f, Quality.of(50), 0f)
        assertEquals(0.5f, Quality.of(10, 0f), 1e-6f)
    }

    @Test fun fromCloud() {
        val c = VoxelCloud()
        repeat(10) { c.add(0.011f, 0.011f, 0.011f) }
        c.add(1.011f, 0.011f, 0.011f)
        val s = ScanSnapshot.from(c, emptyList(), null, "x")
        assertEquals(2, s.pointCount)
        val sorted = s.quality.sorted()
        assertEquals(0f, sorted[0], 0f)
        assertEquals(1f, sorted[1], 0f)
    }
}
