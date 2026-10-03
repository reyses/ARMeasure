package com.example.arruler.processing

import com.example.arruler.measure.Units
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PcPhotoResultTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun parse(json: String) = ProcJson.json.decodeFromString<ResultJson>(json)

    // what pc-server's photogrammetry.run returns today (stats.images, stats.sparse, no walk/fused keys)
    private val today = """
        {"schema":1,"job_type":"photogrammetry",
         "measures":{"object_dims":{"length_m":0.2,"width_m":0.1,"height_m":0.05},"volume_m3":{"low":0.0009,"high":0.0011,"recommended":0.001}},
         "stats":{"backend":"pc","duration_ms":252000,"images":64,
                  "sparse":{"registered_images":58,"points":9000,"mean_reprojection_error_px":0.42},
                  "versions":{"server":"0.1.0"},"notes":["64 images, one camera per image"]},
         "files":["mesh.obj","texture.png"]}
    """.trimIndent()

    @Test fun theCurrentServerKeysGiveTheStatsLine() {
        val o = ObjectOutcome.from(parse(today))!!
        assertEquals(64, o.images); assertEquals(58, o.registered); assertEquals(0.42, o.reprojectionPx!!, 1e-9)
        assertEquals("58 of 64 photos registered, 0.42 px mean reprojection error", ObjectCardText.photoLine(o))
        assertEquals("pc, 58 of 64 photos registered, 4 min 12 s", ObjectCardText.method(o))
        val lines = ObjectCardText.lines(Units.CM, o).toMap()
        assertEquals("pc, 4 min 12 s", lines["Computed"])
        assertEquals("58 of 64 photos registered, 0.42 px mean reprojection error", lines["PC photos"])
    }

    @Test fun aPhoneResultHasNoPhotoLine() {
        val o = ObjectOutcome(0.2, 0.1, 0.05, Estimate.exact(0.001), emptyMap(), "phone", 8400, emptyList())
        assertNull(ObjectCardText.photoLine(o))
        assertEquals("phone, 8.4 s", ObjectCardText.method(o))
        assertTrue(ObjectCardText.lines(Units.CM, o).none { it.first == "PC photos" })
    }

    @Test fun partialStatsStillRead() {
        val o = ObjectOutcome(0.2, 0.1, 0.05, Estimate.exact(0.001), emptyMap(), "pc", 5000, emptyList(), images = 30)
        assertEquals("30 photos sent", ObjectCardText.photoLine(o))
        val r = ObjectOutcome(0.2, 0.1, 0.05, Estimate.exact(0.001), emptyMap(), "pc", 5000, emptyList(), registered = 12)
        assertEquals("12 photos registered", ObjectCardText.photoLine(r))
    }

    @Test fun noWalkOrFusedKeysGiveNoLines() {
        assertTrue(ObjectCardText.fusionLines(Units.CM, parse(today)).isEmpty())
    }

    @Test fun walkOnlyAndFusedShowAsTwoLines() {
        val json = today.replace(
            "\"volume_m3\":{\"low\":0.0009,\"high\":0.0011,\"recommended\":0.001}}",
            "\"volume_m3\":{\"low\":0.0009,\"high\":0.0011,\"recommended\":0.001}," +
                "\"walk_only\":{\"object_dims\":{\"length_m\":0.204,\"width_m\":0.103,\"height_m\":0.052},\"volume_m3\":{\"low\":0.001,\"high\":0.0012,\"recommended\":0.0011}}," +
                "\"fused\":{\"object_dims\":{\"length_m\":0.2,\"width_m\":0.1,\"height_m\":0.05},\"volume_m3\":{\"low\":0.0009,\"high\":0.0011,\"recommended\":0.001}}}",
        )
        val lines = ObjectCardText.fusionLines(Units.CM, parse(json))
        assertEquals(listOf("Walk only", "Fused (walk + spin)"), lines.map { it.first })
        assertTrue(lines[0].second, lines[0].second.startsWith("20.4 x 10.3 x 5.2 cm, 0.0011 m³"))
        assertTrue(lines[1].second, lines[1].second.startsWith("20.0 x 10.0 x 5.0 cm, 0.0010 m³"))
    }

    @Test fun topLevelKeysAndASingleHalfAreHandled() {
        val json = today.replace(
            "\"files\":[",
            "\"fused\":{\"object_dims\":{\"length_m\":0.2,\"width_m\":0.1,\"height_m\":0.05}},\"files\":[",
        )
        val lines = ObjectCardText.fusionLines(Units.CM, parse(json))
        assertEquals(listOf("Fused (walk + spin)"), lines.map { it.first })
        assertEquals("20.0 x 10.0 x 5.0 cm", lines.single().second)
        // an empty object carries nothing to show
        assertTrue(ObjectCardText.fusionLines(Units.CM, parse(today.replace("\"files\":[", "\"walk_only\":{},\"files\":["))).isEmpty())
    }

    @Test fun aPrebuiltJobWritesItsOwnZip() {
        val job = ProcessingJob(
            type = JobType.PHOTOGRAMMETRY, quality = ObjectQuality.DETAILED, estimate = JobEstimate(imageCount = 3),
            workDir = tmp.root,
            objectBox = ObjectBox(Vec3(0f, 0f, 0f), 0f, 0.2f, 0.2f, 0.2f),
            prebuilt = { it.writeText("zip-bytes") },
        )
        val dest = File(tmp.root, "job.zip")
        DefaultJobPackager("1.0", { null }, { "2026-10-03T12:00:00Z" }).pack(job, dest)
        assertEquals("zip-bytes", dest.readText())
    }

    @Test fun aPhotoJobManifestCarriesTheBoxAndThePlane() {
        val img = tmp.newFile("a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val frames = (0 until 3).map { PhotoFrame(img, it.toLong(), FloatArray(16) { i -> if (i % 5 == 0) 1f else 0f }, 500f, 500f, 320f, 240f, 640, 480) }
        val box = ObjectBox(Vec3(0f, 0f, 0f), 0f, 0.2f, 0.2f, 0.2f)
        val plane = com.example.arruler.objscan.SupportPlane.horizontal(0f)
        val dest = File(tmp.root, "photo.zip")
        JobPackage.writePhotoJob(dest, frames, PackageMeta("1.0", "2026-10-03T12:00:00Z", null, ObjectQuality.DETAILED, box, plane))
        val m = JobPackage.readManifest(dest)
        assertNotNull(m.box); assertNotNull(m.supportPlane)
        assertEquals(3, m.imageCount)
    }
}
