package com.example.arruler.store

import com.example.arruler.measure.Units
import com.example.arruler.measure.shapes.ShapeFormat
import com.example.arruler.objscan.TexturedObject
import com.example.arruler.objscan.TriMesh
import com.example.arruler.plan.FloorPlan
import com.example.arruler.plan.toDxf
import com.example.arruler.plan.toSvg
import com.example.arruler.processing.ObjectCardText
import com.example.arruler.scan3d.ObjectSummary
import com.example.arruler.scan3d.ScanFiles
import com.example.arruler.scan3d.ScanSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** File and folder naming of the public export (Download/ARMeasure/<project>/): pure, JVM-tested. */
object ExportNames {
    const val ROOT = "ARMeasure"
    const val DEFAULT_PROJECT = "My place"
    private const val MAX_LEN = 80
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** A name safe on every file system and still readable: illegal characters dropped, spaces collapsed, no leading / trailing dot or space. */
    fun sanitize(name: String, fallback: String = "Untitled"): String {
        val cleaned = name.replace(ILLEGAL, " ").replace(Regex("\\s+"), " ").trim().trim('.').trim()
        val cut = if (cleaned.length > MAX_LEN) cleaned.substring(0, MAX_LEN).trim().trim('.') else cleaned
        return cut.ifEmpty { fallback }
    }

    /** '2026-10-03 14-05' (no colon: file names cannot hold one). */
    fun stamp(timeMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT)
        f.timeZone = tz
        return f.format(Date(timeMs))
    }

    /** '<base> <stamp> <suffix>.<ext>', e.g. 'Living room 2026-10-03 14-05 plan.png'. */
    fun stamped(base: String, timeMs: Long, suffix: String, ext: String, tz: TimeZone = TimeZone.getDefault()): String =
        "${sanitize(base)} ${stamp(timeMs, tz)} $suffix.$ext"

    /** '<base> <suffix>.<ext>', e.g. 'Chair mesh.obj'. */
    fun plain(base: String, suffix: String, ext: String): String = "${sanitize(base)} $suffix.$ext"

    private fun projectDir(project: String) = sanitize(project, DEFAULT_PROJECT)

    /** MediaStore RELATIVE_PATH: 'Download/ARMeasure/<project>/'. */
    fun relativePath(project: String): String = "Download/$ROOT/${projectDir(project)}/"

    /** The folder as the user reads it: 'Download/ARMeasure/<project>'. */
    fun folderLabel(project: String): String = "Download/$ROOT/${projectDir(project)}"

    /** Document id of the folder in the external storage provider (for DocumentsContract). */
    fun folderDocumentId(project: String): String = "primary:" + folderLabel(project)

    fun savedMessage(project: String): String = "Saved to ${folderLabel(project)}"
}

/** One file of an export, by name and type. */
data class ExportFileSpec(val name: String, val mime: String)

/** Which files an export writes; kept apart so the layout can be checked without writing anything. */
object ExportLayout {
    const val MIME_PNG = "image/png"
    const val MIME_SVG = "image/svg+xml"
    const val MIME_DXF = "application/dxf"
    const val MIME_JSON = "application/json"
    const val MIME_TEXT = "text/plain"
    const val MIME_PLY = "application/octet-stream"
    const val MIME_MP4 = "video/mp4"

    fun plan(base: String, timeMs: Long, tz: TimeZone = TimeZone.getDefault()): List<ExportFileSpec> = listOf(
        ExportFileSpec(ExportNames.stamped(base, timeMs, "plan", "png", tz), MIME_PNG),
        ExportFileSpec(ExportNames.stamped(base, timeMs, "plan", "svg", tz), MIME_SVG),
        ExportFileSpec(ExportNames.stamped(base, timeMs, "plan", "dxf", tz), MIME_DXF),
        ExportFileSpec(ExportNames.stamped(base, timeMs, "plan", "json", tz), MIME_JSON),
    )

    fun objectFiles(name: String, textured: Boolean, video: Boolean): List<ExportFileSpec> = buildList {
        add(ExportFileSpec(ExportNames.plain(name, "mesh", "obj"), MIME_TEXT))
        if (textured) {
            add(ExportFileSpec(ExportNames.plain(name, "mesh", "mtl"), MIME_TEXT))
            add(ExportFileSpec(ExportNames.plain(name, "texture", "png"), MIME_PNG))
        }
        add(ExportFileSpec(ExportNames.plain(name, "mesh", "ply"), MIME_PLY))
        add(ExportFileSpec(ExportNames.plain(name, "measurements", "json"), MIME_JSON))
        add(ExportFileSpec(ExportNames.plain(name, "measurements", "txt"), MIME_TEXT))
        if (video) add(ExportFileSpec(ExportNames.plain(name, "capture", "mp4"), MIME_MP4))
    }

