package com.example.arruler.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxelCloudTest {

    @Test fun duplicatePointsMergeIntoOneVoxelWithMeanAndHits() {
        val c = VoxelCloud(0.02f)
        c.add(0.011f, 0.011f, 0.011f)
        c.add(0.013f, 0.015f, 0.017f)
        c.add(0.015f, 0.013f, 0.012f)
        assertEquals(1, c.count)
        assertEquals(3, c.hitsAt(0.012f, 0.012f, 0.012f))
        val p = c.points(1)
        assertEquals(3, p.size)
        assertEquals((0.011f + 0.013f + 0.015f) / 3f, p[0], 1e-6f)
        assertEquals((0.011f + 0.015f + 0.013f) / 3f, p[1], 1e-6f)
        assertEquals((0.011f + 0.017f + 0.012f) / 3f, p[2], 1e-6f)
        assertEquals(0, c.points(4).size)
    }

    @Test fun voxelBoundariesSplitAndNegativeCoordsFloor() {
        val c = VoxelCloud(0.02f)
        c.add(0.0199f, 0f, 0f)
        c.add(0.0201f, 0f, 0f)
        assertEquals(2, c.count)
        c.add(-0.001f, 0f, 0f)   // belongs to voxel -1, not voxel 0
        assertEquals(3, c.count)
        c.add(-0.019f, 0f, 0f)   // same voxel as -0.001
        assertEquals(3, c.count)
        assertEquals(2, c.hitsAt(-0.01f, 0f, 0f))
    }

    @Test fun outOfRangePointsAreRejectedAndCounted() {
        val c = VoxelCloud(0.02f)
        assertFalse(c.add(11f, 0f, 0f))   // beyond 10.24 m
        assertTrue(c.add(10f, 0f, 0f))
        assertFalse(c.add(0f, -11f, 0f))
        assertEquals(2L, c.rejectedOutOfRange)
        assertEquals(1, c.count)
    }

    @Test fun capEvictsLowHitVoxelsAndKeepsCount() {
        val c = VoxelCloud(0.02f, maxVoxels = 100)
        // 50 well-observed voxels along x, then 50 single-hit ones
        for (i in 0 until 50) repeat(5) { c.add(i * 0.02f + 0.01f, 0f, 0f) }
        for (i in 0 until 50) c.add(i * 0.02f + 0.01f, 1f, 0f)
        assertEquals(100, c.count)
        // 200 more new voxels: count stays at the cap
        for (i in 0 until 200) c.add(i * 0.02f + 0.01f, -1f, 0f)
        assertEquals(100, c.count)
        assertTrue(c.evicted >= 200)
        // the 5-hit voxels were not chosen over single-hit candidates
        var survivors = 0
        for (i in 0 until 50) if (c.hitsAt(i * 0.02f + 0.01f, 0f, 0f) == 5) survivors++
        assertTrue("survivors=$survivors", survivors >= 40)
    }

    @Test fun boundsAndClear() {
        val c = VoxelCloud()
        assertNull(c.bounds())
        c.add(1f, 2f, 3f); c.add(-1f, 0.5f, 4f)
        val (lo, hi) = c.bounds()!!
        assertEquals(-1f, lo.x, 0.011f); assertEquals(0.5f, lo.y, 0.011f); assertEquals(3f, lo.z, 0.011f)
        assertEquals(1f, hi.x, 0.011f); assertEquals(2f, hi.y, 0.011f); assertEquals(4f, hi.z, 0.011f)
        c.clear()
        assertEquals(0, c.count); assertNull(c.bounds()); assertEquals(0, c.points().size)
        c.add(0f, 0f, 0f)
        assertEquals(1, c.count)
    }
}
