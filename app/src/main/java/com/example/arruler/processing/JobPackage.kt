package com.example.arruler.processing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PackageFormatException(message: String) : Exception(message)

@Serializable
data class DeviceSummary(
    val tier: String,
    @SerialName("performance_class") val performanceClass: Int = 0,
    @SerialName("total_mem_mb") val totalMemMb: Long = 0,
    val cores: Int = 0,
    val soc: String = "",
    @SerialName("bench_ms") val benchMs: Long? = null
)

@Serializable
data class CoordinateConvention(
    val frame: String = "ARCore world",
    val units: String = "meters",
    val up: String = "+Y",
    val handedness: String = "right",
    @SerialName("pose_layout") val poseLayout: String = "4x4 column-major, camera-to-world",
    @SerialName("camera_axes") val cameraAxes: String = "OpenGL: +X right, +Y up, camera looks along -Z"
)

@Serializable
data class JobManifest(
    val schema: Int = JobPackage.SCHEMA,
    @SerialName("job_type") val jobType: String,
    @SerialName("app_version") val appVersion: String,
    val units: String = "meters",
    val created: String,
    val device: DeviceSummary? = null,
    val coordinates: CoordinateConvention = CoordinateConvention(),
    val quality: String? = null,
    @SerialName("voxel_mm") val voxelMm: Int? = null,
    @SerialName("point_count") val pointCount: Int = 0,
    @SerialName("image_count") val imageCount: Int = 0,
    val files: List<String> = emptyList()
)

/** Caller-supplied manifest fields. [created] is ISO-8601 UTC, e.g. 2026-10-03T12:00:00Z. */
data class PackageMeta(
    val appVersion: String,
    val created: String,
    val device: DeviceSummary? = null,
    val quality: ObjectQuality? = null
)

/** Point payload: x y z in meters, voxel hit count (0..65535) and confidence (0..255). */
class CloudData(val xyz: FloatArray, val hits: IntArray, val confidence: IntArray) {
    init {
        require(xyz.size % 3 == 0) { "xyz size must be a multiple of 3" }
        require(hits.size == xyz.size / 3 && confidence.size == hits.size) { "attribute sizes must match the point count" }
    }

    val count: Int get() = xyz.size / 3

    companion object {
        fun fromXyz(xyz: FloatArray, hits: Int = 1, confidence: Int = 255) =
            CloudData(xyz, IntArray(xyz.size / 3) { hits }, IntArray(xyz.size / 3) { confidence })
    }
}

/** Binary little-endian PLY: float x y z, ushort hits, uchar confidence (15 bytes per vertex). */
object PlyCloud {
    private const val STRIDE = 15

    fun write(out: OutputStream, cloud: CloudData) {
        val header = "ply\nformat binary_little_endian 1.0\nelement vertex ${cloud.count}\n" +
            "property float x\nproperty float y\nproperty float z\n" +
            "property ushort hits\nproperty uchar confidence\nend_header\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        val chunk = 4096
        val buf = ByteBuffer.allocate(chunk * STRIDE).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < cloud.count) {
            buf.clear()
            val end = minOf(cloud.count, i + chunk)
            for (k in i until end) {
                buf.putFloat(cloud.xyz[k * 3]).putFloat(cloud.xyz[k * 3 + 1]).putFloat(cloud.xyz[k * 3 + 2])
                buf.putShort(cloud.hits[k].coerceIn(0, 65535).toShort())
                buf.put(cloud.confidence[k].coerceIn(0, 255).toByte())
            }
            out.write(buf.array(), 0, buf.position())
            i = end
        }
    }

    fun read(input: InputStream): CloudData {
        val header = StringBuilder()
        while (!header.endsWith("end_header\n")) {
            val b = input.read()
            if (b < 0 || header.length > 4096) throw PackageFormatException("PLY header not terminated")
            header.append(b.toChar())
        }
        val lines = header.lines()
        if (lines.firstOrNull() != "ply" || "format binary_little_endian 1.0" !in lines) {
            throw PackageFormatException("not a binary little-endian PLY")
        }
        val n = lines.firstOrNull { it.startsWith("element vertex ") }?.substringAfter("element vertex ")?.trim()?.toIntOrNull()
            ?: throw PackageFormatException("PLY has no vertex element")
        val props = lines.filter { it.startsWith("property ") }.map { it.trim() }
        val expected = listOf("property float x", "property float y", "property float z", "property ushort hits", "property uchar confidence")
        if (props != expected) throw PackageFormatException("unexpected PLY properties: $props")
        val xyz = FloatArray(n * 3)
        val hits = IntArray(n)
        val conf = IntArray(n)
        val chunk = 4096
        val raw = ByteArray(chunk * STRIDE)
        var i = 0
        while (i < n) {
            val take = minOf(chunk, n - i)
            var got = 0
            while (got < take * STRIDE) {
                val r = input.read(raw, got, take * STRIDE - got)
                if (r < 0) throw PackageFormatException("PLY truncated")
                got += r
            }
            val bb = ByteBuffer.wrap(raw, 0, take * STRIDE).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until take) {
                val idx = i + k
                xyz[idx * 3] = bb.getFloat(); xyz[idx * 3 + 1] = bb.getFloat(); xyz[idx * 3 + 2] = bb.getFloat()
                hits[idx] = bb.getShort().toInt() and 0xFFFF
                conf[idx] = bb.get().toInt() and 0xFF
            }
            i += take
        }
        return CloudData(xyz, hits, conf)
    }
}

