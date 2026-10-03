package com.example.arruler.scan3d

import com.example.arruler.depth.PlaneKind
import com.example.arruler.geometry.ColorRamp
import com.example.arruler.objscan.TexturedObject
import com.example.arruler.store.writeAtomic
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

@Serializable
internal data class PlaneDto(
    val kind: String,
    val normal: List<Float>,
    val d: Float,
    /** Packed xyz world outline. */
    val outline: List<Float>,
    val inliers: Int,
)

@Serializable
internal data class RoomDto(
    val outlineXZ: List<Float>,
    val floorY: Float,
    val ceilingY: Float,
    val wallCount: Int,
)

@Serializable
internal data class ObjectSummaryDto(
    val lengthM: Float,
    val widthM: Float,
    val heightM: Float,
    val volumeLowM3: Float,
    val volumeHighM3: Float,
    val volumeM3: Float,
) {
    fun toSummary() = ObjectSummary(lengthM, widthM, heightM, volumeLowM3, volumeHighM3, volumeM3)
}

@Serializable
internal data class ScanMetaDto(
    val id: String,
    val projectId: String? = null,
    val createdAt: Long,
    val pointCount: Int,
    val planes: List<PlaneDto> = emptyList(),
    val room: RoomDto? = null,
    val schema: Int = 1,
    val kind: String = ScanSnapshot.KIND_ROOM,
    val objectSummary: ObjectSummaryDto? = null,
    val name: String? = null,
    val notes: String? = null,
    val method: String? = null,
    val extras: List<String> = emptyList(),
)

/** One saved scan as listed in the UI. */
data class ScanInfo(
    val id: String,
    val projectId: String?,
    val createdAt: Long,
    val pointCount: Int,
    val roomAreaM2: Float?,
    val kind: String = ScanSnapshot.KIND_ROOM,
    val objectVolumeM3: Float? = null,
    val name: String? = null,
    val summary: ObjectSummary? = null,
)

/**
 * Scans on disk, one directory each: root/<id>/cloud.ply + planes.json. Pass `File(filesDir, "scans")` as [root].
 *
 * cloud.ply is binary little-endian: per vertex float x, y, z; uchar red, green, blue (the quality ramp);
 * float quality - 19 bytes per point. planes.json carries the planes, the room and the metadata.
 * Both files are written atomically (temp file + rename); a scan whose planes.json is missing is not listed.
 */
object ScanFiles {
    const val PLY_NAME = "cloud.ply"
    const val JSON_NAME = "planes.json"
    const val OBJ_NAME = "surfaces.obj"
    const val MESH_NAME = "mesh.ply"
    const val BYTES_PER_POINT = 3 * 4 + 3 + 4

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    fun dir(root: File, id: String): File = File(root, safeId(id))

