package com.example.arruler.texture

import com.example.arruler.processing.PhotoFrame
import com.example.arruler.processing.ProcJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** One saved keyframe: JPEG file name (relative to the scan folder), pose (camera to world, 16 floats), intrinsics at the JPEG size. */
@Serializable
data class KeyframeRecord(
    val file: String,
    @SerialName("timestamp_ns") val timestampNs: Long,
    val pose: List<Float>,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float,
    val width: Int, val height: Int,
    val sharpness: Double = 0.0,
) {
    fun intrinsics() = Intrinsics(fx, fy, cx, cy, width, height)
}

@Serializable
data class KeyframeIndex(val schema: Int = 1, val keyframes: List<KeyframeRecord>)

/** `keyframes.json` + `kf_000001.jpg ...` under a scan folder. */
object KeyframeStore {
    const val INDEX = "keyframes.json"
    fun fileName(index1: Int) = "kf_%06d.jpg".format(index1)

    fun write(dir: File, records: List<KeyframeRecord>) {
        dir.mkdirs()
        File(dir, INDEX).writeText(ProcJson.json.encodeToString(KeyframeIndex(keyframes = records)), Charsets.UTF_8)
    }

    fun read(dir: File): List<KeyframeRecord> {
        val f = File(dir, INDEX)
        if (!f.isFile) return emptyList()
        return ProcJson.json.decodeFromString<KeyframeIndex>(f.readText(Charsets.UTF_8)).keyframes
    }

    fun toPhotoFrames(dir: File, records: List<KeyframeRecord>): List<PhotoFrame> = records.map {
        PhotoFrame(File(dir, it.file), it.timestampNs, it.pose.toFloatArray(), it.fx, it.fy, it.cx, it.cy, it.width, it.height)
    }
}