/** One photogrammetry frame as captured. [pose] = 16 floats, column-major, from Camera.getPose (physical camera, not getDisplayOrientedPose). */
class PhotoFrame(
    val jpeg: File,
    val timestampNs: Long,
    val pose: FloatArray,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float,
    val width: Int, val height: Int
) {
    init { require(pose.size == 16) { "pose must have 16 floats" } }
}

@Serializable
data class ImagePose(
    val file: String,
    @SerialName("timestamp_ns") val timestampNs: Long,
    val pose: List<Float>,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float,
    val width: Int, val height: Int
)

@Serializable
data class PosesFile(val schema: Int = JobPackage.SCHEMA, val images: List<ImagePose>)

class JobPackageContents(
    val manifest: JobManifest,
    val cloud: CloudData?,
    val poses: PosesFile?,
    val imageNames: List<String>
)

/** Upload ZIP writer and reader. Layout is documented in docs/PROCESSING_PROTOCOL.md. */
object JobPackage {
    const val SCHEMA = 1
    const val MANIFEST = "manifest.json"
    const val CLOUD = "cloud.ply"
    const val POSES = "poses.json"
    const val IMAGES_DIR = "images/"

    fun imageName(index1: Int) = IMAGES_DIR + "%06d.jpg".format(index1)

    fun writePointJob(dest: File, type: JobType, cloud: CloudData, meta: PackageMeta) {
        require(type != JobType.PHOTOGRAMMETRY) { "use writePhotoJob" }
        val manifest = JobManifest(
            jobType = type.wire, appVersion = meta.appVersion, created = meta.created, device = meta.device,
            quality = meta.quality?.name, voxelMm = meta.quality?.voxelMm,
            pointCount = cloud.count, files = listOf(CLOUD)
        )
        ZipOutputStream(dest.outputStream().buffered()).use { z ->
            z.putText(MANIFEST, ProcJson.json.encodeToString(manifest))
            z.putNextEntry(ZipEntry(CLOUD))
            PlyCloud.write(z, cloud)
            z.closeEntry()
        }
    }

    fun writePhotoJob(dest: File, frames: List<PhotoFrame>, meta: PackageMeta) {
        val names = frames.indices.map { imageName(it + 1) }
        val manifest = JobManifest(
            jobType = JobType.PHOTOGRAMMETRY.wire, appVersion = meta.appVersion, created = meta.created, device = meta.device,
            quality = (meta.quality ?: ObjectQuality.DETAILED).name,
            imageCount = frames.size, files = listOf(POSES) + names
        )
        val poses = PosesFile(images = frames.mapIndexed { i, f ->
            ImagePose(names[i], f.timestampNs, f.pose.toList(), f.fx, f.fy, f.cx, f.cy, f.width, f.height)
        })
        ZipOutputStream(dest.outputStream().buffered()).use { z ->
            z.putText(MANIFEST, ProcJson.json.encodeToString(manifest))
            z.putText(POSES, ProcJson.json.encodeToString(poses))
            z.setLevel(Deflater.NO_COMPRESSION) // JPEGs do not compress
            frames.forEachIndexed { i, f ->
                z.putNextEntry(ZipEntry(names[i]))
                f.jpeg.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name)); write(text.toByteArray(Charsets.UTF_8)); closeEntry()
    }

    fun readManifest(file: File): JobManifest = ZipFile(file).use { readManifest(it) }

    private fun readManifest(zf: ZipFile): JobManifest {
        val e = zf.getEntry(MANIFEST) ?: throw PackageFormatException("manifest.json missing")
        val m = try {
            ProcJson.json.decodeFromString<JobManifest>(zf.getInputStream(e).readBytes().toString(Charsets.UTF_8))
        } catch (ex: Exception) {
            throw PackageFormatException("manifest.json invalid: ${ex.message}")
        }
        if (m.schema > SCHEMA) throw PackageFormatException("unsupported schema ${m.schema}")
        if (JobType.fromWire(m.jobType) == null) throw PackageFormatException("unknown job type ${m.jobType}")
        return m
    }

    fun read(file: File): JobPackageContents = ZipFile(file).use { zf ->
        val m = readManifest(zf)
        val cloud = zf.getEntry(CLOUD)?.let { e -> zf.getInputStream(e).buffered().use { PlyCloud.read(it) } }
        val poses = zf.getEntry(POSES)?.let { e ->
            ProcJson.json.decodeFromString<PosesFile>(zf.getInputStream(e).readBytes().toString(Charsets.UTF_8))
        }
        val images = zf.entries().asSequence().map { it.name }.filter { it.startsWith(IMAGES_DIR) && !it.endsWith("/") }.sorted().toList()
        if (JobType.fromWire(m.jobType) == JobType.PHOTOGRAMMETRY) {
            if (poses == null) throw PackageFormatException("poses.json missing")
            if (poses.images.size != images.size) throw PackageFormatException("poses.json lists ${poses.images.size} images, zip has ${images.size}")
        } else if (cloud == null) throw PackageFormatException("cloud.ply missing")
        JobPackageContents(m, cloud, poses, images)
    }
}
