package com.example.arruler.diag

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.example.arruler.gpu.GpuContext
import com.example.arruler.processing.DeviceProfile
import com.example.arruler.texture.CameraConfigChooser
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan
import kotlin.math.sqrt

// ---------------------------------------------------------------- data (no Android types, so formatting is JVM-testable)

/** One Camera2 camera (a listed id, or a physical lens of a logical multi-camera). Lengths in mm, poses in metres. */
data class LensCamera(
    val id: String,
    /** The logical camera this physical lens belongs to; null for a camera listed by CameraManager. */
    val physicalOf: String?,
    val facing: String,
    val focalLengthsMm: List<Float>,
    /** SENSOR_INFO_PHYSICAL_SIZE (width, height) in mm. */
    val sensorMm: Pair<Float, Float>?,
    /** LENS_INFO_MINIMUM_FOCUS_DISTANCE in diopters; null / 0 = fixed focus or unknown. */
    val minFocusDiopters: Float?,
    val poseTranslationM: List<Float>?,
    val poseRotation: List<Float>?,
    val poseReference: String?,
    val capabilities: List<String>,
    /** Physical ids when this is a logical multi-camera. */
    val physicalIds: List<String>,
    /** "DEPTH16 640x480" style entries; empty when the camera has no depth stream. */
    val depthStreams: List<String>,
) {
    val isLogical: Boolean get() = physicalIds.isNotEmpty()
    val hasDepthOutput: Boolean get() = depthStreams.isNotEmpty() || "DEPTH_OUTPUT" in capabilities
    val hFovDeg: Double? get() = LensGeometry.hFovDeg(focalLengthsMm.firstOrNull(), sensorMm?.first)
}

enum class LensRole { ULTRAWIDE, MAIN, TELE }

/** Pure lens arithmetic. */
object LensGeometry {
    /** Diopters (1/m) to the nearest focus distance in cm; null for 0 / unknown (fixed focus). */
    fun minFocusCm(diopters: Float?): Double? = if (diopters == null || diopters <= 0f) null else 100.0 / diopters

    /** Horizontal field of view in degrees from focal length and sensor width (both mm). */
    fun hFovDeg(focalMm: Float?, sensorWidthMm: Float?): Double? {
        if (focalMm == null || sensorWidthMm == null || focalMm <= 0f || sensorWidthMm <= 0f) return null
        return Math.toDegrees(2.0 * atan(sensorWidthMm / (2.0 * focalMm)))
    }

    /** Straight-line distance between two lens poses in mm; null when either pose is missing or malformed. */
    fun baselineMm(a: List<Float>?, b: List<Float>?): Double? {
        if (a == null || b == null || a.size < 3 || b.size < 3) return null
        val dx = (a[0] - b[0]).toDouble(); val dy = (a[1] - b[1]).toDouble(); val dz = (a[2] - b[2]).toDouble()
        return 1000.0 * sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Roles of the back-facing physical lenses by field of view: widest at >= 95 degrees = ULTRAWIDE, narrowest at <= 50 = TELE,
     * MAIN = of the rest the one closest to 75 degrees. A logical camera is replaced by its physical lenses when those are known.
     */
    fun roles(cameras: List<LensCamera>): Map<LensRole, LensCamera> {
        val known = cameras.associateBy { it.id }
        val leaves = ArrayList<LensCamera>()
        for (c in cameras) {
            if (c.facing != "BACK") continue
            if (c.isLogical) { c.physicalIds.mapNotNullTo(leaves) { known[it] } } else leaves += c
        }
        val withFov = leaves.distinctBy { it.id }.mapNotNull { c -> c.hFovDeg?.let { c to it } }
        val out = LinkedHashMap<LensRole, LensCamera>()
        var rest = withFov
        withFov.maxByOrNull { it.second }?.takeIf { it.second >= 95.0 }?.let { out[LensRole.ULTRAWIDE] = it.first; rest = rest.filter { r -> r.first.id != it.first.id } }
        rest.minByOrNull { it.second }?.takeIf { it.second <= 50.0 }?.let { out[LensRole.TELE] = it.first; rest = rest.filter { r -> r.first.id != it.first.id } }
        rest.minByOrNull { Math.abs(it.second - 75.0) }?.let { out[LensRole.MAIN] = it.first }
        return out
    }

    /** Baseline in mm between two roles, or null when a role or its pose is absent. */
    fun baselineMm(cameras: List<LensCamera>, a: LensRole, b: LensRole): Double? {
        val r = roles(cameras)
        return baselineMm(r[a]?.poseTranslationM, r[b]?.poseTranslationM)
    }

    fun capabilityName(c: Int): String = when (c) {
        0 -> "BACKWARD_COMPATIBLE"; 1 -> "MANUAL_SENSOR"; 2 -> "MANUAL_POST_PROCESSING"; 3 -> "RAW"; 4 -> "PRIVATE_REPROCESSING"
        5 -> "READ_SENSOR_SETTINGS"; 6 -> "BURST_CAPTURE"; 7 -> "YUV_REPROCESSING"; 8 -> "DEPTH_OUTPUT"; 9 -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        10 -> "MOTION_TRACKING"; 11 -> "LOGICAL_MULTI_CAMERA"; 12 -> "MONOCHROME"; 13 -> "SECURE_IMAGE_DATA"; 14 -> "SYSTEM_CAMERA"
        15 -> "OFFLINE_PROCESSING"; 16 -> "ULTRA_HIGH_RESOLUTION_SENSOR"; 17 -> "REMOSAIC_REPROCESSING"; 18 -> "DYNAMIC_RANGE_TEN_BIT"
        19 -> "STREAM_USE_CASE"; 20 -> "COLOR_SPACE_PROFILES"; else -> "CAP_$c"
    }

    fun thermalName(s: Int): String = when (s) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "status $s"
    }
}

