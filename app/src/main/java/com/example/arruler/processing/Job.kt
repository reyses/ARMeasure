package com.example.arruler.processing

import kotlinx.serialization.json.Json

/** Shared JSON configuration for every file in the processing protocol (all keys snake_case). */
object ProcJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }
}

/** What kind of heavy work a job is. [wire] is the string used in manifests and HTTP calls. */
enum class JobType(val wire: String) {
    /** voxel cloud -> planes + room */
    SCAN_ANALYZE("scan_analyze"),

    /** isolated object points -> measures + mesh */
    OBJECT_MESH("object_mesh"),

    /** images + poses + intrinsics -> textured mesh; PC only */
    PHOTOGRAMMETRY("photogrammetry");

    companion object {
        fun fromWire(s: String): JobType? = entries.firstOrNull { it.wire == s }
    }
}

enum class UserPref { AUTO, PHONE, PC }

enum class Backend { PHONE, PC }

/**
 * Object capture quality (objects are about 200 mm). Ordered worst to best.
 * [voxelMm] is the depth voxel size (null for photogrammetry). [jobType] is the job it produces.
 */
enum class ObjectQuality(val jobType: JobType, val voxelMm: Int?, val label: String, val accuracy: String) {
    /** depth, 5 mm voxels, any tier, phone */
    QUICK(JobType.OBJECT_MESH, 5, "Quick (depth, 5 mm)", "±1 cm"),

    /** depth, 3 mm voxels, two orbits; phone only on MID/HIGH, else PC */
    FINE(JobType.OBJECT_MESH, 3, "Fine (depth, 3 mm, two orbits)", "±5-8 mm"),

    /** photogrammetry, 60-120 photos + ARCore poses; PC only */
    DETAILED(JobType.PHOTOGRAMMETRY, null, "Detailed (photos, textured)", "±1-2 mm"),

    /** reserved: Gaussian splat; PC only, disabled until [SPLAT_ENABLED] */
    DETAILED_SPLAT(JobType.PHOTOGRAMMETRY, null, "Detailed splat (reserved)", "±1-2 mm");

    companion object {
        const val SPLAT_ENABLED = false
    }
}

/** Size of a job, used for routing and the upload-size estimate. */
data class JobEstimate(
    val pointCount: Int = 0,
    val imageCount: Int = 0,
    /** Exact payload size when known (e.g. summed JPEG lengths); 0 = estimate it. */
    val imageBytes: Long = 0L
) {
    /** Approximate upload size in bytes: cloud.ply is 15 B/point, a 12 MP JPEG is taken as 2 MB. */
    fun uploadBytes(type: JobType): Long = when (type) {
        JobType.PHOTOGRAMMETRY -> if (imageBytes > 0) imageBytes else imageCount * DEFAULT_IMAGE_BYTES
        else -> pointCount * PLY_BYTES_PER_POINT
    }

    companion object {
        const val PLY_BYTES_PER_POINT = 15L
        const val DEFAULT_IMAGE_BYTES = 2_000_000L
    }
}

/**
 * The routing outcome. [blocked] = the job cannot run anywhere right now (PC-only without a
 * reachable PC, or a disabled quality); [needsConfirmation] = PC upload over mobile data above
 * [Router.MOBILE_CONFIRM_BYTES].
 */
data class RouteDecision(
    val backend: Backend,
    val reason: String,
    val warning: String? = null,
    val needsConfirmation: Boolean = false,
    val blocked: Boolean = false
)

/** One row of the quality picker. A disabled row carries [reason] ("needs your PC"). */
data class QualityOption(
    val quality: ObjectQuality,
    val backend: Backend,
    val label: String,
    val estimatedTime: String,
    val accuracy: String,
    val enabled: Boolean,
    val reason: String? = null,
    val needsConfirmation: Boolean = false
)

