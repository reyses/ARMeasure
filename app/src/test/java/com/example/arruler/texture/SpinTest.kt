package com.example.arruler.texture

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.SupportPlane
import com.example.arruler.processing.CloudData
import com.example.arruler.processing.DeviceSummary
import com.example.arruler.processing.JobPackage
import com.example.arruler.processing.PackageMeta
import com.example.arruler.processing.ProcJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

class SpinTest {
    @get:Rule val tmp = TemporaryFolder()

    private val k = Intrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val box = ObjectBox(Vec3(0f, 0f, 0f), 0f, 0.2f, 0.2f, 0.2f)
    private val eye = Vec3(0.4f, 0.25f, 0f)             // 40 cm from the vertical axis, 25 cm above the plane
    private val pose = CameraPose.lookAt(eye, Vec3(0f, 0.1f, 0f))

    private fun roi(): SpinRoi = SpinRoi.fromHull(BoxMask.hull(box, pose.m, k), k.width, k.height)!!

    private fun sig(cube: SyntheticCube, roi: SpinRoi, yawDeg: Double): FloatArray =
        roi.signature(SyntheticCube.luma(cube.render(pose, k, Math.toRadians(yawDeg).toFloat())), k.width)

    // ---- box mask ----
    @Test fun maskCoversTheProjectedBoxAndNothingElse() {
        val hull = BoxMask.hull(box, pose.m, k)
        assertTrue(hull.size in 4..8)
        val centrePx = pose.project(Vec3(0f, 0.1f, 0f), k)!!
        assertTrue(BoxMask.inside(hull, centrePx[0], centrePx[1]))
        assertFalse(BoxMask.inside(hull, 5f, 5f))
        // every projected corner of the UNPADDED box lies inside the padded hull
        for (c in BoxMask.corners(box, 0f)) { val p = pose.project(c, k)!!; assertTrue(BoxMask.inside(hull, p[0], p[1])) }
        val m = BoxMask.rasterise(hull, k.width, k.height)
        assertEquals(255, m[centrePx[1].toInt() * k.width + centrePx[0].toInt()].toInt() and 0xFF)
        assertEquals(0, m[0].toInt())
        val white = m.count { it.toInt() != 0 }
        assertTrue("mask area $white px", white in 20_000..200_000)
    }

    @Test fun paddingGrowsTheMask() {
        val a = BoxMask.rasterise(BoxMask.hull(box, pose.m, k, 0f), k.width, k.height).count { it.toInt() != 0 }
        val b = BoxMask.rasterise(BoxMask.hull(box, pose.m, k, 0.02f), k.width, k.height).count { it.toInt() != 0 }
        assertTrue("$a < $b", b > a * 1.1)
    }

    @Test fun boxPartlyBehindTheCameraStillGivesAMask() {
        val near = CameraPose.lookAt(Vec3(0.1f, 0.1f, 0f), Vec3(-1f, 0.1f, 0f)) // inside the box's padded extent
        val hull = BoxMask.hull(box, near.m, k)
        assertTrue(hull.size >= 3)
    }

    @Test fun pngIsAValidGrayscaleImage() {
        val bytes = BoxMask.maskPng(box, pose.m, k)
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(640, img.width); assertEquals(480, img.height)
        val c = pose.project(Vec3(0f, 0.1f, 0f), k)!!
        assertEquals(255, img.raster.getSample(c[0].toInt(), c[1].toInt(), 0))
        assertEquals(0, img.raster.getSample(2, 2, 0))
    }

    // ---- spin policy ----
    @Test fun roiDifferenceGrowsWithRotationAndIsDerivedFromTheCube() {
        val roi = roi()
        for (cells in intArrayOf(4, 8, 16)) {
            val cube = SyntheticCube(cells = cells)
            val s0 = sig(cube, roi, 0.0)
            val d = listOf(2, 4, 6, 8, 10, 12, 15, 20).map { it to roi.meanAbsDiff(s0, sig(cube, roi, it.toDouble())) }
            println("ROI_DIFF cells=$cells valid=${roi.validCount} " + d.joinToString { "${it.first}deg=${"%.2f".format(it.second)}" })
        }
        // keyframes per 360 degree turn for several texture scales and ratios (measured 2026-10-03: see docs/TEXTURE.md)
        for (cells in intArrayOf(4, 8)) {
            val cube = SyntheticCube(cells = cells)
            val row = StringBuilder("ROI_SWEEP cells=$cells contrast=${"%.1f".format(roi.contrast(sig(cube, roi, 0.0)))}")
            val counts = HashMap<Float, Int>()
            for (r in listOf(0.5f, SpinConfig.DEFAULT_ROI_DIFF, 0.8f)) {
                val (kept, _) = spin(cube, SpinConfig(roiDiffThreshold = r, maxPerTurn = 1000), 1.0, 360.0)
                counts[r] = kept.size
                row.append(" R$r=${kept.size}")
            }
            println(row)
            val atDefault = counts[SpinConfig.DEFAULT_ROI_DIFF]!!
            assertTrue("cells=$cells kept $atDefault per turn", atDefault in 24..60)  // 6 to 15 degrees per keyframe
            assertTrue(counts[0.5f]!! >= atDefault && atDefault >= counts[0.8f]!!)
        }
    }