    private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "scan" }

    fun save(root: File, s: ScanSnapshot): File {
        val d = dir(root, s.id)
        if (!d.isDirectory && !d.mkdirs()) throw IOException("cannot create $d")
        writeAtomic(File(d, PLY_NAME), plyBytes(s))
        s.mesh?.let { writeAtomic(File(d, MESH_NAME), it.toBinaryPly()) }
        writeAtomic(File(d, JSON_NAME), json.encodeToString(ScanMetaDto.serializer(), meta(s)).toByteArray(Charsets.UTF_8))
        return d
    }

    fun load(root: File, id: String): ScanSnapshot? {
        val d = dir(root, id)
        return try {
            val m = json.decodeFromString(ScanMetaDto.serializer(), File(d, JSON_NAME).readText(Charsets.UTF_8))
            val (pts, q) = readPly(File(d, PLY_NAME).readBytes())
            ScanSnapshot(
                m.id, m.projectId, m.createdAt, pts, q,
                m.planes.map { SnapshotPlane(PlaneKind.valueOf(it.kind), it.normal[0], it.normal[1], it.normal[2], it.d, it.outline.toFloatArray(), it.inliers) },
                m.room?.let { SnapshotRoom(it.outlineXZ.toFloatArray(), it.floorY, it.ceilingY, it.wallCount) },
                File(d, MESH_NAME).takeIf { it.isFile }?.let { MeshIo.readPly(it.readBytes()) },
                m.kind, m.objectSummary?.toSummary(),
                m.name, m.notes, m.method, m.extras,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Saved scans, newest first; unreadable ones are skipped. */
    fun list(root: File, projectId: String? = null): List<ScanInfo> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            try {
                val m = json.decodeFromString(ScanMetaDto.serializer(), File(d, JSON_NAME).readText(Charsets.UTF_8))
                val area = m.room?.let { SnapshotRoom(it.outlineXZ.toFloatArray(), it.floorY, it.ceilingY, it.wallCount).areaM2 }
                ScanInfo(m.id, m.projectId, m.createdAt, m.pointCount, area, m.kind, m.objectSummary?.volumeM3, m.name, m.objectSummary?.toSummary())
            } catch (_: Exception) {
                null
            }
        }.filter { projectId == null || it.projectId == projectId }.sortedByDescending { it.createdAt }
    }

    fun delete(root: File, id: String): Boolean = dir(root, id).deleteRecursively()

    // ---- object extras: thumbnail, capture video, textured mesh, editable name / notes ----

    const val THUMB_NAME = "thumb.png"
    const val VIDEO_NAME = "capture.mp4"
    const val TEXTURED_DIR = "textured"

    fun thumbFile(root: File, id: String): File = File(dir(root, id), THUMB_NAME)

    fun videoFile(root: File, id: String): File = File(dir(root, id), VIDEO_NAME)

    /** The planes.json text of [s] (for exports). */
    fun metaJson(s: ScanSnapshot): String = json.encodeToString(ScanMetaDto.serializer(), meta(s))

    /** Writes the thumbnail PNG bytes of a saved scan. */
    fun saveThumbnail(root: File, id: String, png: ByteArray) {
        val d = dir(root, id)
        if (d.isDirectory) writeAtomic(File(d, THUMB_NAME), png)
    }

    /** Copies the capture video into the scan's directory (the source stays). */
    fun saveVideo(root: File, id: String, source: File): Boolean {
        val d = dir(root, id)
        if (!d.isDirectory || !source.isFile) return false
        source.copyTo(File(d, VIDEO_NAME), overwrite = true)
        return true
    }

    /** Stores a textured mesh next to the grey one: textured/mesh.obj, mesh.mtl, texture.png (see [TexturedObject]). */
    fun saveTextured(root: File, id: String, t: TexturedObject) {
        val d = File(dir(root, id), TEXTURED_DIR)
        if (!d.isDirectory && !d.mkdirs()) throw IOException("cannot create $d")
        writeAtomic(File(d, "mesh.obj"), t.obj.toByteArray(Charsets.UTF_8))
        writeAtomic(File(d, "mesh.mtl"), t.mtl.toByteArray(Charsets.UTF_8))
        writeAtomic(File(d, "texture.png"), t.png)
    }

    fun loadTextured(root: File, id: String): TexturedObject? {
        val d = File(dir(root, id), TEXTURED_DIR)
        val obj = File(d, "mesh.obj"); val mtl = File(d, "mesh.mtl"); val png = File(d, "texture.png")
        if (!obj.isFile || !mtl.isFile || !png.isFile) return null
        return try { TexturedObject(obj.readText(Charsets.UTF_8), mtl.readText(Charsets.UTF_8), png.readBytes()) } catch (_: Exception) { null }
    }

    /** Rewrites only the name / notes of a saved scan (planes.json); false when the scan is missing. */
    fun updateMeta(root: File, id: String, name: String? = null, notes: String? = null): Boolean {
        val f = File(dir(root, id), JSON_NAME)
        return try {
            val m = json.decodeFromString(ScanMetaDto.serializer(), f.readText(Charsets.UTF_8))
            val next = m.copy(name = name ?: m.name, notes = notes ?: m.notes)
            writeAtomic(f, json.encodeToString(ScanMetaDto.serializer(), next).toByteArray(Charsets.UTF_8))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun meta(s: ScanSnapshot) = ScanMetaDto(
        s.id, s.projectId, s.createdAt, s.pointCount,
        planes = s.planes.map { PlaneDto(it.kind.name, listOf(it.nx, it.ny, it.nz), it.d, it.outline.toList(), it.inlierCount) },
        room = s.room?.let { RoomDto(it.outlineXZ.toList(), it.floorY, it.ceilingY, it.wallCount) },
        kind = s.kind,
        objectSummary = s.objectSummary?.let { ObjectSummaryDto(it.lengthM, it.widthM, it.heightM, it.volumeLowM3, it.volumeHighM3, it.volumeM3) },
        name = s.name, notes = s.notes, method = s.method, extras = s.extras,
    )

    // ---- PLY ----

    internal fun plyHeader(n: Int): String = buildString {
        append("ply\n")
        append("format binary_little_endian 1.0\n")
        append("comment ARMeasure scan, meters, +Y up, colour = quality ramp (red weak, green good)\n")
        append("element vertex ").append(n).append('\n')
        append("property float x\nproperty float y\nproperty float z\n")
        append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
        append("property float quality\n")
        append("end_header\n")
    }

    fun plyBytes(s: ScanSnapshot): ByteArray {
        val header = plyHeader(s.pointCount).toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(header.size + s.pointCount * BYTES_PER_POINT).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(header)
        for (i in 0 until s.pointCount) {
            buf.putFloat(s.points[i * 3]).putFloat(s.points[i * 3 + 1]).putFloat(s.points[i * 3 + 2])
            val q = s.quality[i]
            buf.put(ColorRamp.r(q).toByte()).put(ColorRamp.g(q).toByte()).put(0.toByte())
            buf.putFloat(q)
        }
        return buf.array()
    }

    internal fun readPly(bytes: ByteArray): Pair<FloatArray, FloatArray> {
        val marker = "end_header\n".toByteArray(Charsets.US_ASCII)
        var end = -1
        outer@ for (i in 0..bytes.size - marker.size) {
            for (j in marker.indices) if (bytes[i + j] != marker[j]) continue@outer
            end = i + marker.size
            break
        }
        if (end < 0) throw IOException("not a PLY file")
        val header = String(bytes, 0, end, Charsets.US_ASCII)
        if (!header.contains("format binary_little_endian")) throw IOException("unsupported PLY format")
        val n = Regex("element vertex (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: throw IOException("no vertex count")
        if (bytes.size - end < n.toLong() * BYTES_PER_POINT) throw IOException("truncated PLY")
        val buf = ByteBuffer.wrap(bytes, end, bytes.size - end).order(ByteOrder.LITTLE_ENDIAN)
        val pts = FloatArray(n * 3)
        val q = FloatArray(n)
        for (i in 0 until n) {
            pts[i * 3] = buf.float; pts[i * 3 + 1] = buf.float; pts[i * 3 + 2] = buf.float
            buf.position(buf.position() + 3)
            q[i] = buf.float
        }
        return pts to q
    }

    // ---- OBJ ----

    /**
     * The planes as Wavefront OBJ text: one group per plane named `<kind>_<n>`, each outline fan-triangulated
     * from its first vertex (outlines are convex hulls, so the fan is valid). Meters, +Y up. Planes with fewer
     * than 3 outline vertices are skipped.
     */
    fun objText(s: ScanSnapshot): String {
        s.mesh?.let { return it.toObj("object") }
        val sb = StringBuilder()
        sb.append("# ARMeasure scan ").append(s.id).append(" - planes, meters, +Y up\n")
        var base = 0
        val counters = HashMap<PlaneKind, Int>()
        for (p in s.planes) {
            val n = p.vertexCount
            if (n < 3) continue
            val k = counters.merge(p.kind, 1, Int::plus)!!
            sb.append("g ").append(KindColors.label(p.kind)).append('_').append(k).append('\n')
            for (i in 0 until n) {
                sb.append(String.format(Locale.US, "v %.4f %.4f %.4f\n", p.outline[i * 3], p.outline[i * 3 + 1], p.outline[i * 3 + 2]))
            }
            for (i in 1 until n - 1) {
                sb.append("f ").append(base + 1).append(' ').append(base + i + 1).append(' ').append(base + i + 2).append('\n')
            }
            base += n
        }
        return sb.toString()
    }

    /** Writes [objText] to [target] atomically and returns it. */
    fun exportObj(s: ScanSnapshot, target: File): File {
        target.parentFile?.mkdirs()
        writeAtomic(target, objText(s).toByteArray(Charsets.UTF_8))
        return target
    }
}
