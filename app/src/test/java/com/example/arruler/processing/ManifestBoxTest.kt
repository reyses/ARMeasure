package com.example.arruler.processing

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.SupportPlane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

/** The box and support plane in manifest.json, in the convention pc-server/objmesh.py reads. */
class ManifestBoxTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun boxIsCentredOnTheVolumeWithSizeWhdAndYawDegrees() {
        val b = ObjectBox(Vec3(1f, 0.8f, -0.5f), (Math.PI / 6).toFloat(), 0.3f, 0.2f, 0.4f)
        val s = b.toSpec()
        assertEquals(listOf(1f, 1.0f, -0.5f), s.center)          // base centre y 0.8 + h/2 0.2
        assertEquals(listOf(0.3f, 0.4f, 0.2f), s.size)           // x = width, y = height, z = depth
        assertEquals(30f, s.yawDeg, 1e-3f)
    }

    @Test fun planeIsNPlusDEqualsZeroForTheServer() {
        val p = SupportPlane.horizontal(0.8f).toSpec()          // our plane: n.p = 0.8
        assertEquals(listOf(0f, 1f, 0f), p.normal)
        assertEquals(-0.8f, p.d, 0f)                            // server: n.p + d = 0
    }

    @Test fun serverBoxMaskAgreesWithObjectBoxContains() {
        // objmesh.box_mask: local = (p - centre) @ R with R = [[c,0,s],[0,1,0],[-s,0,c]]
        val yaw = 0.7f
        val b = ObjectBox(Vec3(2f, 0.5f, 1f), yaw, 0.3f, 0.2f, 0.4f)
        val spec = b.toSpec()
        val c = Math.cos(Math.toRadians(spec.yawDeg.toDouble())); val s = Math.sin(Math.toRadians(spec.yawDeg.toDouble()))
        for ((x, y, z) in listOf(Triple(2.1f, 0.7f, 1.05f), Triple(2.4f, 0.7f, 1.0f), Triple(2.0f, 0.9f, 0.7f), Triple(2f, 1.2f, 1f))) {
            val dx = x - spec.center[0]; val dy = y - spec.center[1]; val dz = z - spec.center[2]
            val lx = dx * c - dz * s; val lz = dx * s + dz * c
            val server = Math.abs(lx) <= spec.size[0] / 2 && Math.abs(dy) <= spec.size[1] / 2 && Math.abs(lz) <= spec.size[2] / 2
            assertEquals("($x,$y,$z)", b.contains(x, y, z), server)
        }
    }

    @Test fun objectJobManifestCarriesThemAndOtherJobsDoNot() {
        val box = ObjectBox(Vec3(0f, 0f, 0f), 0f, 0.25f, 0.25f, 0.25f)
        val meta = PackageMeta("1.0.0", "2026-10-03T12:00:00Z", null, ObjectQuality.QUICK, box, SupportPlane.horizontal(0f))
        val f = tmp.newFile("obj.zip")
        JobPackage.writePointJob(f, JobType.OBJECT_MESH, CloudData.fromXyz(FloatArray(300)), meta)
        val m = JobPackage.readManifest(f)
        assertEquals(0.125f, m.box!!.center[1], 1e-6f)
        assertEquals(0f, m.supportPlane!!.d, 0f)
        val text = ZipFile(f).use { z -> z.getInputStream(z.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8) }
        assertTrue(text.contains("\"support_plane\"") && text.contains("\"yaw_deg\""))

        val g = tmp.newFile("scan.zip")
        JobPackage.writePointJob(g, JobType.SCAN_ANALYZE, CloudData.fromXyz(FloatArray(300)), meta.copy(quality = null, box = null, supportPlane = null))
        val m2 = JobPackage.readManifest(g)
        assertNull(m2.box); assertNull(m2.supportPlane)
        val text2 = ZipFile(g).use { z -> z.getInputStream(z.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8) }
        assertTrue(!text2.contains("support_plane") && !text2.contains("\"box\""))
    }
}