    fun scanFiles(name: String, timeMs: Long, hasMesh: Boolean, tz: TimeZone = TimeZone.getDefault()): List<ExportFileSpec> = listOf(
        ExportFileSpec(ExportNames.stamped(name, timeMs, "scan", "ply", tz), MIME_PLY),
        ExportFileSpec(ExportNames.stamped(name, timeMs, if (hasMesh) "mesh" else "surfaces", "obj", tz), MIME_TEXT),
    )
}

/** What an object export writes into measurements.json / .txt. Lengths in meters, volumes in m^3. */
data class ObjectExportInfo(
    val name: String,
    val createdAt: Long,
    val projectName: String,
    val summary: ObjectSummary,
    val method: String? = null,
    val notes: String? = null,
    /** Extra lines such as the primitive fit: 'Shape' to 'cylinder, r 9.8 cm, h 20.1 cm'. */
    val extras: List<Pair<String, String>> = emptyList(),
)

@Serializable
private data class MeasurementsDto(
    val name: String,
    val project: String,
    val created: String,
    val footprintLengthM: Float,
    val footprintWidthM: Float,
    val heightM: Float,
    val volumeRecommendedM3: Float,
    val volumeLowM3: Float,
    val volumeHighM3: Float,
    val method: String? = null,
    val notes: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val units: String = "meters, cubic meters",
)

/** The numbers of an object as text files. */
object MeasurementsText {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private fun n1(v: Float) = String.format(Locale.US, "%.1f", v)

    /** measurements.txt: the numbers in the app's current [units]. */
    fun txt(info: ObjectExportInfo, units: Units, tz: TimeZone = TimeZone.getDefault()): String {
        val s = info.summary
        val rec = units.volumeFromCubicMeters(s.volumeM3)
        val lo = units.volumeFromCubicMeters(s.volumeLowM3)
        val hi = units.volumeFromCubicMeters(s.volumeHighM3)
        val vol = ObjectCardText.volumeNumber(rec) + " " + units.volumeSymbol +
            if (ObjectCardText.volumeNumber(lo) == ObjectCardText.volumeNumber(hi)) "" else
                " (range ${ObjectCardText.volumeNumber(lo)} to ${ObjectCardText.volumeNumber(hi)} ${units.volumeSymbol})"
        val sb = StringBuilder()
        sb.append("Name: ").append(info.name).append('\n')
        sb.append("Project: ").append(info.projectName).append('\n')
        sb.append("Date: ").append(ExportNames.stamp(info.createdAt, tz)).append('\n')
        sb.append("Footprint: ").append(n1(units.fromMeters(s.lengthM))).append(" x ").append(n1(units.fromMeters(s.widthM)))
            .append(' ').append(units.symbol).append('\n')
        sb.append("Height: ").append(ShapeFormat.length(units, s.heightM)).append('\n')
        sb.append("Volume: ").append(vol).append('\n')
        info.method?.let { sb.append("Method: ").append(it).append('\n') }
        for ((k, v) in info.extras) sb.append(k).append(": ").append(v).append('\n')
        info.notes?.takeIf { it.isNotBlank() }?.let { sb.append("Notes: ").append(it).append('\n') }
        return sb.toString()
    }

    /** measurements.json: always meters and cubic meters, for tools. */
    fun json(info: ObjectExportInfo, tz: TimeZone = TimeZone.getDefault()): String {
        val s = info.summary
        return json.encodeToString(
            MeasurementsDto.serializer(),
            MeasurementsDto(
                info.name, info.projectName, ExportNames.stamp(info.createdAt, tz),
                s.lengthM, s.widthM, s.heightM, s.volumeM3, s.volumeLowM3, s.volumeHighM3,
                info.method, info.notes, info.extras.toMap(),
            ),
        )
    }
}

/** A readable stream of an export file (a file of tens of MB must not be loaded whole). */
fun interface ContentSource {
    fun open(): InputStream

    companion object {
        fun of(bytes: ByteArray) = ContentSource { ByteArrayInputStream(bytes) }
        fun of(text: String) = of(text.toByteArray(Charsets.UTF_8))
        fun of(file: File) = ContentSource { FileInputStream(file) }
    }
}

