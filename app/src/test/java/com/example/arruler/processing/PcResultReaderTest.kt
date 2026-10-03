package com.example.arruler.processing

import com.example.arruler.measure.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The result ZIP exactly as pc-server writes it (objmesh.run + jobs.py), read by the phone's reader. */
class PcResultReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * Copied from what armeasure_pc/processing/objmesh.py run() returns, wrapped by jobs.py as
     * {"schema": 1, "job_type": "object_mesh", **result, "files": [...]} with json.dumps(indent=1).
     * Note the extra stats keys (quality, voxel_mm, poisson_depth), the extra volume_variants_m3 key
     * (convex_hull) and the python versions map.
     */
    private val pcResultJson = """
{
 "schema": 1,
 "job_type": "object_mesh",
 "measures": {
  "object_dims": {
   "length_m": 0.20312,
   "width_m": 0.19876,
   "height_m": 0.2003
  },
  "volume_m3": {
   "low": 0.0078,
   "high": 0.0083,
   "recommended": 0.008
  },
  "volume_variants_m3": {
   "bounding_box": 0.0080874,
   "convex_hull": 0.0079512,
   "mesh": 0.0080021
  }
 },
 "stats": {
  "backend": "pc",
  "duration_ms": 4210,
  "versions": {
   "server": "0.1.0",
   "python": "3.12.4",
   "open3d": "0.19.0",
   "numpy": "2.1.1"
  },
  "notes": [
   "mesh method: poisson, 52110 triangles, voxel 3 mm, poisson depth 10, measures basis: denoised_points, watertight: True"
  ],
  "quality": "FINE",
  "voxel_mm": 3.0,
  "poisson_depth": 10
 },
 "files": [
  "cloud_clean.ply",
  "mesh.obj",
  "mesh.ply"
 ]
}
""".trimIndent()

    private fun zipOf(json: String, vararg names: String): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("result.json")); z.write(json.toByteArray()); z.closeEntry()
            for (n in names) { z.putNextEntry(ZipEntry(n)); z.write(byteArrayOf(1, 2, 3)); z.closeEntry() }
        }
        return f
    }

    @Test fun readsTheServersObjectResultAndIgnoresExtraKeys() {
        val c = ResultPackage.read(zipOf(pcResultJson, "mesh.ply", "mesh.obj", "cloud_clean.ply"))
        val r = c.result
        assertEquals("object_mesh", r.jobType)
        assertEquals("pc", r.stats.backend)
        assertEquals(4210L, r.stats.durationMs)
        assertEquals("0.1.0", r.stats.versions["server"])
        assertEquals(3, r.measures.volumeVariantsM3.size)
        assertEquals(0.0079512, r.measures.volumeVariantsM3["convex_hull"]!!, 1e-9)
        assertEquals(0.20312, r.measures.objectDims!!.lengthM, 1e-9)
        assertEquals(0.008, r.measures.volumeM3!!.recommended, 1e-9)
        assertTrue(c.entries.containsAll(listOf("mesh.ply", "mesh.obj", "cloud_clean.ply")))
        assertEquals(listOf("cloud_clean.ply", "mesh.obj", "mesh.ply"), r.files)
    }

    @Test fun outcomeAndCardTextFromTheServerResult() {
        val r = ResultPackage.read(zipOf(pcResultJson)).result
        val o = ObjectOutcome.from(r)!!
        assertEquals(0.2003, o.heightM, 1e-9)
        assertEquals("pc", o.backend)
        val lines = ObjectCardText.lines(Units.M, o).toMap()
        assertEquals("0.0080 m³ (0.0078-0.0083)", lines["Volume"])
        assertEquals("0.20 m", lines["Height"])
        assertEquals("0.2 x 0.2 m", lines["Footprint"])
    }

    @Test fun toleratesNullQualityAndMissingOptionalBlocks() {
        val json = """{"schema":1,"job_type":"scan_analyze","measures":{"area_m2":{"low":11.5,"high":12.5,"recommended":12.0}},
            "stats":{"backend":"pc","duration_ms":10,"quality":null,"extra":{"a":[1,2]}},"files":[],"unknown_top":true}"""
        val r = ResultPackage.read(zipOf(json)).result
        assertEquals(12.0, r.measures.areaM2!!.recommended, 0.0)
        assertNull(ObjectOutcome.from(r))
    }

    @Test fun volumeLineDropsAnEmptyRangeAndUsesImperialUnits() {
        assertEquals("0.0080 m³", ObjectCardText.volume(Units.M, Estimate.exact(0.008)))
        // 0.008 m3 = 0.28252 ft3, three decimals from 0.1 ft3 up
        assertEquals("0.283 ft³", ObjectCardText.volume(Units.FT, Estimate.exact(0.008)))
        assertEquals("1.250 m³ (1.000-1.500)", ObjectCardText.volume(Units.M, Estimate(1.0, 1.5, 1.25)))
        assertEquals("12.50 m³", ObjectCardText.volume(Units.M, Estimate.exact(12.5)))
    }

    @Test fun stageTextAndPickerRows() {
        assertEquals("Building the mesh", ProcessingUiText.stageLabel("poisson"))
        assertEquals("Some new stage", ProcessingUiText.stageLabel("some_new_stage"))
        val ui = ProcessingUiText.of(ProcessingState.Routed(RouteDecision(Backend.PC, "PC: big job")))!!
        assertTrue(ui.onPc)
        val run = ProcessingUiText.of(ProcessingState.Running(0.5f, "poisson"), ui)!!
        assertEquals("Building the mesh, 50 %", run.text)
        assertEquals(0.5f, run.fraction!!, 0f)
        val phone = ProcessingUiText.of(ProcessingState.Running(0f, "analysing on the phone"), ProcessingUi("Phone"))!!
        assertNull(phone.fraction)

        val rows = PickerRows.forObject(Router.availableQualities(Tier.MID, pcAvailable = false))
        assertEquals(listOf("QUICK", "FINE", "DETAILED", "DETAILED_SPLAT"), rows.map { it.id })
        assertTrue(rows[0].enabled && rows[1].enabled)
        assertTrue(!rows[2].enabled && rows[2].reason == "needs your PC")
        assertTrue(!rows[3].enabled)
        // photo capture does not exist yet: DETAILED stays grey even with a PC
        val optsPc = Router.availableQualities(Tier.HIGH, pcAvailable = true)
        val withPc = PickerRows.forObject(optsPc)
        assertTrue(!withPc[2].enabled && withPc[2].reason == PickerRows.PHOTO_REASON)
        assertEquals("FINE", PickerRows.defaultId(withPc, optsPc))
    }
}
