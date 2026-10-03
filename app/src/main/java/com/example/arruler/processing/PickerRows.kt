package com.example.arruler.processing

/** One row of the quality / backend picker. A disabled row carries the [reason] it is greyed out. */
data class PickerRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val reason: String? = null,
)

/** Pure construction of the picker rows from the routing layer. */
object PickerRows {
    const val PHOTO_REASON = "photo capture is not in this build yet"

    /**
     * Object picker rows from [Router.availableQualities]. Photogrammetry rows stay visible but greyed
     * ([photoCapture] = false) because the app cannot capture the 60-120 photos yet.
     */
    fun forObject(options: List<QualityOption>, photoCapture: Boolean = false): List<PickerRow> = options.map { o ->
        val photo = o.quality.jobType == JobType.PHOTOGRAMMETRY
        val enabled = o.enabled && (photoCapture || !photo)
        val where = if (o.backend == Backend.PC) "on your PC" else "on this phone"
        var sub = "$where, ${o.estimatedTime}, ${o.accuracy}"
        if (o.needsConfirmation) sub += ", asks before uploading on mobile data"
        PickerRow(o.quality.name, o.label, sub, enabled, if (enabled) null else (o.reason ?: PHOTO_REASON))
    }

    /** Default selection: the router's best option, unless a row of its was disabled here; else the last enabled row. */
    fun defaultId(rows: List<PickerRow>, options: List<QualityOption>): String? {
        val routerPick = Router.defaultQuality(options).name
        return if (rows.any { it.id == routerPick && it.enabled }) routerPick else rows.lastOrNull { it.enabled }?.id
    }

    /** Where to run a room-scan analysis: ids are [UserPref] names. */
    fun forScan(pcAvailable: Boolean, pointCount: Int, tier: Tier): List<PickerRow> = listOf(
        PickerRow(
            UserPref.AUTO.name, "Automatic",
            "the app picks: PC for big scans (over ${Router.formatCount(Router.maxComfortPoints(tier))} points on a $tier phone), else the phone",
            true,
        ),
        PickerRow(UserPref.PHONE.name, "On this phone", "about ${Router.formatCount(pointCount)} points, may be slow on a LOW phone", true),
        PickerRow(
            UserPref.PC.name, "On your PC", "sends the point cloud; the 3D view shows the mesh when the PC makes one",
            pcAvailable, if (pcAvailable) null else "needs your PC",
        ),
    )
}