    private fun spin(cube: SyntheticCube, config: SpinConfig, stepDeg: Double, totalDeg: Double): Pair<List<Double>, SpinKeyframePolicy> {
        val roi = roi()
        val p = SpinKeyframePolicy(config, roi)
        p.begin(pose.m)
        val kept = ArrayList<Double>()
        var a = 0.0
        while (a < totalDeg) {
            val img = SyntheticCube.luma(cube.render(pose, k, Math.toRadians(a).toFloat()))
            val v = p.decide(true, roi.signature(img, k.width), Sharpness.laplacianVariance(img, k.width, k.height, k.width))
            if (v == SpinVerdict.KEEP) kept.add(a)
            a += stepDeg
        }
        return kept to p
    }

    @Test fun oneTurnGivesAboutOneKeyframePerTenDegrees() {
        val (kept, _) = spin(SyntheticCube(cells = 4), SpinConfig(), 1.0, 360.0)
        val gaps = kept.zipWithNext { a, b -> b - a }
        println("SPIN_KEPT_ONE_TURN_cells4=${kept.size} gaps=$gaps")
        assertTrue("kept ${kept.size}", kept.size in 24..48)
        assertTrue("gaps $gaps", gaps.all { it in 5.0..30.0 })
        // finer texture: more frames, but inside the 72 cap
        val (fine, _) = spin(SyntheticCube(cells = 8), SpinConfig(), 1.0, 360.0)
        println("SPIN_KEPT_ONE_TURN_cells8=${fine.size}")
        assertTrue(fine.size in kept.size..72)
    }

    @Test fun noRotationKeepsOnlyTheFirstFrame() {
        val (kept, _) = spin(SyntheticCube(cells = 8), SpinConfig(), 1.0, 1.0)
        assertEquals(1, kept.size)
        val roi = roi(); val cube = SyntheticCube(cells = 8)
        val p = SpinKeyframePolicy(SpinConfig(), roi)
        val s = sig(cube, roi, 0.0)
        assertEquals(SpinVerdict.KEEP, p.decide(true, s, 100.0))
        repeat(20) { assertEquals(SpinVerdict.UNCHANGED, p.decide(true, s, 100.0)) }
    }

    @Test fun capsAtSeventyTwoPerTurnAndTwoTurns() {
        val roi = roi(); val cube = SyntheticCube(cells = 8)
        val p = SpinKeyframePolicy(SpinConfig(roiDiffThreshold = 0.01f), roi)
        p.begin(pose.m)
        var turn1 = 0; var turn2 = 0
        var a = 0.0
        while (turn1 < 72) { if (p.decide(true, sig(cube, roi, a), 100.0) == SpinVerdict.KEEP) turn1++; a += 2.5 }
        assertEquals(SpinVerdict.CAP_REACHED, p.decide(true, sig(cube, roi, a + 90), 100.0))
        assertFalse(p.finished)
        assertTrue(p.nextTurn())
        assertEquals(1, p.turn)
        a = 0.0
        while (turn2 < 72) { if (p.decide(true, sig(cube, roi, a), 100.0) == SpinVerdict.KEEP) turn2++; a += 2.5 }
        assertTrue(p.finished)
        assertFalse(p.nextTurn())
        assertEquals(144, p.totalKept)
    }

    @Test fun blurryFramesAreRejectedRelativeToTheMedian() {
        val roi = roi(); val cube = SyntheticCube(cells = 8)
        val p = SpinKeyframePolicy(SpinConfig(), roi)
        var a = 0.0
        repeat(6) { assertEquals(SpinVerdict.KEEP, p.decide(true, sig(cube, roi, a), 100.0)); a += 12.0 }
        assertEquals(SpinVerdict.BLURRY, p.decide(true, sig(cube, roi, a), 20.0))
        assertEquals(SpinVerdict.NOT_TRACKING, p.decide(false, sig(cube, roi, a), 100.0))
    }

    private fun turned(deg: Double): FloatArray {
        val a = Math.toRadians(deg)
        val f = pose.forward
        val fx = (f.x * cos(a) + f.z * sin(a)).toFloat(); val fz = (-f.x * sin(a) + f.z * cos(a)).toFloat()
        return CameraPose.lookAt(eye, Vec3(eye.x + fx, eye.y + f.y, eye.z + fz)).m
    }

    @Test fun phoneMovedFiresOnOneCentimetreOrOneAndAHalfDegrees() {
        val p = SpinKeyframePolicy(SpinConfig(), roi())
        p.begin(pose.m)
        assertFalse(p.phoneMoved(pose.m))
        fun shifted(dx: Float) = pose.m.copyOf().also { it[12] += dx }
        assertFalse(p.phoneMoved(shifted(0.008f)))
        assertTrue(p.phoneMoved(shifted(0.012f)))
        assertFalse(p.phoneMoved(turned(1.0)))
        assertTrue(p.phoneMoved(turned(2.0)))
        val d = p.drift(turned(2.0))
        assertEquals(2.0, d.degrees, 0.3)
    }