/**
 * Phone-or-PC routing. Pure, so it is JVM-tested.
 *
 * PC-only work: PHOTOGRAMMETRY, quality DETAILED / DETAILED_SPLAT, and quality FINE on a LOW phone.
 * Rules, in order:
 *  0. quality DETAILED_SPLAT is disabled -> blocked.
 *  1. PC-only work -> PC; no reachable PC -> blocked (user preference is ignored).
 *  2. userPref PHONE -> PHONE (warning if above the tier's comfort limit or the phone is hot / low).
 *  3. userPref PC -> PC if available, else PHONE with a warning "PC not reachable".
 *  4. AUTO with a PC available -> PC when points exceed [maxComfortPoints] for the tier (LOW: 50k,
 *     MID: 150k, HIGH: 400k), when thermal status >= MODERATE ([HOT_THERMAL]), or when the battery
 *     is low; otherwise PHONE.
 *  5. AUTO without a PC -> PHONE, with a warning when above the comfort limit, hot, or low battery.
 *  6. A PC decision on mobile data (not Wi-Fi) with an upload above 20 MB asks for confirmation.
 */
object Router {
    const val MOBILE_CONFIRM_BYTES = 20L * 1024 * 1024

    /** PowerManager.THERMAL_STATUS_MODERATE: the phone is already throttling. */
    const val HOT_THERMAL = 2

    /** Photo count assumed by the picker before any photos exist. */
    const val ASSUMED_PHOTOS = 60

    fun maxComfortPoints(tier: Tier): Int = when (tier) {
        Tier.LOW -> 50_000
        Tier.MID -> 150_000
        Tier.HIGH -> 400_000
    }

    /** True when [quality] (or the job type) can only run on the PC for this [tier]. */
    fun pcOnly(job: JobType, quality: ObjectQuality?, tier: Tier): Boolean =
        job == JobType.PHOTOGRAMMETRY ||
            quality == ObjectQuality.DETAILED || quality == ObjectQuality.DETAILED_SPLAT ||
            (quality == ObjectQuality.FINE && tier == Tier.LOW)

    fun decide(
        job: JobType,
        quality: ObjectQuality?,
        estimate: JobEstimate,
        tier: Tier,
        pcAvailable: Boolean,
        userPref: UserPref,
        onWifi: Boolean,
        batteryLow: Boolean,
        thermal: Int
    ): RouteDecision {
        val hot = thermal >= HOT_THERMAL
        val over = job != JobType.PHOTOGRAMMETRY && estimate.pointCount > maxComfortPoints(tier)
        val pts = formatCount(estimate.pointCount)

        fun phoneWarning(): String? {
            val w = ArrayList<String>()
            if (over) w += "$pts points is above the comfortable ${formatCount(maxComfortPoints(tier))} for a $tier phone, so this may be slow"
            if (hot) w += "the phone is hot (thermal status $thermal)"
            if (batteryLow) w += "battery is low"
            return if (w.isEmpty()) null else w.joinToString("; ").replaceFirstChar { it.uppercase() }
        }

        fun pc(reason: String): RouteDecision {
            val bytes = estimate.uploadBytes(job)
            val confirm = !onWifi && bytes > MOBILE_CONFIRM_BYTES
            val warn = if (confirm) "Upload is about ${bytes / (1024 * 1024)} MB on mobile data" else null
            return RouteDecision(Backend.PC, reason, warning = warn, needsConfirmation = confirm)
        }

        fun phone(reason: String, extra: String? = null): RouteDecision {
            val w = listOfNotNull(extra, phoneWarning()).joinToString(". ").ifEmpty { null }
            return RouteDecision(Backend.PHONE, reason, warning = w)
        }

        if (quality == ObjectQuality.DETAILED_SPLAT && !ObjectQuality.SPLAT_ENABLED) {
            return RouteDecision(Backend.PC, "PC: splat quality is reserved", warning = "Not available yet", blocked = true)
        }
        if (pcOnly(job, quality, tier)) {
            val why = when {
                job == JobType.PHOTOGRAMMETRY -> "PC: photogrammetry only runs on the PC"
                quality == ObjectQuality.FINE -> "PC: FINE needs a MID or HIGH phone, this is $tier"
                else -> "PC: $quality only runs on the PC"
            }
            if (!pcAvailable) {
                return RouteDecision(
                    Backend.PC, why,
                    warning = "No reachable PC. Pair a PC in Settings and make sure it is on the same network.",
                    blocked = true
                )
            }
            return pc(why)
        }
        when (userPref) {
            UserPref.PHONE -> return phone("Phone: chosen in Settings")
            UserPref.PC ->
                return if (pcAvailable) pc("PC: chosen in Settings")
                else phone("Phone: PC not reachable", "PC not reachable, running on the phone")
            UserPref.AUTO -> Unit
        }
        if (!pcAvailable) return phone("Phone: no PC available")
        return when {
            over -> pc("PC: $pts points on a $tier phone")
            hot -> pc("PC: phone is hot")
            batteryLow -> pc("PC: phone battery is low")
            else -> phone("Phone: $pts points fits a $tier phone")
        }
    }

