package com.example.arruler.texture

import com.example.arruler.geometry.Vec3
import com.example.arruler.processing.DeviceSummary
import com.example.arruler.processing.JobPackage
import com.example.arruler.processing.PackageMeta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeyframeSupportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val k = Intrinsics(700f, 700f, 320f, 240f, 640, 480)
    private val centre = Vec3(0f, 0.1f, 0f)

    private fun pose(az: Double, el: Double = 20.0, dist: Float = 0.7f): FloatArray {
        val a = Math.toRadians(az); val e = Math.toRadians(el)
        val eye = Vec3(centre.x + dist * (Math.cos(e) * Math.cos(a)).toFloat(), centre.y + dist * Math.sin(e).toFloat(), centre.z + dist * (Math.cos(e) * Math.sin(a)).toFloat())
        return CameraPose.lookAt(eye, centre).m
    }

    // ---- sharpness ----
    private fun checker(w: Int, h: Int, cell: Int) = ByteArray(w * h) { if (((it % w) / cell + (it / w) / cell) % 2 == 0) 220.toByte() else 30.toByte() }

    private fun blur(y: ByteArray, w: Int, h: Int, passes: Int): ByteArray {
        var cur = y
        repeat(passes) {
            val n = ByteArray(cur.size)
            for (j in 0 until h) for (i in 0 until w) {
                var s = 0; var c = 0
                for (dj in -1..1) for (di in -1..1) { val xx = i + di; val yy = j + dj; if (xx in 0 until w && yy in 0 until h) { s += cur[yy * w + xx].toInt() and 0xFF; c++ } }
                n[j * w + i] = (s / c).toByte()
            }
            cur = n
        }
        return cur
    }

    @Test fun sharpnessRanksSharpAboveBlurredAboveFlat() {
        val sharp = checker(320, 240, 4)
        val soft = blur(sharp, 320, 240, 3)
        val flat = ByteArray(320 * 240) { 128.toByte() }
        val a = Sharpness.laplacianVariance(sharp, 320, 240, 320, 1, 1)
        val b = Sharpness.laplacianVariance(soft, 320, 240, 320, 1, 1)
        val c = Sharpness.laplacianVariance(flat, 320, 240, 320, 1, 1)
        assertTrue("$a > $b", a > 3 * b)
        assertEquals(0.0, c, 1e-9)
    }

    @Test fun sharpnessHonoursRowAndPixelStride() {
        val w = 64; val h = 48
        val img = checker(w, h, 2)
        val padded = ByteArray(80 * h) { 7 }
        for (j in 0 until h) for (i in 0 until w) padded[j * 80 + i] = img[j * w + i]
        assertEquals(Sharpness.laplacianVariance(img, w, h, w, 1, 1), Sharpness.laplacianVariance(padded, w, h, 80, 1, 1), 1e-9)
        val inter = ByteArray(w * 2 * h)
        for (j in 0 until h) for (i in 0 until w) inter[j * w * 2 + i * 2] = img[j * w + i]
        assertEquals(Sharpness.laplacianVariance(img, w, h, w, 1, 1), Sharpness.laplacianVariance(inter, w, h, w * 2, 2, 1), 1e-9)
    }

    @Test fun runningMedianKeepsWindow() {
        val m = RunningMedian(3)
        for (v in listOf(1.0, 100.0, 5.0, 6.0)) m.add(v)
        assertEquals(6.0, m.median(), 0.0) // window 100, 5, 6
    }

    // ---- walk-around policy ----
    @Test fun policyRejectsNotTracking() {
        val p = KeyframePolicy()
        assertEquals(KeyframeVerdict.NOT_TRACKING, p.decide(false, pose(0.0), k, centre, 100.0))
    }

    @Test fun policyEnforcesMinimumAngle() {
        val p = KeyframePolicy()
        assertEquals(KeyframeVerdict.KEEP, p.decide(true, pose(0.0), k, centre, 100.0))
        assertEquals(KeyframeVerdict.TOO_CLOSE, p.decide(true, pose(5.0), k, centre, 100.0))
        assertEquals(KeyframeVerdict.KEEP, p.decide(true, pose(9.0), k, centre, 100.0))
        assertTrue(p.minKeptSeparationDeg() >= 8.0)
    }

    @Test fun policyRejectsBoxCentreOutsideCentral80Percent() {
        val p = KeyframePolicy()
        // look at a point to the side so the box centre lands near the image edge
        val eye = Vec3(0.7f, 0.1f, 0f)
        val off = CameraPose.lookAt(eye, Vec3(0f, 0.1f, 0.62f)).m
        val pr = CameraPose(off).project(centre, k)!!
        assertTrue(pr[0] < 0.1f * 640 || pr[0] > 0.9f * 640)
        assertEquals(KeyframeVerdict.OUT_OF_FRAME, p.decide(true, off, k, centre, 100.0))
        // behind the camera
        val away = CameraPose.lookAt(eye, Vec3(1.7f, 0.1f, 0f)).m
        assertEquals(KeyframeVerdict.OUT_OF_FRAME, p.decide(true, away, k, centre, 100.0))
    }

    @Test fun policyRejectsBlurRelativeToRunningMedian() {
        val p = KeyframePolicy()
        for (i in 0 until 6) assertEquals(KeyframeVerdict.KEEP, p.decide(true, pose(i * 20.0), k, centre, 100.0 + i))
        assertEquals(KeyframeVerdict.BLURRY, p.decide(true, pose(130.0), k, centre, 30.0))
        assertEquals(KeyframeVerdict.KEEP, p.decide(true, pose(150.0), k, centre, 80.0))
    }

    @Test fun policyCapsAt120() {
        val p = KeyframePolicy()
        var kept = 0
        val rnd = java.util.Random(5)
        for (i in 0 until 4000) {
            val az = rnd.nextDouble() * 360.0; val el = rnd.nextDouble() * 150.0 - 75.0
            if (p.decide(true, pose(az, el), k, centre, 100.0) == KeyframeVerdict.KEEP) kept++
        }
        assertEquals(120, kept)
        assertEquals(KeyframeVerdict.CAP_REACHED, p.decide(true, pose(1.0, 85.0), k, centre, 100.0))
    }

    // ---- NV21 ----
    @Test fun nv21InterleavesVuAndHonoursStrides() {
        val w = 4; val h = 4
        val y = ByteArray(8 * h) { 0 }
        for (j in 0 until h) for (i in 0 until w) y[j * 8 + i] = (j * w + i).toByte()
        // chroma: pixel stride 2, row stride 8, 2 rows of 2 samples
        val u = ByteArray(16) { 0 }; val v = ByteArray(16) { 0 }
        for (j in 0 until 2) for (i in 0 until 2) { u[j * 8 + i * 2] = (100 + j * 2 + i).toByte(); v[j * 8 + i * 2] = (200 + j * 2 + i).toByte() }
        val nv = YuvConvert.toNv21(y, 8, u, v, 8, 2, w, h)
        assertEquals(24, nv.size)
        for (i in 0 until 16) assertEquals(i.toByte(), nv[i])
        assertArrayEquals(byteArrayOf(200.toByte(), 100, 201.toByte(), 101, 202.toByte(), 102, 203.toByte(), 103), nv.copyOfRange(16, 24))
    }

    // ---- camera config ranking ----
    @Test fun rankingPrefersLargestImageUnderTheCap() {
        val cfgs = listOf(
            ConfigInfo(0, 640, 480, 30, 30, false),
            ConfigInfo(1, 1280, 720, 30, 30, false),
            ConfigInfo(2, 1920, 1080, 30, 30, false),
            ConfigInfo(3, 3840, 2160, 30, 30, false),
            ConfigInfo(4, 1920, 1080, 30, 30, true),
        )
        val r = CameraConfigRanking.rank(cfgs)
        assertEquals(2, r[0].index)          // 1080p without the depth sensor beats 1080p needing it
        assertEquals(4, r[1].index)
        assertEquals(3, r[2].index)          // above the cap: ranked after the capped-equal ones
        assertEquals(0, r.last().index)
    }

    @Test fun rankingDropsSlowConfigsWhenFastExist() {
        val cfgs = listOf(ConfigInfo(0, 1920, 1080, 15, 15, false), ConfigInfo(1, 640, 480, 30, 30, false))
        assertEquals(listOf(1), CameraConfigRanking.rank(cfgs).map { it.index })
        assertEquals(listOf(0), CameraConfigRanking.rank(listOf(cfgs[0])).map { it.index })
    }

    // ---- store + photogrammetry job round trip ----
    @Test fun keyframesRoundTripIntoPhotogrammetryZip() {
        val dir = tmp.newFolder("scan")
        val recs = (1..3).map { i ->
            val name = KeyframeStore.fileName(i)
            File(dir, name).writeBytes(ByteArray(100 + i) { (it + i).toByte() })
            KeyframeRecord(name, 1000L * i, pose(i * 30.0).toList(), 700f + i, 701f, 320f, 240f, 640, 480, 12.5)
        }
        KeyframeStore.write(dir, recs)
        assertEquals(recs, KeyframeStore.read(dir))
        val dest = File(tmp.root, "job.zip")
        val meta = PackageMeta("1.0.0", "2026-10-03T12:00:00Z", DeviceSummary("MID"))
        assertEquals(3, PhotogrammetryJobBuilder.build(dir, dest, meta))
        val c = JobPackage.read(dest)
        assertEquals("photogrammetry", c.manifest.jobType)
        assertEquals(3, c.manifest.imageCount)
        assertEquals(listOf("images/000001.jpg", "images/000002.jpg", "images/000003.jpg"), c.imageNames)
        val poses = c.poses!!.images
        assertEquals(3, poses.size)
        assertEquals(recs[1].pose, poses[1].pose)
        assertEquals(702f, poses[1].fx, 0f)
        assertEquals(640, poses[2].width)
        assertEquals(2000L, poses[1].timestampNs)
    }

    @Test fun builderRejectsAnEmptyFolder() {
        val dir = tmp.newFolder("empty")
        try {
            PhotogrammetryJobBuilder.build(dir, File(tmp.root, "x.zip"), PackageMeta("1", "2026-10-03T12:00:00Z"))
            assertFalse("should have thrown", true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no keyframes"))
        }
    }

    @Test fun poseMathProjectsTheBoxCentreToTheImageCentre() {
        val p = CameraPose(pose(33.0))
        val px = p.project(centre, k)!!
        assertEquals(320f, px[0], 0.01f); assertEquals(240f, px[1], 0.01f)
        assertEquals(0.7f, px[2], 1e-3f)
        assertEquals(null, p.project(Vec3(p.position.x * 2, p.position.y * 2, p.position.z * 2), k)) // behind the camera
    }
}