    // ---- job ----
    private fun keyframeDir(name: String, n: Int, poseOf: (Int) -> FloatArray): File {
        val dir = tmp.newFolder(name)
        val recs = (1..n).map { i ->
            val f = KeyframeStore.fileName(i)
            File(dir, f).writeBytes(ByteArray(64) { (it + i).toByte() })
            KeyframeRecord(f, 1000L * i, poseOf(i).toList(), k.fx, k.fy, k.cx, k.cy, k.width, k.height, 10.0)
        }
        KeyframeStore.write(dir, recs)
        return dir
    }

    private val meta = PackageMeta("1.0.0", "2026-10-03T12:00:00Z", DeviceSummary("MID"))

    @Test fun spinJobHasMasksAndManifestExtras() {
        val dir = keyframeDir("spin", 4) { pose.m }
        val dest = File(tmp.root, "spin.zip")
        assertEquals(4, SpinJobBuilder.build(dest, SpinJobInput(CaptureMode.SPIN, dir, box, SupportPlane.horizontal(0f), meta)))
        val c = JobPackage.read(dest)                       // still a valid photogrammetry package
        assertEquals(4, c.imageNames.size)
        val names = ZipFile(dest).use { z -> z.entries().asSequence().map { it.name }.toList() }
        for (i in 1..4) assertTrue(names.contains("masks/%06d.jpg.png".format(i)))
        assertFalse(names.any { it.startsWith("walk/") })
        assertFalse(names.contains("cloud.ply"))
        val m = manifest(dest)
        assertEquals("spin", m["capture"]!!.jsonPrimitive.content)
        assertTrue(m["camera_static"]!!.jsonPrimitive.boolean)
        assertEquals(0.4f, m["camera_to_axis_m"]!!.jsonPrimitive.float, 1e-3f)
        assertEquals(0.25f, m["camera_height_above_plane_m"]!!.jsonPrimitive.float, 1e-3f)
        assertEquals(listOf(0.2f, 0.2f, 0.2f), m["box"]!!.jsonObject["size"]!!.jsonArray.map { it.jsonPrimitive.float })
        assertEquals(0f, m["support_plane"]!!.jsonObject["d"]!!.jsonPrimitive.float, 1e-6f)
        assertEquals("photogrammetry", m["job_type"]!!.jsonPrimitive.content)
        assertEquals(4, m["image_count"]!!.jsonPrimitive.int)
        assertTrue(m["files"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("masks/000004.jpg.png"))
    }

    @Test fun hybridJobAddsWalkFolderAndCloud() {
        val spin = keyframeDir("spin2", 3) { pose.m }
        val walk = keyframeDir("walk", 5) { i -> CameraPose.lookAt(Vec3(0.5f * cos(i.toDouble()).toFloat(), 0.3f, 0.5f * sin(i.toDouble()).toFloat()), Vec3(0f, 0.1f, 0f)).m }
        val dest = File(tmp.root, "hybrid.zip")
        val cloud = CloudData.fromXyz(FloatArray(300) { it * 0.001f })
        SpinJobBuilder.build(dest, SpinJobInput(CaptureMode.HYBRID, spin, box, SupportPlane.horizontal(0f), meta, walk, cloud))
        val c = JobPackage.read(dest)
        assertEquals(3, c.imageNames.size)                  // walk images live under walk/, not images/
        assertEquals(100, c.cloud!!.count)
        val names = ZipFile(dest).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertTrue(names.contains("walk/poses.json"))
        for (i in 1..5) assertTrue(names.contains("walk/images/%06d.jpg".format(i)))
        val m = manifest(dest)
        assertEquals("hybrid", m["capture"]!!.jsonPrimitive.content)
        val w = m["walk"]!!.jsonObject
        assertEquals(5, w["image_count"]!!.jsonPrimitive.int)
        assertEquals("walk/poses.json", w["poses"]!!.jsonPrimitive.content)
        val poses = ZipFile(dest).use { z -> ProcJson.json.parseToJsonElement(z.getInputStream(z.getEntry("walk/poses.json")).readBytes().toString(Charsets.UTF_8)).jsonObject }
        assertEquals(5, (poses["images"] as JsonArray).size)
    }

    @Test fun hybridWithoutWalkDataIsRejected() {
        val spin = keyframeDir("spin3", 2) { pose.m }
        try {
            SpinJobBuilder.build(File(tmp.root, "h.zip"), SpinJobInput(CaptureMode.HYBRID, spin, box, SupportPlane.horizontal(0f), meta))
            assertTrue("should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("walkDir"))
        }
    }

    private fun manifest(zip: File): JsonObject = ZipFile(zip).use { z ->
        ProcJson.json.parseToJsonElement(z.getInputStream(z.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)).jsonObject
    }
}