data class DeviceBasics(
    val manufacturer: String, val model: String, val device: String, val socManufacturer: String, val socModel: String,
    val sdk: Int, val release: String, val fingerprint: String, val appVersionName: String, val appVersionCode: Long,
    val tier: String, val benchMs: Long?, val thermal: String, val ramGb: Double, val cores: Int,
)

data class GlSummary(val vendor: String, val renderer: String, val version: String, val compute: String, val vulkan: String, val error: String?)

data class ArConfigRow(val index: Int, val image: String, val texture: String, val fps: String, val depthSensor: String, val stereo: String, val facing: String, val cameraId: String) {
    fun text() = "#$index cam $cameraId $facing image $image texture $texture $fps fps depth-sensor $depthSensor stereo $stereo"
}

data class ArSummary(
    val availability: String,
    val apkVersion: String?,
    val depthModes: List<Pair<String, Boolean>>,
    val configs: List<ArConfigRow>,
    val chosen: ArConfigRow?,
    val chosenDepthAutomatic: Boolean?,
    val error: String?,
)

data class CameraSummary(val cameras: List<LensCamera>, val concurrent: List<List<String>>?, val concurrentNote: String?, val error: String?)

data class DeviceReportData(val device: DeviceBasics?, val gl: GlSummary?, val ar: ArSummary?, val cameras: CameraSummary?)

// ---------------------------------------------------------------- formatting (pure)

object ReportFormat {
    private fun f(v: Double, digits: Int = 1) = String.format(Locale.US, "%.${digits}f", v)

    fun depthSensorLine(cams: List<LensCamera>): String {
        val d = cams.filter { it.hasDepthOutput }
        if (d.isEmpty()) return "Depth sensor (ToF): no (no camera has DEPTH_OUTPUT or a DEPTH16 / DEPTH_POINT_CLOUD stream)"
        return "Depth sensor (ToF): yes (" + d.joinToString("; ") { c ->
            "camera ${c.id}" + (if (c.depthStreams.isNotEmpty()) " " + c.depthStreams.joinToString(", ") else " DEPTH_OUTPUT")
        } + ")"
    }

    fun concurrentLine(c: CameraSummary): String = when {
        c.concurrent != null && c.concurrent.isEmpty() -> "Concurrent cameras: none reported"
        c.concurrent != null -> "Concurrent cameras: " + c.concurrent.joinToString(" | ") { it.joinToString("+") }
        else -> "Concurrent cameras: n/a (${c.concurrentNote ?: "needs Android 11"})"
    }

    fun baselineLine(cams: List<LensCamera>, a: LensRole, b: LensRole, label: String): String {
        val r = LensGeometry.roles(cams)
        val ca = r[a]; val cb = r[b]
        if (ca == null || cb == null) return "Stereo baseline $label: n/a (lens ${if (ca == null) a else b} not identified)"
        val mm = LensGeometry.baselineMm(ca.poseTranslationM, cb.poseTranslationM)
            ?: return "Stereo baseline $label: n/a (cameras ${ca.id}/${cb.id} report no LENS_POSE_TRANSLATION)"
        return "Stereo baseline $label: ${f(mm)} mm (cameras ${ca.id}/${cb.id})"
    }

