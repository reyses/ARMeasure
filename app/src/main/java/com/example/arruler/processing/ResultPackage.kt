package com.example.arruler.processing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A measured quantity with its uncertainty band; all in SI units (m, m2, m3). */
@Serializable
data class Estimate(val low: Double, val high: Double, val recommended: Double) {
    companion object {
        fun exact(v: Double) = Estimate(v, v, v)
    }
}

@Serializable
data class ObjectDims(
    @SerialName("length_m") val lengthM: Double,
    @SerialName("width_m") val widthM: Double,
    @SerialName("height_m") val heightM: Double
)

@Serializable
data class Measures(
    @SerialName("area_m2") val areaM2: Estimate? = null,
    @SerialName("perimeter_m") val perimeterM: Estimate? = null,
    @SerialName("height_m") val heightM: Estimate? = null,
    @SerialName("volume_m3") val volumeM3: Estimate? = null,
    /** Named volume variants in m3, e.g. "bounding_box", "convex_hull", "mesh". */
    @SerialName("volume_variants_m3") val volumeVariantsM3: Map<String, Double> = emptyMap(),
    @SerialName("wall_count") val wallCount: Int? = null,
    @SerialName("object_dims") val objectDims: ObjectDims? = null
)

@Serializable
data class ProcessingStats(
    /** "phone" or "pc". */
    val backend: String,
    @SerialName("duration_ms") val durationMs: Long,
    val versions: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList()
)

@Serializable
data class ResultJson(
    val schema: Int = ResultPackage.SCHEMA,
    @SerialName("job_type") val jobType: String,
    val measures: Measures,
    val stats: ProcessingStats,
    val files: List<String> = emptyList()
)

@Serializable
data class PlaneDto(
    val kind: String,
    val normal: List<Float>,
    val d: Float,
    val centroid: List<Float>,
    val inliers: Int,
    @SerialName("outline_3d") val outline3d: List<List<Float>> = emptyList()
)

@Serializable
data class PlanesFile(val schema: Int = ResultPackage.SCHEMA, val planes: List<PlaneDto>)

class ResultContents(val result: ResultJson, val entries: Set<String>)

/** Result ZIP: result.json plus optional mesh.ply, mesh.obj, texture.png, planes.json, cloud_clean.ply. */
object ResultPackage {
    const val SCHEMA = 1
    const val RESULT = "result.json"
    val OPTIONAL = listOf("mesh.ply", "mesh.obj", "texture.png", "planes.json", "cloud_clean.ply")

    /** Writes result.json (its `files` list is filled from [extra]) and the optional files. */
    fun write(dest: File, result: ResultJson, extra: Map<String, ByteArray> = emptyMap()) {
        require(extra.keys.all { it in OPTIONAL }) { "unknown result file ${extra.keys - OPTIONAL.toSet()}" }
        val r = result.copy(files = extra.keys.sorted())
        ZipOutputStream(dest.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry(RESULT))
            z.write(ProcJson.json.encodeToString(r).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            for ((name, bytes) in extra) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
    }

    fun read(file: File): ResultContents = ZipFile(file).use { zf ->
        val e = zf.getEntry(RESULT) ?: throw PackageFormatException("result.json missing")
        val r = try {
            ProcJson.json.decodeFromString<ResultJson>(zf.getInputStream(e).readBytes().toString(Charsets.UTF_8))
        } catch (ex: Exception) {
            throw PackageFormatException("result.json invalid: ${ex.message}")
        }
        if (r.schema > SCHEMA) throw PackageFormatException("unsupported schema ${r.schema}")
        val names = zf.entries().asSequence().map { it.name }.toSet()
        ResultContents(r, names)
    }

    /** Reads one optional entry fully (meshes can be large; prefer [extract] for those). */
    fun readEntry(file: File, name: String): ByteArray? = ZipFile(file).use { zf ->
        zf.getEntry(name)?.let { zf.getInputStream(it).readBytes() }
    }

    fun readPlanes(file: File): PlanesFile? = readEntry(file, "planes.json")?.let {
        ProcJson.json.decodeFromString<PlanesFile>(it.toString(Charsets.UTF_8))
    }

    /** Extracts every entry into [dir] (zip-slip safe) and returns name -> file. */
    fun extract(file: File, dir: File): Map<String, File> {
        dir.mkdirs()
        val root = dir.canonicalFile
        val out = LinkedHashMap<String, File>()
        ZipFile(file).use { zf ->
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                val target = File(root, e.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw PackageFormatException("illegal entry ${e.name}")
                target.parentFile?.mkdirs()
                zf.getInputStream(e).use { i -> target.outputStream().use { o -> i.copyTo(o) } }
                out[e.name] = target
            }
        }
        return out
    }
}
