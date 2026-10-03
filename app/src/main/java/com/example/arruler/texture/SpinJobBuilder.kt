package com.example.arruler.texture

import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.SupportPlane
import com.example.arruler.processing.CloudData
import com.example.arruler.processing.JobPackage
import com.example.arruler.processing.PackageMeta
import com.example.arruler.processing.PlyCloud
import com.example.arruler.processing.ProcJson
import com.example.arruler.processing.toSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

enum class CaptureMode(val wire: String) { SPIN("spin"), HYBRID("hybrid") }

/**
 * Inputs of a spin / hybrid job: [spinDir] holds the spin keyframes (`keyframes.json`, static camera, the object turns);
 * for [CaptureMode.HYBRID] also [walkDir] (walk-around keyframes with real poses) and [walkCloud] (the walk-around depth cloud).
 */
class SpinJobInput(
    val mode: CaptureMode,
    val spinDir: File,
    val box: ObjectBox,
    val plane: SupportPlane,
    val meta: PackageMeta,
    val walkDir: File? = null,
    val walkCloud: CloudData? = null,
    val maskPadM: Float = BoxMask.DEFAULT_PAD_M,
)

/**
 * Builds the PHOTOGRAMMETRY ZIP for spin / hybrid capture by composing [JobPackage.writePhotoJob] (images + poses.json +
 * manifest are exactly the standard package) and then adding: manifest extras, `masks/<image>.png`, and for hybrid
 * `cloud.ply` plus the walk-around JPEGs under `walk/images/` + `walk/poses.json`. Manifest extras are specified in docs/TEXTURE.md.
 */
object SpinJobBuilder {
    const val MASKS_DIR = "masks/"
    const val WALK_DIR = "walk/"

    /** Name of the mask for a package image `images/000001.jpg`: `masks/000001.jpg.png`. */
    fun maskName(imageEntry: String) = MASKS_DIR + imageEntry.substringAfterLast('/') + ".png"

    /** @return number of spin images packed. */
    fun build(dest: File, input: SpinJobInput): Int {
        val spin = KeyframeStore.read(input.spinDir).filter { File(input.spinDir, it.file).isFile }
        require(spin.isNotEmpty()) { "no spin keyframes in ${input.spinDir}" }
        if (input.mode == CaptureMode.HYBRID) {
            require(input.walkDir != null && input.walkCloud != null) { "hybrid needs walkDir and walkCloud" }
        }
        val tmpMain = File.createTempFile("spin_main", ".zip")
        val tmpWalk = if (input.mode == CaptureMode.HYBRID) File.createTempFile("spin_walk", ".zip") else null
        try {
            JobPackage.writePhotoJob(tmpMain, KeyframeStore.toPhotoFrames(input.spinDir, spin), input.meta)
            var walkCount = 0
            if (tmpWalk != null) {
                val walk = KeyframeStore.read(input.walkDir!!).filter { File(input.walkDir, it.file).isFile }
                require(walk.isNotEmpty()) { "no walk keyframes in ${input.walkDir}" }
                JobPackage.writePhotoJob(tmpWalk, KeyframeStore.toPhotoFrames(input.walkDir, walk), input.meta)
                walkCount = walk.size
            }
            val imageNames = spin.indices.map { JobPackage.imageName(it + 1) }
            val maskNames = imageNames.map { maskName(it) }

            ZipFile(tmpMain).use { main ->
                val oldManifest = main.getInputStream(main.getEntry(JobPackage.MANIFEST)).readBytes().toString(Charsets.UTF_8)
                val manifest = augmentManifest(oldManifest, input, spin, walkCount, maskNames)
                ZipOutputStream(dest.outputStream().buffered()).use { z ->
                    z.putNextEntry(ZipEntry(JobPackage.MANIFEST)); z.write(manifest.toByteArray(Charsets.UTF_8)); z.closeEntry()
                    z.setLevel(Deflater.NO_COMPRESSION)
                    for (e in main.entries()) {
                        if (e.name == JobPackage.MANIFEST) continue
                        z.putNextEntry(ZipEntry(e.name)); main.getInputStream(e).use { it.copyTo(z) }; z.closeEntry()
                    }
                    spin.forEachIndexed { i, r ->
                        z.putNextEntry(ZipEntry(maskNames[i]))
                        z.write(BoxMask.maskPng(input.box, r.pose.toFloatArray(), r.intrinsics(), input.maskPadM))
                        z.closeEntry()
                    }
                    if (tmpWalk != null) {
                        z.putNextEntry(ZipEntry(JobPackage.CLOUD)); PlyCloud.write(z, input.walkCloud!!); z.closeEntry()
                        ZipFile(tmpWalk).use { walk ->
                            for (e in walk.entries()) {
                                if (e.name == JobPackage.MANIFEST) continue
                                z.putNextEntry(ZipEntry(WALK_DIR + e.name)); walk.getInputStream(e).use { it.copyTo(z) }; z.closeEntry()
                            }
                        }
                    }
                }
            }
        } finally {
            tmpMain.delete(); tmpWalk?.delete()
        }
        return spin.size
    }