    fun cameraLine(c: LensCamera, roles: Map<LensRole, LensCamera>): String {
        val role = roles.entries.firstOrNull { it.value.id == c.id }?.key?.name?.lowercase()
        val focal = c.focalLengthsMm.joinToString("/") { f(it.toDouble(), 2) }.ifEmpty { "?" }
        val sensor = c.sensorMm?.let { "${f(it.first.toDouble(), 2)}x${f(it.second.toDouble(), 2)} mm" } ?: "?"
        val fov = c.hFovDeg?.let { "${f(it, 0)} deg" } ?: "?"
        val focus = c.minFocusDiopters?.let { LensGeometry.minFocusCm(it)?.let { cm -> "${f(cm)} cm" } ?: "fixed" } ?: "?"
        val pose = c.poseTranslationM?.let { t -> "pose ${t.joinToString(",") { f(it * 1000.0, 1) }} mm" + (c.poseReference?.let { " ref $it" } ?: "") } ?: "no pose"
        val kind = if (c.physicalOf != null) "phys of ${c.physicalOf}" else if (c.isLogical) "logical [${c.physicalIds.joinToString(",")}]" else "single"
        val caps = c.capabilities.filter { it in KEY_CAPS }.joinToString(",").ifEmpty { "-" }
        return "  ${c.id} ${c.facing} $kind${role?.let { " ($it)" } ?: ""}: f $focal mm, sensor $sensor, hFOV $fov, min focus $focus, $pose, caps $caps" +
            (if (c.depthStreams.isNotEmpty()) ", depth ${c.depthStreams.joinToString(" ")}" else "")
    }

    private val KEY_CAPS = setOf("LOGICAL_MULTI_CAMERA", "DEPTH_OUTPUT", "MANUAL_SENSOR", "RAW", "BURST_CAPTURE", "MOTION_TRACKING", "ULTRA_HIGH_RESOLUTION_SENSOR")

    /**
     * The whole plain-text report (target under 8 KB). [gpuLines] = the GPU section body (gate line + self-test lines),
     * pass empty when the self-test was not run.
     */
    fun format(data: DeviceReportData, gpuLines: List<String>, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).also { it.timeZone = tz }.format(Date(nowMs))
        sb.append("ARMeasure diagnostics report, ").append(stamp).append(' ').append(tz.getDisplayName(false, TimeZone.SHORT, Locale.US)).append('\n')
        data.device?.let { d ->
            sb.append("\n== Device ==\n")
            sb.append("${d.manufacturer} ${d.model} (${d.device}), SoC ${listOf(d.socManufacturer, d.socModel).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "n/a" }}, Android ${d.release} (API ${d.sdk})\n")
            sb.append("Fingerprint: ${d.fingerprint}\n")
            sb.append("App ${d.appVersionName} (${d.appVersionCode}); RAM ${f(d.ramGb)} GB; ${d.cores} cores; tier ${d.tier}; CPU benchmark ${d.benchMs?.let { "$it ms" } ?: "not run"}; thermal ${d.thermal}\n")
        }
        sb.append("\n== GPU ==\n")
        data.gl?.let { g ->
            sb.append("GL: ${g.vendor} | ${g.renderer} | ${g.version}\n")
            sb.append("Compute: ${g.compute}\nVulkan: ${g.vulkan}\n")
            g.error?.let { sb.append("GL note: $it\n") }
        }
        if (gpuLines.isEmpty()) sb.append("Self-test: not run\n") else for (l in gpuLines) sb.append(l).append('\n')
        data.ar?.let { a ->
            sb.append("\n== AR ==\n")
            sb.append("ARCore: ${a.availability}, apk ${a.apkVersion ?: "n/a"}\n")
            a.error?.let { sb.append("AR note: $it\n") }
            if (a.depthModes.isNotEmpty()) sb.append("Depth modes: ${a.depthModes.joinToString(", ") { "${it.first}=${if (it.second) "yes" else "no"}" }}\n")
            a.chosen?.let { sb.append("Chosen config (CameraConfigChooser): ${it.text()}${a.chosenDepthAutomatic?.let { d -> ", AUTOMATIC depth ${if (d) "yes" else "no"}" } ?: ""}\n") }
            if (a.configs.isNotEmpty()) {
                sb.append("Offered configs (${a.configs.size}):\n")
                for (c in a.configs) sb.append("  ").append(c.text()).append('\n')
            }
        }
        data.cameras?.let { c ->
            sb.append("\n== Cameras ==\n")
            c.error?.let { sb.append("Camera note: $it\n") }
            sb.append(depthSensorLine(c.cameras)).append('\n')
            sb.append(concurrentLine(c)).append('\n')
            sb.append(baselineLine(c.cameras, LensRole.MAIN, LensRole.ULTRAWIDE, "main<->ultrawide")).append('\n')
            if (LensGeometry.roles(c.cameras).containsKey(LensRole.TELE)) sb.append(baselineLine(c.cameras, LensRole.MAIN, LensRole.TELE, "main<->tele")).append('\n')
            val roles = LensGeometry.roles(c.cameras)
            for (cam in c.cameras) sb.append(cameraLine(cam, roles)).append('\n')
        }
        return sb.toString()
    }

    /** Download file name: '<model> <yyyy-MM-dd>.txt' with file-system-unsafe characters dropped. */
    fun fileName(model: String, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = tz }.format(Date(nowMs))
        val m = model.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().ifEmpty { "device" }
        return "$m $day.txt"
    }
}

