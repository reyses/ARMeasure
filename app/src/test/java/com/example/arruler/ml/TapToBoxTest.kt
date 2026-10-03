package com.example.arruler.ml

import com.example.arruler.depth.Intrinsics
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.SupportPlane
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapToBoxTest {
    private val w = 640; private val h = 480

    // ---------------- selection ----------------
    private val dets = listOf(
        Detection(100f, 100f, 300f, 300f, "Home good", 0.8f),     // big
        Detection(150f, 150f, 200f, 200f, "Fashion good", 0.6f),  // small, inside the big one
        Detection(500f, 400f, 560f, 450f, null, 0f),
    )

    @Test fun tapInsideTwoBoxesPicksInnermost() {
        assertEquals(dets[1], TapToBox.pick(dets, 170f, 170f, w, h))
        assertEquals(dets[0], TapToBox.pick(dets, 250f, 250f, w, h))
    }

    @Test fun tapOutsideFallsBackToNearestWithin15PercentOfDiagonal() {
        val diag = Math.sqrt((w * w + h * h).toDouble()).toFloat()          // 800 px -> 120 px limit
        assertEquals(dets[0], TapToBox.pick(listOf(dets[0]), 300f + 0.14f * diag, 200f, w, h))
        assertNull(TapToBox.pick(listOf(dets[0]), 300f + 0.16f * diag, 200f, w, h))
        assertEquals(dets[2], TapToBox.pick(dets, 600f, 440f, w, h))        // 40 px from the third box
        assertNull(TapToBox.pick(emptyList(), 10f, 10f, w, h))
    }

    // ---------------- orientation ----------------
    @Test fun rotationMath() {
        assertEquals(90, ImageOrientation.rotationDegrees(90, 0))
        assertEquals(0, ImageOrientation.rotationDegrees(90, 90))
        assertEquals(180, ImageOrientation.rotationDegrees(90, 270))
        assertEquals(270, ImageOrientation.rotationDegrees(90, 180))
        assertEquals(270, ImageOrientation.surfaceRotationDegrees(3))
    }

    @Test fun uprightMappingRoundTrips() {
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val up = ImageOrientation.imageToUpright(100f, 40f, 640, 480, rot)
            val back = ImageOrientation.uprightToImage(up[0], up[1], 640, 480, rot)
            assertEquals(100f, back[0], 1e-4f); assertEquals(40f, back[1], 1e-4f)
        }
        // clockwise 90: top-left corner goes to the top-right of the upright (480 x 640) image
        val c = ImageOrientation.imageToUpright(0f, 0f, 640, 480, 90)
        assertEquals(480f, c[0], 1e-4f); assertEquals(0f, c[1], 1e-4f)
        val b = ImageOrientation.uprightBoxToImage(0f, 0f, 100f, 50f, 640, 480, 90)
        assertEquals(0f, b[0], 1e-4f); assertEquals(380f, b[1], 1e-4f); assertEquals(50f, b[2], 1e-4f); assertEquals(480f, b[3], 1e-4f)
    }

    @Test fun nv21Interleaves() {
        // 4x2 image, I420-style planar chroma (pixelStride 1), row stride padded to 6
        val y = ByteBuffer.wrap(ByteArray(12) { it.toByte() })        // rows of 6, 4 used
        val u = ByteBuffer.wrap(byteArrayOf(10, 11, 0)); val v = ByteBuffer.wrap(byteArrayOf(20, 21, 0))
        val out = ImageOrientation.toNv21(4, 2, y, 6, u, v, 3, 1)
        assertEquals(8 + 4, out.size)
        assertEquals(6, out[4].toInt()); assertEquals(7, out[5].toInt())   // second luma row starts at index 6 in the source
        assertEquals(20, out[8].toInt()); assertEquals(10, out[9].toInt()); assertEquals(21, out[10].toInt()); assertEquals(11, out[11].toInt())
    }

    // ---------------- 3D fit ----------------
    /** Camera 0.9 m back, 0.55 m up, pitched 30 degrees down, looking at the origin; pose column-major, camera -Z forward. */
    private fun pose(): FloatArray {
        val th = -0.5f
        val m = FloatArray(16)
        m[0] = 1f
        m[5] = cos(th); m[6] = sin(th)
        m[9] = -sin(th); m[10] = cos(th)
        m[12] = 0f; m[13] = 0.55f; m[14] = 0.9f; m[15] = 1f
        return m
    }
    private val k = Intrinsics(500f, 500f, 320f, 240f, w, h)
    private val projector = CameraProjector(pose(), k)

    private fun addBox(out: ArrayList<Float>, rnd: Random, b: ObjectBox, n: Int, sigma: Float) {
        // points on the top and the four sides (what a camera above sees, plus a bit more)
        repeat(n) {
            val face = rnd.nextInt(5)
            var lx = (rnd.nextFloat() - .5f) * b.w; var lz = (rnd.nextFloat() - .5f) * b.d; var ly = rnd.nextFloat() * b.h
            when (face) { 0 -> ly = b.h; 1 -> lx = -b.w / 2; 2 -> lx = b.w / 2; 3 -> lz = -b.d / 2; else -> lz = b.d / 2 }
            val p = b.toWorld(com.example.arruler.geometry.Vec3(lx, ly, lz))
            out.add(p.x + rnd.nextGaussian().toFloat() * sigma); out.add(p.y + rnd.nextGaussian().toFloat() * sigma); out.add(p.z + rnd.nextGaussian().toFloat() * sigma)
        }
    }

    private fun detectionOf(b: ObjectBox): Detection {
        var u0 = 1e9f; var v0 = 1e9f; var u1 = -1e9f; var v1 = -1e9f
        val uv = FloatArray(2)
        for (c in b.corners()) {
            projector.project(c.x, c.y, c.z, uv)
            u0 = minOf(u0, uv[0]); u1 = maxOf(u1, uv[0]); v0 = minOf(v0, uv[1]); v1 = maxOf(v1, uv[1])
        }
        return Detection(u0, v0, u1, v1, "Home good", 0.7f)
    }

    @Test fun projectorPutsTheLookAtPointInTheImageCentre() {
        // camera looks at (0, 0, 0)?  forward = (0, sin th, -cos th) = (0, -0.479, -0.878); the origin is at (0,-0.55,-0.9) from the camera
        val uv = FloatArray(2)
        val d = projector.project(0f, 0f, 0f, uv)
        assertTrue(d > 0f)
        assertEquals(320f, uv[0], 1e-3f)
        assertTrue("v=${uv[1]}", uv[1] > 240f && uv[1] < 480f)        // below the principal point
        assertTrue(projector.project(0f, 0f, 3f, uv) <= 0f)            // behind the camera
    }

    @Test fun fitsAlignedBoxFromClutterAroundTheDetection() {
        val rnd = Random(3)
        val truth = ObjectBox(com.example.arruler.geometry.Vec3(0.02f, 0f, -0.03f), 0.5f, 0.16f, 0.09f, 0.12f)
        val cloud = ArrayList<Float>()
        addBox(cloud, rnd, truth, 1500, 0.002f)
        // table: dense grid of points on y = 0 around the object (inside the 2D box too, behind and beside)
        for (i in 0 until 6000) { cloud.add((rnd.nextFloat() - .5f) * 0.9f); cloud.add(rnd.nextGaussian().toFloat() * 0.002f); cloud.add((rnd.nextFloat() - .5f) * 0.9f) }
        // a wall 0.45 m behind the object: projects inside the detection's 2D box above the table
        for (i in 0 until 4000) { cloud.add((rnd.nextFloat() - .5f) * 0.9f); cloud.add(rnd.nextFloat() * 0.5f); cloud.add(-0.55f + rnd.nextGaussian().toFloat() * 0.002f) }
        // a neighbouring cube that must not leak into the box
        addBox(cloud, rnd, ObjectBox(com.example.arruler.geometry.Vec3(0.3f, 0f, 0.0f), 0f, 0.1f, 0.1f, 0.1f), 800, 0.002f)
        val world = cloud.toFloatArray()
        val det = detectionOf(truth)
        val r = TapToBox.fitBox(world, projector, det, SupportPlane.horizontal(0f))
        assertNotNull(r)
        val b = r!!.box
        println("fit: w=${b.w} d=${b.d} h=${b.h} yaw=${b.yaw} centre=${b.centre} pts=${r.isolated.size / 3} ${r.stats}")
        // padded by 1 cm per side: long side 0.16 + 0.02, short side 0.09 + 0.02, height 0.12 + 0.01
        assertEquals(0.18f, b.w, 0.015f); assertEquals(0.11f, b.d, 0.015f); assertEquals(0.13f, b.h, 0.015f)
        // yaw modulo pi
        val dy = abs(((b.yaw - truth.yaw + PI / 2) % PI + PI) % PI - PI / 2)
        assertTrue("yaw error ${dy * 180 / PI} deg", dy < 4 * PI / 180)
        assertEquals(truth.centre.x, b.centre.x, 0.015f); assertEquals(truth.centre.z, b.centre.z, 0.015f)
        assertEquals(0f, b.centre.y, 1e-6f)
        // the neighbouring cube (x = 0.3) must not be inside the box
        assertTrue(!b.contains(0.3f, 0.05f, 0f))
        assertNotNull(r.shape)
    }

    @Test fun nothingAboveThePlaneReturnsNull() {
        val rnd = Random(4)
        val cloud = ArrayList<Float>()
        for (i in 0 until 3000) { cloud.add((rnd.nextFloat() - .5f)); cloud.add(0f); cloud.add((rnd.nextFloat() - .5f)) }
        val det = Detection(200f, 200f, 400f, 400f, null, 0f)
        assertNull(TapToBox.fitBox(cloud.toFloatArray(), projector, det, SupportPlane.horizontal(0f)))
    }
}