    /**
     * The picker rows, worst to best. Disabled rows carry a reason. [onWifi] / [estimate] only drive
     * the confirmation flag (a PC option on mobile data is flagged when its upload would exceed
     * 20 MB; photo options assume [ASSUMED_PHOTOS] photos when [estimate] has none).
     */
    fun availableQualities(
        tier: Tier,
        pcAvailable: Boolean,
        onWifi: Boolean = true,
        estimate: JobEstimate = JobEstimate()
    ): List<QualityOption> = ObjectQuality.entries.map { q ->
        val photoEst = if (estimate.imageCount > 0 || estimate.imageBytes > 0) estimate
        else estimate.copy(imageCount = ASSUMED_PHOTOS)
        val confirm = { onPc: Boolean ->
            onPc && !onWifi && (if (q.jobType == JobType.PHOTOGRAMMETRY) photoEst else estimate).uploadBytes(q.jobType) > MOBILE_CONFIRM_BYTES
        }
        when {
            q == ObjectQuality.DETAILED_SPLAT && !ObjectQuality.SPLAT_ENABLED ->
                QualityOption(q, Backend.PC, q.label, "n/a", q.accuracy, false, "reserved, not available yet")
            pcOnly(q.jobType, q, tier) ->
                QualityOption(
                    q, Backend.PC, q.label, timeEstimate(q, Backend.PC, tier), q.accuracy,
                    pcAvailable, if (pcAvailable) null else "needs your PC", confirm(true)
                )
            else -> QualityOption(q, Backend.PHONE, q.label, timeEstimate(q, Backend.PHONE, tier), q.accuracy, true)
        }
    }

    /** Best enabled option that needs no confirmation (QUICK is always a valid fallback). */
    fun defaultQuality(options: List<QualityOption>): ObjectQuality =
        options.lastOrNull { it.enabled && !it.needsConfirmation }?.quality ?: ObjectQuality.QUICK

    fun timeEstimate(q: ObjectQuality, backend: Backend, tier: Tier): String = when (q) {
        ObjectQuality.QUICK -> when (tier) { Tier.LOW -> "about 20 s"; Tier.MID -> "about 10 s"; Tier.HIGH -> "about 5 s" }
        ObjectQuality.FINE ->
            if (backend == Backend.PC) "about 20 s plus upload"
            else if (tier == Tier.MID) "about 1 min" else "about 30 s"
        ObjectQuality.DETAILED -> "about 5-15 min plus upload"
        ObjectQuality.DETAILED_SPLAT -> "n/a"
    }

    /** 180_000 -> "180k", 950 -> "950". */
    fun formatCount(n: Int): String = if (n >= 1000) "${(n + 500) / 1000}k" else n.toString()
}