// ---------------------------------------------------------------- collectors (Android)

object DeviceReportCollector {
    fun basics(app: Context, benchmark: Long? = null): DeviceBasics {
        val profile = DeviceProfile.read(app, false)
        val info = try { app.packageManager.getPackageInfo(app.packageName, 0) } catch (e: Exception) { null }
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: 0L
        return DeviceBasics(
            Build.MANUFACTURER, Build.MODEL, Build.DEVICE,
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "",
            Build.VERSION.SDK_INT, Build.VERSION.RELEASE, Build.FINGERPRINT, info?.versionName ?: "?", code,
            profile.tier.name, benchmark ?: profile.signals.benchMs, LensGeometry.thermalName(profile.thermalStatus),
            profile.signals.totalMemBytes / (1024.0 * 1024 * 1024), profile.signals.cores,
        )
    }

    fun gl(app: Context): GlSummary {
        val c = try { GpuContext.create(app) } catch (e: Throwable) { null } ?: return GlSummary("n/a", "n/a", "n/a", "n/a", "n/a", "EGL context could not be created")
        return try {
            val i = c.info
            GlSummary(
                i.glVendor, i.glRenderer, i.glVersion,
                if (i.computeSupported) "yes, ES ${i.esMajor}.${i.esMinor}, ${i.maxWorkGroupInvocations} invocations/group, size ${i.maxWorkGroupSize.toList()}, count ${i.maxWorkGroupCount.toList()}, SSBO ${i.maxSsboBytes / (1024 * 1024)} MiB"
                else "no (ES ${i.esMajor}.${i.esMinor})",
                i.vulkanText, null,
            )
        } finally { try { c.close() } catch (e: Throwable) { /* ignore */ } }
    }

