package com.example.arruler.store

import com.example.arruler.measure.Units
import com.example.arruler.objscan.TexturedObject
import com.example.arruler.objscan.TriMesh
import com.example.arruler.scan3d.ObjectSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

class PublicExportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")
    private val t0 = 1_790_000_000_000L // 2026-09-21 ... fixed instant

    /** Remembers every write by directory and name. */
    private class MemorySink : PublicSink {
        val files = LinkedHashMap<String, ByteArray>()
        val dirs = LinkedHashSet<String>()
        override fun write(relativeDir: String, name: String, mime: String, content: ContentSource): String? {
            dirs += relativeDir
            files[name] = content.open().use { it.readBytes() }
            return "mem:$relativeDir$name"
        }
        fun text(name: String) = String(files.getValue(name), Charsets.UTF_8)
    }

    private fun room() = SavedRoom(
        "r1", "Living room", listOf(PlanPoint(0f, 0f), PlanPoint(4f, 0f), PlanPoint(4f, 3f), PlanPoint(0f, 3f)),
        heightM = 2.5f, areaM2 = 12f, perimeterM = 14f, volumeM3 = 30f, capturedAt = 5L,
    )

    private fun project() = Project("p1", "Flat: 2/3", 1L, 2L, listOf(room(), room().copy(id = "r2", name = "Kitchen")))

    private fun mesh() = TriMesh.withNormals(
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        intArrayOf(0, 1, 2, 0, 3, 1, 0, 2, 3, 1, 3, 2),
    )

    private val info = ObjectExportInfo(
        "Chair: red", t0, "Flat", ObjectSummary(0.5f, 0.4f, 0.9f, 0.08f, 0.1f, 0.09f), "phone, 3.2 s", "left side worn",
        listOf("Shape" to "cylinder, r 9.8 cm"),
    )

    private fun exporter(sink: MemorySink) = PublicExporter(sink, { _, _ -> byteArrayOf(1, 2, 3) }, { t0 }, utc)

    // ---- names ----

    @Test fun sanitizeKeepsNamesReadable() {
        assertEquals("Living room", ExportNames.sanitize("  Living   room "))
        assertEquals("a b c d e f g h", ExportNames.sanitize("a/b\\c:d*e?f\"g<h>"))
        assertEquals("Untitled", ExportNames.sanitize("..."))
        assertEquals("My place", ExportNames.sanitize("   ", "My place"))
        assertEquals("x", ExportNames.sanitize(".x."))
        assertEquals(80, ExportNames.sanitize("a".repeat(200)).length)
        assertFalse(ExportNames.sanitize("tab\there").contains('\t'))
    }

    @Test fun stampHasNoColon() {
        val ms = java.time.ZonedDateTime.of(2026, 10, 3, 14, 5, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("2026-10-03 14-05", ExportNames.stamp(ms, utc))
        assertEquals("Living room 2026-10-03 14-05 plan.png", ExportNames.stamped("Living room", ms, "plan", "png", utc))
    }

    @Test fun planNamesFollowTheOwnersExample() {
        val n = ExportNames.stamped("Living room", t0, "plan", "png", utc)
        assertEquals("Living room ${ExportNames.stamp(t0, utc)} plan.png", n)
        assertEquals("Chair mesh.obj", ExportNames.plain("Chair", "mesh", "obj"))
    }

    @Test fun folderLayoutIsDownloadArMeasureProject() {
        assertEquals("Download/ARMeasure/Flat 2 3/", ExportNames.relativePath("Flat: 2/3"))
        assertEquals("Download/ARMeasure/Flat 2 3", ExportNames.folderLabel("Flat: 2/3"))
        assertEquals("primary:Download/ARMeasure/Flat", ExportNames.folderDocumentId("Flat"))
        assertEquals("Saved to Download/ARMeasure/Flat", ExportNames.savedMessage("Flat"))
        assertEquals("Download/ARMeasure/My place/", ExportNames.relativePath("  "))
    }

    @Test fun objectLayoutListsTextureAndVideoOnlyWhenPresent() {
        val plain = ExportLayout.objectFiles("Chair", textured = false, video = false).map { it.name }
        assertEquals(listOf("Chair mesh.obj", "Chair mesh.ply", "Chair measurements.json", "Chair measurements.txt"), plain)
        val full = ExportLayout.objectFiles("Chair", textured = true, video = true).map { it.name }
        assertEquals(
            listOf(
                "Chair mesh.obj", "Chair mesh.mtl", "Chair texture.png", "Chair mesh.ply",
                "Chair measurements.json", "Chair measurements.txt", "Chair capture.mp4",
            ),
            full,
        )
    }

    // ---- exporter ----

    @Test fun roomExportWritesFourPlanFilesIntoTheProjectFolder() {
        val sink = MemorySink()
        val r = exporter(sink).exportRoom(project(), room(), Units.CM)
        val stamp = ExportNames.stamp(t0, utc)
        assertEquals(
            listOf("Living room $stamp plan.png", "Living room $stamp plan.svg", "Living room $stamp plan.dxf", "Living room $stamp plan.json"),
            sink.files.keys.toList(),
        )
        assertEquals(setOf("Download/ARMeasure/Flat 2 3/"), sink.dirs)
        assertEquals("Download/ARMeasure/Flat 2 3", r.folder)
        assertEquals(4, r.files.size)
        assertTrue(sink.text("Living room $stamp plan.svg").contains("<svg"))
        assertTrue(sink.text("Living room $stamp plan.dxf").contains("LINE"))
        // only the one room is in the room's JSON
        val json = sink.text("Living room $stamp plan.json")
        assertTrue(json.contains("Living room")); assertFalse(json.contains("Kitchen"))
        assertEquals(listOf<Byte>(1, 2, 3), sink.files.getValue("Living room $stamp plan.png").toList())
    }

    @Test fun projectExportCarriesEveryRoom() {
        val sink = MemorySink()
        exporter(sink).exportPlan(project(), Units.M)
        val json = sink.text("Flat 2 3 ${ExportNames.stamp(t0, utc)} plan.json")
        assertTrue(json.contains("Living room") && json.contains("Kitchen"))
    }

    @Test fun greyObjectExportWritesMeshPlyAndMeasurements() {
        val sink = MemorySink()
        val r = exporter(sink).exportObject(info, mesh(), null, Units.CM, null)
        assertEquals(
            listOf("Chair red mesh.obj", "Chair red mesh.ply", "Chair red measurements.json", "Chair red measurements.txt"),
            sink.files.keys.toList(),
        )
        assertEquals("Download/ARMeasure/Flat/", sink.dirs.single())
        assertTrue(sink.text("Chair red mesh.obj").contains("\nv "))
        assertTrue(sink.files.getValue("Chair red mesh.ply").take(3).map { it.toInt().toChar() }.joinToString("") == "ply")
        assertEquals(4, r.files.size)
        assertTrue(r.files.all { it.handle != null })
    }

    @Test fun measurementsTextIsInTheCurrentUnits() {
        val cm = MeasurementsText.txt(info, Units.CM, utc)
        assertTrue(cm, cm.contains("Footprint: 50.0 x 40.0 cm"))
        assertTrue(cm.contains("Height: 90.00 cm"))
        assertTrue(cm.contains("Volume: 0.0900 m³ (range 0.0800 to 0.100 m³)"))
        assertTrue(cm.contains("Shape: cylinder, r 9.8 cm"))
        assertTrue(cm.contains("Notes: left side worn"))
        val inch = MeasurementsText.txt(info, Units.INCH, utc)
        assertTrue(inch, inch.contains("Footprint: 19.7 x 15.7 in"))
        assertTrue(inch.contains("ft³"))
    }

    @Test fun measurementsJsonIsAlwaysMeters() {
        val j = MeasurementsText.json(info, utc)
        assertTrue(j, j.contains("\"footprintLengthM\": 0.5"))
        assertTrue(j.contains("\"heightM\": 0.9"))
        assertTrue(j.contains("\"volumeRecommendedM3\": 0.09"))
        assertTrue(j.contains("\"Shape\": \"cylinder, r 9.8 cm\""))
    }

    @Test fun texturedExportRewritesTheReferencesToTheWrittenNames() {
        val sink = MemorySink()
        val t = TexturedObject("mtllib mesh.mtl\nv 0 0 0\n", "newmtl m\nmap_Kd texture.png\n", byteArrayOf(9, 9))
        exporter(sink).exportObject(info, mesh(), t, Units.CM, null)
        assertTrue(sink.text("Chair red mesh.obj").startsWith("mtllib Chair red mesh.mtl"))
        assertTrue(sink.text("Chair red mesh.mtl").contains("map_Kd Chair red texture.png"))
        assertEquals(listOf<Byte>(9, 9), sink.files.getValue("Chair red texture.png").toList())
        assertTrue(sink.files.containsKey("Chair red mesh.ply"))
    }

    @Test fun vertexColourOnlyResultKeepsTheGreyObjAndAddsTheColourPly() {
        val sink = MemorySink()
        val t = TexturedObject("", "", ByteArray(0), null, byteArrayOf(5, 6, 7))
        assertFalse(t.hasAtlas)
        exporter(sink).exportObject(info, mesh(), t, Units.CM, null)
        assertEquals(
            listOf("Chair red mesh.obj", "Chair red mesh.ply", "Chair red mesh colour.ply", "Chair red measurements.json", "Chair red measurements.txt"),
            sink.files.keys.toList(),
        )
        assertTrue(sink.text("Chair red mesh.obj").contains("\nv "))      // grey OBJ, no mtllib
        assertFalse(sink.text("Chair red mesh.obj").contains("mtllib"))
        assertEquals(listOf<Byte>(5, 6, 7), sink.files.getValue("Chair red mesh colour.ply").toList())
    }

    @Test fun atlasResultWithColoursWritesObjMtlPngAndBothPlys() {
        val sink = MemorySink()
        val t = TexturedObject("mtllib mesh.mtl\nv 0 0 0\n", "newmtl m\nmap_Kd texture.png\n", byteArrayOf(9, 9), null, byteArrayOf(1))
        exporter(sink).exportObject(info, mesh(), t, Units.CM, null)
        assertTrue(sink.files.keys.containsAll(listOf("Chair red mesh.obj", "Chair red mesh.mtl", "Chair red texture.png", "Chair red mesh.ply", "Chair red mesh colour.ply")))
    }

    @Test fun shapeLineReachesBothMeasurementFiles() {
        val sink = MemorySink()
        exporter(sink).exportObject(info, mesh(), null, Units.CM, null)
        assertTrue(sink.text("Chair red measurements.txt").contains("Shape: cylinder, r 9.8 cm"))
        assertTrue(sink.text("Chair red measurements.json").contains("\"Shape\": \"cylinder, r 9.8 cm\""))
    }

    @Test fun captureVideoIsCopiedWhenTheFileExists() {
        val video = tmp.newFile("rec.mp4").apply { writeBytes(ByteArray(1000) { 7 }) }
        val sink = MemorySink()
        exporter(sink).exportObject(info, mesh(), null, Units.CM, video)
        assertEquals(1000, sink.files.getValue("Chair red capture.mp4").size)
        val none = MemorySink()
        exporter(none).exportObject(info, mesh(), null, Units.CM, java.io.File(tmp.root, "missing.mp4"))
        assertFalse(none.files.containsKey("Chair red capture.mp4"))
        assertNull(none.files["Chair red capture.mp4"])
    }
}
