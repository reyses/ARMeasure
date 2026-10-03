package com.example.arruler.objscan

import com.example.arruler.geometry.Vec3
import com.example.arruler.processing.CloudData
import com.example.arruler.processing.DefaultPhoneRunner
import com.example.arruler.processing.JobEstimate
import com.example.arruler.processing.JobType
import com.example.arruler.processing.ProcessingJob
import com.example.arruler.processing.ObjectQuality as ProcQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random
import kotlin.math.PI

class ObjectPlacementTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tap = Vec3(1f, 0.8f, -0.5f)

    @Test fun defaultBoxIs25CmOnThePlane() {
        val b = ObjectPlacement.defaultBox(tap)
        assertEquals(0.25f, b.w, 0f); assertEquals(0.25f, b.d, 0f); assertEquals(0.25f, b.h, 0f)
        assertEquals(tap, b.centre)
        assertEquals(0.8f, ObjectPlacement.supportPlane(tap).d, 0f)
    }

    @Test fun resizeStepsAndClamps() {
        var b = ObjectPlacement.defaultBox(tap)
        b = ObjectPlacement.resize(b, BoxDim.WIDTH, ObjectPlacement.STEP)
        assertEquals(0.26f, b.w, 1e-6f)
        b = ObjectPlacement.resize(b, BoxDim.HEIGHT, -ObjectPlacement.LONG_STEP)
        assertEquals(0.20f, b.h, 1e-6f)
        assertEquals(0.25f, b.d, 0f)
        for (i in 0 until 100) b = ObjectPlacement.resize(b, BoxDim.DEPTH, -ObjectPlacement.LONG_STEP)
        assertEquals(ObjectPlacement.MIN_SIDE, b.d, 0f)
        for (i in 0 until 100) b = ObjectPlacement.resize(b, BoxDim.DEPTH, ObjectPlacement.LONG_STEP)
        assertEquals(ObjectPlacement.MAX_SIDE, b.d, 0f)
    }

    @Test fun rotateStepsFifteenDegreesAndWraps() {
        var b = ObjectPlacement.defaultBox(tap)
        b = ObjectPlacement.rotate(b)
        assertEquals(15.0 * PI / 180.0, b.yaw.toDouble(), 1e-5)
        repeat(23) { b = ObjectPlacement.rotate(b) }
        assertEquals(0.0, Math.sin(b.yaw.toDouble()), 1e-4); assertEquals(1.0, Math.cos(b.yaw.toDouble()), 1e-6)
    }

    @Test fun twelveDistinctEdgesOfTheRightLengths() {
        val b = ObjectBox(tap, 0.4f, 0.3f, 0.2f, 0.1f)
        val e = ObjectPlacement.edges(b)
        assertEquals(12, e.size)
        assertEquals(12, e.map { setOf(it.first, it.second) }.toSet().size)
        val lens = e.map { it.first.distanceTo(it.second) }
        assertEquals(4, lens.count { kotlin.math.abs(it - 0.3f) < 1e-4f })
        assertEquals(4, lens.count { kotlin.math.abs(it - 0.2f) < 1e-4f })
        assertEquals(4, lens.count { kotlin.math.abs(it - 0.1f) < 1e-4f })
    }

    private fun cloudOf(scene: Scene, box: ObjectBox, voxel: Float): FloatArray {
        val c = ObjectVoxelCloud(box, voxel)
        c.addAll(scene.points)
        return c.points()
    }

    @Test fun fitFindsTheObjectAndItsYaw() {
        val voxel = ObjectQuality.QUICK.voxelSize
        val scene = Fixtures.boxScene(Random(21), 0.30f, 0.15f, 0.20f, 30f, 0.002f, voxel)
        val search = ObjectPlacement.searchBox(Fixtures.origin)
        val fit = assertNotNull(ObjectPlacement.fit(cloudOf(scene, search, voxel), search, Fixtures.plane)).let {
            ObjectPlacement.fit(cloudOf(scene, search, voxel), search, Fixtures.plane)!!
        }
        val dims = listOf(fit.w, fit.d).sortedDescending()
        assertEquals(0.32f, dims[0], 0.02f)       // 30 cm + 2 x 1 cm slack
        assertEquals(0.17f, dims[1], 0.02f)       // 15 cm + slack
        assertEquals(0.21f, fit.h, 0.02f)         // 20 cm + slack
        // the box sits on the plane and is centred on the object
        assertEquals(0.8f, fit.centre.y, 1e-4f)
        assertEquals(Fixtures.origin.x, fit.centre.x, 0.02f)
        assertEquals(Fixtures.origin.z, fit.centre.z, 0.02f)
        // the long side is along the object's 30 deg axis: width axis vs world direction of the yawed object
        val wx = Math.cos(fit.yaw.toDouble()); val wz = -Math.sin(fit.yaw.toDouble())
        val objX = Math.cos(30.0 * PI / 180.0); val objZ = -Math.sin(30.0 * PI / 180.0)
        val alongLong = if (fit.w >= fit.d) 1.0 else 0.0
        val dot = Math.abs(wx * objX + wz * objZ)
        if (alongLong == 1.0) assertTrue("width axis follows the long side ($dot)", dot > 0.98)
        else assertTrue("depth axis follows the long side ($dot)", dot < 0.2)
    }

    @Test fun fitNeedsPointsNearTheTap() {
        assertNull(ObjectPlacement.fit(FloatArray(0), ObjectPlacement.searchBox(tap), SupportPlane.horizontal(0.8f)))
        // a table plane alone (no object) leaves nothing above the support margin
        val rng = Random(5)
        val flat = FloatArray(3 * 4000)
        for (i in 0 until 4000) {
            flat[i * 3] = tap.x + (rng.nextFloat() - 0.5f) * 0.6f
            flat[i * 3 + 1] = 0.8f + (rng.nextFloat() - 0.5f) * 0.002f
            flat[i * 3 + 2] = tap.z + (rng.nextFloat() - 0.5f) * 0.6f
        }
        assertNull(ObjectPlacement.fit(flat, ObjectPlacement.searchBox(tap), SupportPlane.horizontal(0.8f)))
    }

    private fun bins(vararg dirObserved: Pair<Vec3, Boolean>) = dirObserved.map { DomeBin(it.first.normalized(), it.second, if (it.second) 5 else 0) }

    @Test fun hintsFollowThePriorityOrder() {
        val win = 0.25f to 0.8f
        val high = Vec3(0f, 1f, 0.3f)
        val low = Vec3(1f, 0.1f, 0f)
        assertEquals("Back off a little", ObjectPlacement.hint(bins(high to true), 0f, 0.1f, win))
        assertEquals("Move closer to the object", ObjectPlacement.hint(bins(high to true), 0f, 1.5f, win))
        assertEquals("Walk slowly around the object", ObjectPlacement.hint(bins(high to false, low to false), 0f, 0.5f, win))
        assertEquals("Lower the phone and circle again", ObjectPlacement.hint(bins(high to true, low to false), 0.3f, 0.5f, win))
        assertEquals("Keep circling the object", ObjectPlacement.hint(bins(high to true, low to true), 0.3f, 0.5f, win))
        assertEquals("Good coverage - tap Finish", ObjectPlacement.hint(bins(high to true, low to true), 0.7f, 0.5f, win))
        assertEquals("Keep circling the object", ObjectPlacement.hint(bins(high to true, low to true), 0.3f, null, win))
    }

    @Test fun phoneRunnerProducesTheObjectResultAndMesh() = runBlocking {
        val q = ObjectQuality.QUICK
        val scene = Fixtures.boxScene(Random(31), 0.20f, 0.20f, 0.20f, 0f, 0.003f, q.voxelSize)
        val box = Fixtures.userBox(0.20f, 0.20f, 0.20f, 0f, 0.01f)
        val cloud = ObjectVoxelCloud(box, q.voxelSize)
        cloud.addAll(scene.points)
        val pts = cloud.points()
        val n = pts.size / 3
        val job = ProcessingJob(
            JobType.OBJECT_MESH, ProcQuality.QUICK, JobEstimate(n),
            CloudData(pts, cloud.hitsFor(pts), IntArray(n) { 255 }), workDir = tmp.newFolder(),
            objectBox = box, supportPlane = Fixtures.plane,
        )
        val r = DefaultPhoneRunner().run(job)
        assertEquals("object_mesh", r.jobType)
        assertEquals("phone", r.stats.backend)
        val d = r.measures.objectDims!!
        assertEquals(0.20, d.lengthM, 0.015)
        assertEquals(0.20, d.widthM, 0.015)
        assertEquals(0.20, d.heightM, 0.015)
        val v = r.measures.volumeM3!!
        assertTrue(v.low <= v.recommended && v.recommended <= v.high)
        assertEquals(0.008, v.recommended, 0.0012)
        assertTrue(r.measures.volumeVariantsM3.containsKey("convex_hull"))
        assertTrue(r.measures.volumeVariantsM3.containsKey("occupancy"))
        assertNotNull(job.mesh)
        assertTrue(job.mesh!!.triangleCount > 100)
    }

    @Test fun phoneRunnerRefusesAJobWithoutABoxAndTinyClouds() {
        val job = ProcessingJob(
            JobType.OBJECT_MESH, ProcQuality.QUICK, JobEstimate(10),
            CloudData.fromXyz(FloatArray(30)), workDir = tmp.newFolder(),
        )
        try { runBlocking { DefaultPhoneRunner().run(job) }; org.junit.Assert.fail() } catch (e: IllegalArgumentException) { }
        val tiny = ProcessingJob(
            JobType.OBJECT_MESH, ProcQuality.QUICK, JobEstimate(10),
            CloudData.fromXyz(FloatArray(30)), workDir = tmp.newFolder(),
            objectBox = ObjectPlacement.defaultBox(tap), supportPlane = SupportPlane.horizontal(0.8f),
        )
        try { runBlocking { DefaultPhoneRunner().run(tiny) }; org.junit.Assert.fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Too few"))
        }
    }
}