    /** Camera2 survey. Opens no camera, only reads characteristics. */
    fun cameras(app: Context): CameraSummary {
        return try {
            val mgr = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val out = ArrayList<LensCamera>()
            val seen = HashSet<String>()
            fun add(id: String, parent: String?) {
                if (!seen.add(id)) return
                val ch = try { mgr.getCameraCharacteristics(id) } catch (e: Throwable) { return }
                val physical = if (Build.VERSION.SDK_INT >= 28) ch.physicalCameraIds.toList() else emptyList()
                out += lens(id, parent, ch, physical)
                for (p in physical) add(p, id)
            }
            for (id in mgr.cameraIdList) add(id, null)
            var conc: List<List<String>>? = null
            var note: String? = null
            if (Build.VERSION.SDK_INT >= 30) {
                try { conc = mgr.concurrentCameraIds.map { it.sorted() }.sortedBy { it.joinToString() } } catch (e: Throwable) { note = e.javaClass.simpleName }
            } else note = "needs Android 11"
            CameraSummary(out, conc, note, null)
        } catch (e: Throwable) {
            CameraSummary(emptyList(), null, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun lens(id: String, parent: String?, ch: CameraCharacteristics, physical: List<String>): LensCamera {
        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"; CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"; else -> "?"
        }
        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val caps = (ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)).map { LensGeometry.capabilityName(it) }
        val depth = ArrayList<String>()
        try {
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val formats = mutableListOf("DEPTH16" to ImageFormat.DEPTH16, "DEPTH_POINT_CLOUD" to ImageFormat.DEPTH_POINT_CLOUD)
                if (Build.VERSION.SDK_INT >= 29) formats += "DEPTH_JPEG" to ImageFormat.DEPTH_JPEG
                for ((name, fmt) in formats) {
                    val sizes = try { map.getOutputSizes(fmt) } catch (e: Throwable) { null }
                    if (sizes != null && sizes.isNotEmpty()) depth += name + " " + sizes.joinToString("/") { "${it.width}x${it.height}" }
                }
            }
        } catch (e: Throwable) { /* no stream map */ }
        val ref = if (Build.VERSION.SDK_INT >= 28) when (ch.get(CameraCharacteristics.LENS_POSE_REFERENCE)) {
            CameraCharacteristics.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> "PRIMARY_CAMERA"
            CameraCharacteristics.LENS_POSE_REFERENCE_GYROSCOPE -> "GYROSCOPE"
            CameraCharacteristics.LENS_POSE_REFERENCE_UNDEFINED -> "UNDEFINED"
            CameraCharacteristics.LENS_POSE_REFERENCE_AUTOMOTIVE -> "AUTOMOTIVE"
            else -> null
        } else null
        return LensCamera(
            id, parent, facing, ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
            size?.let { it.width to it.height }, ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            ch.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.toList(), ch.get(CameraCharacteristics.LENS_POSE_ROTATION)?.toList(), ref,
            caps, physical, depth,
        )
    }

    private fun row(i: Int, c: CameraConfig) = ArConfigRow(
        i, "${c.imageSize.width}x${c.imageSize.height}", "${c.textureSize.width}x${c.textureSize.height}", "${c.fpsRange.lower}-${c.fpsRange.upper}",
        c.depthSensorUsage.name, try { c.stereoCameraUsage.name } catch (e: Throwable) { "n/a" }, c.facingDirection.name, c.cameraId,
    )

    /**
     * ARCore survey with a short-lived Session (created, queried, paused and closed here). Call it only while the AR view is
     * paused (the diagnostics screen gates it) so two sessions never hold the camera.
     */
    fun ar(app: Context): ArSummary {
        val apk = try { app.packageManager.getPackageInfo("com.google.ar.core", 0).versionName } catch (e: PackageManager.NameNotFoundException) { null } catch (e: Throwable) { null }
        val avail = try { ArCoreApk.getInstance().checkAvailability(app).name } catch (e: Throwable) { "unknown (${e.javaClass.simpleName})" }
        if (avail != "SUPPORTED_INSTALLED") return ArSummary(avail, apk, emptyList(), emptyList(), null, null, "no session created: ARCore is not installed and ready")
        var session: Session? = null
        return try {
            session = Session(app)
            val s = session
            val modes = Config.DepthMode.values().map { it.name to (try { s.isDepthModeSupported(it) } catch (e: Throwable) { false }) }
            val configs = try { s.getSupportedCameraConfigs(CameraConfigFilter(s)).mapIndexed { i, c -> row(i, c) } } catch (e: Throwable) { emptyList() }
            val chosenCfg = CameraConfigChooser.select(s)
            val chosen = configs.firstOrNull { it.cameraId == chosenCfg.cameraId && it.image == "${chosenCfg.imageSize.width}x${chosenCfg.imageSize.height}" && it.fps == "${chosenCfg.fpsRange.lower}-${chosenCfg.fpsRange.upper}" }
                ?: row(-1, chosenCfg)
            val autoDepth = try { s.isDepthModeSupported(Config.DepthMode.AUTOMATIC) } catch (e: Throwable) { null }
            ArSummary(avail, apk, modes, configs, chosen, autoDepth, null)
        } catch (e: Throwable) {
            ArSummary(avail, apk, emptyList(), emptyList(), null, null, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { session?.pause() } catch (e: Throwable) { /* never resumed */ }
            try { session?.close() } catch (e: Throwable) { /* ignore */ }
        }
    }

    /** Everything except the AR session and the self-test, cheap enough for the main thread's first frame. */
    fun collectAll(app: Context): DeviceReportData = DeviceReportData(
        runCatching { basics(app) }.getOrNull(), runCatching { gl(app) }.getOrNull(), runCatching { ar(app) }.getOrNull(), runCatching { cameras(app) }.getOrNull(),
    )
}
