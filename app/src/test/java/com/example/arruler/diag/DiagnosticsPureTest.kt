package com.example.arruler.diag

import com.example.arruler.gpu.GpuBench
import com.example.arruler.gpu.GpuFixtures
import com.example.arruler.gpu.GpuGate
import com.example.arruler.gpu.GpuGateLogic
import com.example.arruler.gpu.GpuImageOps
import com.example.arruler.gpu.GpuKernel
import com.example.arruler.gpu.GpuVerification
import com.example.arruler.gpu.KernelCost
import com.example.arruler.objscan.ObjectDenoise
import com.example.arruler.texture.Sharpness
import com.example.arruler.texture.SpinRoi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DiagnosticsPureTest {
    private fun cam(
        id: String, facing: String = "BACK", f: Float, w: Float, pose: List<Float>? = null, parent: String? = null,
        phys: List<String> = emptyList(), depth: List<String> = emptyList(), caps: List<String> = emptyList(),
    ) = LensCamera(id, parent, facing, listOf(f), w to (w * 0.75f), 0f, pose, null, null, caps, phys, depth)

    // Pixel-8-Pro-like: logical 0 over physical 2 (main), 3 (ultrawide), 4 (tele)
    private val main = cam("2", f = 6.9f, w = 9.8f, pose = listOf(0f, 0f, 0f), parent = "0")
    private val ultra = cam("3", f = 2.0f, w = 5.6f, pose = listOf(0.0123f, 0f, 0f), parent = "0")
    private val tele = cam("4", f = 13.0f, w = 5.6f, pose = listOf(-0.0301f, 0.004f, 0f), parent = "0")
    private val logical = cam("0", f = 6.9f, w = 9.8f, pose = listOf(0f, 0f, 0f), phys = listOf("2", "3", "4"), caps = listOf("LOGICAL_MULTI_CAMERA"))
    private val front = cam("1", facing = "FRONT", f = 2.0f, w = 4.0f)
    private val all = listOf(logical, front, main, ultra, tele)

    @Test fun baselineIsTheEuclideanDistanceInMillimetres() {
        assertEquals(12.3, LensGeometry.baselineMm(listOf(0f, 0f, 0f), listOf(0.0123f, 0f, 0f))!!, 1e-3)
        assertEquals(50.0, LensGeometry.baselineMm(listOf(0.03f, 0f, 0f), listOf(0f, 0.04f, 0f))!!, 1e-3)
        assertNull(LensGeometry.baselineMm(null, listOf(0f, 0f, 0f)))
        assertNull(LensGeometry.baselineMm(listOf(0f), listOf(0f, 0f, 0f)))
    }

    @Test fun rolesFollowFieldOfViewAndIgnoreTheLogicalParentAndTheFrontCamera() {
        val r = LensGeometry.roles(all)
        assertEquals("3", r[LensRole.ULTRAWIDE]!!.id)
        assertEquals("2", r[LensRole.MAIN]!!.id)
        assertEquals("4", r[LensRole.TELE]!!.id)
        assertEquals(12.3, LensGeometry.baselineMm(all, LensRole.MAIN, LensRole.ULTRAWIDE)!!, 1e-3)
        assertNull(LensGeometry.baselineMm(listOf(front), LensRole.MAIN, LensRole.ULTRAWIDE))
    }

    @Test fun minFocusConvertsDioptersToCentimetres() {
        assertEquals(10.0, LensGeometry.minFocusCm(10f)!!, 1e-9)
        assertEquals(40.0, LensGeometry.minFocusCm(2.5f)!!, 1e-9)
        assertNull(LensGeometry.minFocusCm(0f))
        assertNull(LensGeometry.minFocusCm(null))
    }

    @Test fun depthSensorLineSaysNoOrYesWithTheStream() {
        assertTrue(ReportFormat.depthSensorLine(all).startsWith("Depth sensor (ToF): no"))
        val tof = cam("5", f = 3f, w = 4f, depth = listOf("DEPTH16 640x480"), caps = listOf("DEPTH_OUTPUT"))
        val line = ReportFormat.depthSensorLine(all + tof)
        assertTrue(line, line.startsWith("Depth sensor (ToF): yes") && line.contains("DEPTH16 640x480"))
    }

    private fun data(cams: List<LensCamera> = all): DeviceReportData {
        val row = ArConfigRow(0, "640x480", "1920x1080", "30-30", "DO_NOT_USE", "DO_NOT_USE", "BACK", "0")
        return DeviceReportData(
            DeviceBasics("Google", "Pixel 8 Pro", "husky", "Google", "Tensor G3", 35, "15", "google/husky/husky:15/AP4A/1:user/release-keys", "1.0.0", 1, "HIGH", 480, "NONE", 11.2, 9),
            GlSummary("ARM", "Mali-G715", "OpenGL ES 3.2", "yes, ES 3.2", "level 1, 1.3.0", null),
            ArSummary("SUPPORTED_INSTALLED", "1.56.0", listOf("DISABLED" to true, "AUTOMATIC" to true), listOf(row), row, true, null),
            CameraSummary(cams, listOf(listOf("0", "1")), null, null),
        )
    }

    @Test fun reportIsPlainReadableAndCompact() {
        val gpu = listOf("GPU: verified 3/6 kernels on Mali-G715", "Self-test: 3/6 kernels PASS, 9.0 s")
        val text = ReportFormat.format(data(), gpu, 1_790_000_000_000L, TimeZone.getTimeZone("UTC"))
        for (needle in listOf(
            "== Device ==", "== GPU ==", "== AR ==", "== Cameras ==", "Depth sensor (ToF): no", "Concurrent cameras: 0+1",
            "Stereo baseline main<->ultrawide: 12.3 mm", "Pixel 8 Pro", "Mali-G715", "GPU: verified 3/6",
        )) assertTrue("missing '$needle' in:\n$text", text.contains(needle))
        assertTrue("size ${text.length}", text.length < 8 * 1024)
    }

    @Test fun reportWithoutSelfTestOrCamerasStillFormats() {
        val text = ReportFormat.format(DeviceReportData(null, null, null, null), emptyList(), 0L, TimeZone.getTimeZone("UTC"))
        assertTrue(text.contains("Self-test: not run"))
    }

    @Test fun fileNameDropsUnsafeCharacters() {
        assertEquals("Pixel 8 Pro 1970-01-01.txt", ReportFormat.fileName("Pixel 8 Pro", 0L, TimeZone.getTimeZone("UTC")))
        assertTrue(ReportFormat.fileName("a/b:c", 0L, TimeZone.getTimeZone("UTC")).startsWith("abc "))
    }

    // ---- gate ----

    private fun rec(passed: Set<GpuKernel>, key: String = GpuGateLogic.key("fp", 3, "Mali-G715", "OpenGL ES 3.2")) = GpuVerification(
        key, "Mali-G715", "OpenGL ES 3.2", passed,
        GpuKernel.values().associateWith { KernelCost(100.0, 10.0, 1.0) }, 5.0, 123L,
    )

    @Test fun persistenceKeyCombinesFingerprintVersionRendererAndGlVersion() {
        assertEquals("fp|v3|Mali-G715|OpenGL ES 3.2", GpuGateLogic.key("fp", 3, "Mali-G715", "OpenGL ES 3.2"))
        assertTrue(GpuGateLogic.key("fp", 3, "A", "B") != GpuGateLogic.key("fp", 4, "A", "B"))
        assertTrue(GpuGateLogic.key("fp", 3, "A", "B") != GpuGateLogic.key("fp2", 3, "A", "B"))
        assertTrue(GpuGateLogic.key("fp", 3, "A", "B") != GpuGateLogic.key("fp", 3, "A2", "B"))
        assertTrue(GpuGateLogic.matchesDevice(rec(emptySet()), "fp", 3))
        assertFalse(GpuGateLogic.matchesDevice(rec(emptySet()), "fp", 4))
        assertFalse(GpuGateLogic.matchesDevice(null, "fp", 3))
    }

    @Test fun verificationRoundTripsThroughText() {
        val r = rec(setOf(GpuKernel.DENOISE, GpuKernel.RANSAC))
        assertEquals(r, GpuVerification.decode(r.encode()))
        assertNull(GpuVerification.decode(null))
        assertNull(GpuVerification.decode("garbage"))
        assertNull(GpuVerification.decode("v1\nrenderer=x"))
    }

    @Test fun gateUsesTheGpuOnlyWhenEverythingAgrees() {
        val r = rec(setOf(GpuKernel.DENOISE))
        assertTrue(GpuGateLogic.useGpu(true, r, true, GpuKernel.DENOISE, 100_000))
        assertFalse("setting off", GpuGateLogic.useGpu(false, r, true, GpuKernel.DENOISE, 100_000))
        assertFalse("other driver or build", GpuGateLogic.useGpu(true, r, false, GpuKernel.DENOISE, 100_000))
        assertFalse("no record", GpuGateLogic.useGpu(true, null, true, GpuKernel.DENOISE, 100_000))
        assertFalse("kernel did not pass", GpuGateLogic.useGpu(true, r, true, GpuKernel.RANSAC, 100_000))
        assertFalse("too small a job", GpuGateLogic.useGpu(true, r, true, GpuKernel.DENOISE, 10))
        assertTrue("size left to the caller", GpuGateLogic.useGpu(true, r, true, GpuKernel.DENOISE, null))
        val slow = GpuVerification(r.key, "x", "y", setOf(GpuKernel.DENOISE), mapOf(GpuKernel.DENOISE to KernelCost(100.0, 100.0, 1.0)), 1.0, 0)
        assertFalse("GPU not faster", GpuGateLogic.useGpu(true, slow, true, GpuKernel.DENOISE, 10_000_000))
    }

    @Test fun statusLineNamesTheStates() {
        assertEquals("GPU: not verified - run Diagnostics", GpuGateLogic.statusLine(true, null, false))
        assertEquals("GPU: verified 3/6 kernels on Mali-G715", GpuGateLogic.statusLine(true, rec(setOf(GpuKernel.DENOISE, GpuKernel.RANSAC, GpuKernel.SHARPNESS)), true))
        assertTrue(GpuGateLogic.statusLine(false, rec(setOf(GpuKernel.DENOISE)), true).startsWith("GPU: off"))
        assertTrue(GpuGateLogic.statusLine(true, rec(emptySet()), true).contains("0/6"))
        assertTrue(GpuGateLogic.statusLine(true, rec(setOf(GpuKernel.DENOISE)), false).contains("run Diagnostics again"))
    }

    @Test fun judgeNeedsTheGpuAndAnErrorInsideTheTolerance() {
        assertTrue(GpuSelfTest.judge(0.05, 0.1, true))
        assertTrue(GpuSelfTest.judge(0.0, 0.0, true))
        assertFalse(GpuSelfTest.judge(0.2, 0.1, true))
        assertFalse(GpuSelfTest.judge(0.0, 0.1, false))
        assertFalse(GpuSelfTest.judge(null, 0.1, true))
        assertFalse(GpuSelfTest.judge(Double.NaN, 0.1, true))
        assertFalse(GpuSelfTest.judge(Double.POSITIVE_INFINITY, 0.1, true))
    }

    @Test fun costsComeFromMeasuredTimingsAndProfileOnlyVerifiedKernels() {
        val results = listOf(
            KernelResult(GpuKernel.DENOISE, true, 0.01, 0.1, "mm", 200.0, 20.0, 20_000, null),
            KernelResult(GpuKernel.RANSAC, false, null, 0.0, "", null, null, 0, "boom"),
        )
        val rep = GpuSelfTestReport("R", "V", "Vendor", null, results, null, 5000.0, 1L)
        assertEquals(setOf(GpuKernel.DENOISE), rep.passed)
        assertEquals(setOf(GpuKernel.DENOISE), rep.costs().keys)
        val v = rep.toVerification("k")
        assertTrue(v.profile().useGpu(GpuKernel.DENOISE, 1_000_000))
        assertFalse(v.profile().useGpu(GpuKernel.RANSAC, 1_000_000))
        assertTrue(rep.lines().any { it.contains("DENOISE PASS") } && rep.lines().any { it.contains("RANSAC FAIL") && it.contains("boom") })
    }

    // ---- gate closed == CPU path (no init, no record: the default before any self-test) ----

    @Test fun closedGateDenoiseIsTheCpuResult() {
        val pts = GpuFixtures.slab(3_000, 5)
        assertArrayEquals(ObjectDenoise.smooth(pts, 0.012f, 2), GpuGate.denoise(pts, 0.012f, 2), 0f)
    }

    @Test fun closedGateImageOpsAreTheCpuResults() {
        val y = GpuFixtures.lumaPlane(640, 480, 640, 1)
        assertEquals(Sharpness.laplacianVariance(y, 640, 480, 640, 1), GpuGate.sharpness(y, 640, 480, 640, 1), 0.0)
        val roi = SpinRoi.fromHull(GpuFixtures.roiHull(), 1280, 960)
        assertNotNull(roi)
        val y2 = GpuFixtures.lumaPlane(1280, 960, 1280, 1)
        assertArrayEquals(roi!!.signature(y2, 1280, 1), GpuGate.roiSignature(roi, y2, 1280, 1), 0f)
        val nv = GpuFixtures.nv21(64, 48)
        assertArrayEquals(GpuImageOps.nv21ToArgbCpu(nv, 64, 48), GpuGate.nv21ToArgb(nv, 64, 48))
    }

    @Test fun closedGateHandsOutNoContextAndNoTextureOverride() {
        for (k in GpuKernel.values()) assertNull(GpuGate.contextFor(k, 1_000_000))
        assertNull(GpuGate.ransacContext())
        assertNull(GpuGate.profile())
        val kfs = listOf(GpuFixtures.keyframe(64, 48, 1))
        assertNull(GpuGate.textureSample(GpuFixtures.texelPlan(64, 1, 1f, 1, 64, 48), kfs))
    }

    @Test fun fixturesAreDeterministic() {
        assertArrayEquals(GpuFixtures.slab(100, 3), GpuBench.slab(100, 3), 0f)
        assertArrayEquals(GpuFixtures.room(50, 2), GpuFixtures.room(50, 2), 0f)
    }
}