/** Writes one file into the public Downloads collection. Returns a handle for sharing (a content Uri string, or a file path on API 24-28). */
interface PublicSink {
    fun write(relativeDir: String, name: String, mime: String, content: ContentSource): String?
}

class PublicFile(val name: String, val mime: String, val handle: String?)

/** The outcome of an export: where it went (the folder as the user reads it) and each file. */
class ExportResult(val project: String, val folder: String, val files: List<PublicFile>)

/**
 * Orchestrates the public export; storage goes through [sink] so the layout and the file contents are testable
 * on the JVM. [pngOf] renders a plan to PNG bytes (Android bitmap code in the app, a stub in tests).
 */
class PublicExporter(
    private val sink: PublicSink,
    private val pngOf: (FloorPlan, Units) -> ByteArray,
    private val now: () -> Long = System::currentTimeMillis,
    private val tz: TimeZone = TimeZone.getDefault(),
) {
    /** One room as a plan: '<room> <stamp> plan.png / .svg / .dxf / .json'. */
    fun exportRoom(project: Project, room: SavedRoom, units: Units): ExportResult =
        exportPlanOf(project.name, project.copy(rooms = listOf(room)), room.name, units)

    /** The whole project plan: '<project> <stamp> plan.*'. */
    fun exportPlan(project: Project, units: Units): ExportResult = exportPlanOf(project.name, project, project.name, units)

    private fun exportPlanOf(projectName: String, project: Project, base: String, units: Units): ExportResult {
        val t = now()
        val specs = ExportLayout.plan(base, t, tz)
        val plan = project.toFloorPlan()
        val contents = listOf(
            ContentSource.of(pngOf(plan, units)),
            ContentSource.of(toSvg(plan, units, false)),
            ContentSource.of(toDxf(plan)),
            ContentSource.of(ProjectCodec.encode(project)),
        )
        return write(projectName, specs, contents)
    }

    /** A room scan: the cloud as PLY and the surfaces (or mesh) as OBJ. */
    fun exportScan(projectName: String, snap: ScanSnapshot, name: String): ExportResult {
        val specs = ExportLayout.scanFiles(name, snap.createdAt, snap.mesh != null, tz)
        return write(projectName, specs, listOf(ContentSource.of(ScanFiles.plyBytes(snap)), ContentSource.of(ScanFiles.objText(snap))))
    }

    /**
     * An object: mesh OBJ (+ MTL + texture PNG when [textured]), PLY, measurements JSON and TXT (in [units]),
     * and the capture video [video] when the file exists.
     */
    fun exportObject(
        info: ObjectExportInfo, mesh: TriMesh, textured: TexturedObject?, units: Units, video: File?,
    ): ExportResult {
        val hasVideo = video != null && video.isFile
        val specs = ExportLayout.objectFiles(info.name, textured != null, hasVideo)
        val byName = HashMap<String, ContentSource>()
        val objName = ExportNames.plain(info.name, "mesh", "obj")
        val mtlName = ExportNames.plain(info.name, "mesh", "mtl")
        val pngName = ExportNames.plain(info.name, "texture", "png")
        if (textured != null) {
            byName[objName] = ContentSource.of(textured.obj.replace(TexturedObject.OBJ_MTL_REF, "mtllib $mtlName"))
            byName[mtlName] = ContentSource.of(textured.mtl.replace(TexturedObject.MTL_PNG_REF, "map_Kd $pngName"))
            byName[pngName] = ContentSource.of(textured.png)
        } else {
            byName[objName] = ContentSource.of(mesh.toObj(ExportNames.sanitize(info.name, "object")))
        }
        byName[ExportNames.plain(info.name, "mesh", "ply")] = ContentSource.of(mesh.toBinaryPly())
        byName[ExportNames.plain(info.name, "measurements", "json")] = ContentSource.of(MeasurementsText.json(info, tz))
        byName[ExportNames.plain(info.name, "measurements", "txt")] = ContentSource.of(MeasurementsText.txt(info, units, tz))
        if (hasVideo) byName[ExportNames.plain(info.name, "capture", "mp4")] = ContentSource.of(video)
        return write(info.projectName, specs, specs.map { byName.getValue(it.name) })
    }

    private fun write(project: String, specs: List<ExportFileSpec>, contents: List<ContentSource>): ExportResult {
        val dir = ExportNames.relativePath(project)
        val files = specs.indices.map { i ->
            PublicFile(specs[i].name, specs[i].mime, sink.write(dir, specs[i].name, specs[i].mime, contents[i]))
        }
        return ExportResult(project, ExportNames.folderLabel(project), files)
    }
}