    private fun num(v: Float) = JsonPrimitive(v)

    internal fun augmentManifest(old: String, input: SpinJobInput, spin: List<KeyframeRecord>, walkCount: Int, maskNames: List<String>): String {
        val base = ProcJson.json.parseToJsonElement(old).jsonObject
        val n = spin.size
        val cx = spin.sumOf { it.pose[12].toDouble() } / n
        val cy = spin.sumOf { it.pose[13].toDouble() } / n
        val cz = spin.sumOf { it.pose[14].toDouble() } / n
        val bx = input.box.centre.x; val bz = input.box.centre.z
        val toAxis = sqrt((cx - bx) * (cx - bx) + (cz - bz) * (cz - bz)).toFloat()
        val height = input.plane.signedDistance(cx.toFloat(), cy.toFloat(), cz.toFloat())
        val extras = LinkedHashMap<String, JsonElement>()
        extras["capture"] = JsonPrimitive(input.mode.wire)
        extras["camera_static"] = JsonPrimitive(true)
        extras["box"] = ProcJson.json.encodeToJsonElement(input.box.toSpec())
        extras["support_plane"] = ProcJson.json.encodeToJsonElement(input.plane.toSpec())
        extras["camera_to_axis_m"] = num(toAxis)
        extras["camera_height_above_plane_m"] = num(height)
        extras["rotation_axis"] = JsonObject(mapOf(
            "point" to JsonArray(listOf(num(input.box.centre.x), num(input.box.centre.y), num(input.box.centre.z))),
            "direction" to JsonArray(listOf(num(0f), num(1f), num(0f))),
        ))
        extras["masks_dir"] = JsonPrimitive(MASKS_DIR)
        extras["mask_padding_m"] = num(input.maskPadM)
        if (input.mode == CaptureMode.HYBRID) {
            extras["walk"] = JsonObject(mapOf(
                "dir" to JsonPrimitive(WALK_DIR),
                "poses" to JsonPrimitive(WALK_DIR + JobPackage.POSES),
                "images_dir" to JsonPrimitive(WALK_DIR + JobPackage.IMAGES_DIR),
                "image_count" to JsonPrimitive(walkCount),
            ))
        }
        val files = base["files"]?.jsonArray?.toMutableList() ?: ArrayList()
        for (m in maskNames) files.add(JsonPrimitive(m))
        if (input.mode == CaptureMode.HYBRID) files.add(JsonPrimitive(JobPackage.CLOUD))
        val merged = LinkedHashMap<String, JsonElement>(base)
        merged.putAll(extras)
        merged["files"] = JsonArray(files)
        return ProcJson.json.encodeToString(JsonObject.serializer(), JsonObject(merged))
    }
}
