package com.example.arruler.objscan

import com.example.arruler.geometry.Vec3
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class AutoBoxTest {

    private class FakeDepth(private val pts: FloatArray, private val voxels: Int = pts.size / 3) : DepthPointSource {
        override fun voxelCount(): Int = voxels
        override suspend fun points(): FloatArray = pts
    }

    private fun ctx(depth: DepthPointSource, tapOnPlane: Vec3 = Fixtures.origin) =
        FrameContext(Fixtures.plane, tapOnPlane.copy(y = 0.1f), tapOnPlane, Vec3(0f, 0.5f, 1f), 1080, 2340, depth)

    @Test fun builtInProviderAlignsTheBoxToTheObjectAtTheTap() = runBlocking {
        val voxel = ObjectQuality.QUICK.voxelSize
        val scene = Fixtures.boxScene(Random(21), 0.30f, 0.15f, 0.20f, 30f, 0.002f, voxel)
        val search = ObjectPlacement.searchBox(Fixtures.origin)
        val cloud = ObjectVoxelCloud(search, voxel)
        cloud.addAll(scene.points)
        val box = DepthFitAutoBox(timeoutMs = 300, pollMs = 20).propose(540f, 1200f, ctx(FakeDepth(cloud.points())))
        assertNotNull(box)
        val dims = listOf(box!!.w, box.d).sortedDescending()
        assertEquals(0.32f, dims[0], 0.03f)
        assertEquals(0.17f, dims[1], 0.03f)
        // yaw is aligned: the long side lies at 30 degrees (mod 90) to the world axes
        val yawDeg = Math.toDegrees(box.yaw.toDouble())
        val mod = ((yawDeg % 90) + 90) % 90
        assertTrue("yaw $yawDeg", abs(mod - 30) < 6 || abs(mod - 60) < 6)
    }

    @Test fun providerGivesUpWithoutDepth() = runBlocking {
        val box = DepthFitAutoBox(timeoutMs = 100, pollMs = 20).propose(0f, 0f, ctx(FakeDepth(FloatArray(0), voxels = 0)))
        assertNull(box)
    }

    @Test fun swappingTheProviderIsOneLine() = runBlocking {
        val fixed = ObjectBox(Vec3(1f, 0f, 1f), 0f, 0.1f, 0.1f, 0.1f)
        val ml = object : AutoBoxProvider {
            override suspend fun propose(tapX: Float, tapY: Float, frameContext: FrameContext) = fixed
        }
        val provider: AutoBoxProvider = ml
        assertEquals(fixed, provider.propose(0f, 0f, ctx(FakeDepth(FloatArray(0)))))
    }

    // ---- placement: drag, scale ----

    @Test fun dragKeepsTheGrabOffsetAndTheBaseHeight() {
        val box = ObjectBox(Vec3(1f, 0.7f, 2f), 0.3f, 0.2f, 0.3f, 0.4f)
        val offset = ObjectPlacement.grabOffset(box, Vec3(1.1f, 0.7f, 2.05f))
        val moved = ObjectPlacement.dragMove(box, offset, Vec3(2.1f, 0.7f, 3.05f))
        assertEquals(2f, moved.centre.x, 1e-5f)
        assertEquals(3f, moved.centre.z, 1e-5f)
        assertEquals(0.7f, moved.centre.y, 0f)
        assertEquals(box.w, moved.w, 0f)
        assertEquals(box.yaw, moved.yaw, 0f)
    }

    @Test fun uniformScaleClampsAndKeepsTheBase() {
        val box = ObjectPlacement.defaultBox(Vec3(0f, 0.5f, 0f))
        val bigger = ObjectPlacement.scaleUniform(box, ObjectPlacement.SCALE_STEP)
        assertEquals(0.25f * 1.05f, bigger.w, 1e-6f)
        assertEquals(0.25f * 1.05f, bigger.h, 1e-6f)
        assertEquals(box.centre, bigger.centre)
        var b = box
        repeat(200) { b = ObjectPlacement.scaleUniform(b, 1f / ObjectPlacement.SCALE_STEP) }
        assertEquals(ObjectPlacement.MIN_SIDE, b.w, 0f)
        repeat(400) { b = ObjectPlacement.scaleUniform(b, ObjectPlacement.SCALE_STEP) }
        assertEquals(ObjectPlacement.MAX_SIDE, b.h, 0f)
    }

    // ---- ray / plane ----

    /** Column-major perspective projection (fov y in degrees) and a camera at [eye] looking down -Z. */
    private fun proj(fovDeg: Float, aspect: Float, n: Float, f: Float): FloatArray {
        val t = 1f / Math.tan(Math.toRadians(fovDeg / 2.0)).toFloat()
        return floatArrayOf(t / aspect, 0f, 0f, 0f, 0f, t, 0f, 0f, 0f, 0f, (f + n) / (n - f), -1f, 0f, 0f, 2 * f * n / (n - f), 0f)
    }

    private fun lookDownMinusZ(ex: Float, ey: Float, ez: Float) =
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, -ex, -ey, -ez, 1f)

    @Test fun centreRayLooksDownTheCameraAxis() {
        val r = PlaneRaycast.rayFromScreen(540f, 1170f, 1080, 2340, lookDownMinusZ(1f, 2f, 3f), proj(60f, 0.46f, 0.05f, 100f))!!
        assertEquals(1f, r.origin.x, 0.01f)
        assertEquals(2f, r.origin.y, 0.01f)
        assertEquals(0f, r.dir.x, 1e-4f); assertEquals(0f, r.dir.y, 1e-4f); assertEquals(-1f, r.dir.z, 1e-4f)
    }

    @Test fun offCentreRayLeansTowardsTheTouch() {
        val view = lookDownMinusZ(0f, 0f, 0f)
        val p = proj(60f, 1f, 0.05f, 100f)
        val right = PlaneRaycast.rayFromScreen(1000f, 500f, 1000, 1000, view, p)!!
        assertTrue(right.dir.x > 0.3f)
        val up = PlaneRaycast.rayFromScreen(500f, 0f, 1000, 1000, view, p)!!
        assertTrue(up.dir.y > 0.3f)   // pixel y grows downwards, the top edge looks up
        // the edge ray of a 60 degree fov is at 30 degrees
        assertEquals(Math.tan(Math.toRadians(30.0)), (up.dir.y / -up.dir.z).toDouble(), 1e-3)
    }

    @Test fun rayMeetsAHorizontalPlaneInFrontOfIt() {
        val ray = Ray(Vec3(0f, 1.5f, 0f), Vec3(0f, -0.6f, -0.8f))
        val hit = PlaneRaycast.intersectHorizontal(ray, 0f)!!
        assertEquals(0f, hit.y, 0f)
        assertEquals(-2f, hit.z, 1e-5f)
        assertNull(PlaneRaycast.intersectHorizontal(ray, 3f))                         // plane behind the ray
        assertNull(PlaneRaycast.intersectHorizontal(Ray(Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f)), 0f)) // parallel
    }

    @Test fun inverseTimesMatrixIsIdentity() {
        val m = PlaneRaycast.mul(proj(50f, 0.5f, 0.1f, 50f), lookDownMinusZ(0.3f, -1f, 2f))
        val id = PlaneRaycast.mul(m, PlaneRaycast.invert(m)!!)
        for (c in 0 until 4) for (r in 0 until 4) assertEquals(if (c == r) 1f else 0f, id[c * 4 + r], 1e-3f)
        assertNull(PlaneRaycast.invert(FloatArray(16)))
    }

    // ---- support plane ----

    @Test fun supportPlaneIsTheHighestOneBelowTheTapNearIt() {
        val tap = Vec3(0f, 0.95f, 0f)
        val floor = PlaneCandidate(0f, 0f)
        val table = PlaneCandidate(0.72f, 0.1f)
        val farShelf = PlaneCandidate(0.9f, 3f)       // too far sideways
        val ceiling = PlaneCandidate(2.4f, 0f)        // above the tap
        assertEquals(0.72f, SupportPlanePick.pick(tap, listOf(floor, table, farShelf, ceiling))!!, 0f)
        assertEquals(0f, SupportPlanePick.pick(tap, listOf(floor, ceiling))!!, 0f)
        assertNull(SupportPlanePick.pick(Vec3(0f, 3.5f, 0f), listOf(floor)))          // more than 2 m below
        assertNull(SupportPlanePick.pick(tap, emptyList()))
    }

    // ---- capture cards, spin texts ----

    @Test fun spinAndHybridNeedThePcAndAnImplementation() {
        val none = CaptureModes.options(pcPaired = false, spinAvailable = true)
        assertEquals(listOf(CaptureMode.WALK, CaptureMode.SPIN, CaptureMode.HYBRID), none.map { it.mode })
        assertTrue(none[0].enabled)
        assertFalse(none[1].enabled); assertEquals("needs your PC", none[1].reason)
        assertFalse(none[2].enabled)
        val soon = CaptureModes.options(pcPaired = true, spinAvailable = false)
        assertEquals("coming soon", soon[1].reason)
        val ok = CaptureModes.options(pcPaired = true, spinAvailable = true)
        assertTrue(ok.all { it.enabled && it.reason == null })
    }

    @Test fun spinProgressText() {
        assertEquals("Turn 1 of 2 · 34 photos", SpinText.progress(SpinProgress(1, 2, 34)))
        assertFalse(NoSpinCapture.available)
        assertFalse(NoSpinCapture.phoneMoved.value)
        assertNull(NoSpinCapture.progress.value)
    }

    @Test fun texturedObjectReferencesAreTheDocumentedOnes() {
        assertEquals("mtllib mesh.mtl", TexturedObject.OBJ_MTL_REF)
        assertEquals("map_Kd texture.png", TexturedObject.MTL_PNG_REF)
    }
}
