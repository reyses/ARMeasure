package com.example.arruler.beam

import com.example.arruler.devlink.Sha256
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Pure parts of the session bundle (manifest, file selection, size budget, ZIP writing). JVM-tested.

@Serializable
data class ManifestFile(val name: String, val bytes: Long, val role: String)

@Serializable
data class ManifestSkipped(val name: String, val bytes: Long, val why: String)

@Serializable
data class BundleManifest(
    val schema: Int = 1,
    val app: String,
    val version: String,
    val commit: String,
    val device: String,
    @SerialName("android_api") val androidApi: Int,
    @SerialName("session_id") val sessionId: String,
    val kind: String,
    val label: String,
    val reason: String,
    @SerialName("start_wall_ms") val startWallMs: Long,
    @SerialName("end_wall_ms") val endWallMs: Long,
    @SerialName("created_wall_ms") val createdWallMs: Long,
    @SerialName("event_total") val eventTotal: Int,
    @SerialName("event_counts") val eventCounts: Map<String, Int>,
    @SerialName("snapshot_count") val snapshotCount: Int,
    val files: List<ManifestFile>,
    val skipped: List<ManifestSkipped>,
)

/** Who is building the bundle: build identity, filled from BuildConfig / Build by the Android side. */
data class BundleIdentity(val app: String, val version: String, val commit: String, val device: String, val androidApi: Int)

/** Text pieces collected by the Android side (any may be null). */
data class BundleTexts(val logcat: String? = null, val diagnostics: String? = null, val crash: String? = null)

/** One thing that may go into the ZIP: a file on disk or in-memory bytes. [priority]: lower is kept first when over budget. */
data class Candidate(val entryName: String, val file: File?, val bytes: ByteArray?, val role: String, val priority: Int) {
    val size: Long get() = bytes?.size?.toLong() ?: file?.takeIf { it.isFile }?.length() ?: -1L
    val media: Boolean get() = entryName.endsWith(".mp4") || entryName.endsWith(".jpg") || entryName.endsWith(".zip")
}

data class BundlePlan(val included: List<Candidate>, val skipped: List<ManifestSkipped>)

private val PRETTY = Json { prettyPrint = true; encodeDefaults = true }

object BundleRoles {
    const val TIMELINE = "timeline"
    const val CRASH = "crash"
    const val DIAGNOSTICS = "diagnostics"
    const val LOGCAT = "logcat"
    const val EXPORT = "export"
    const val SNAPSHOT = "snapshot"
    const val ARCORE_RECORDING = "arcore_recording"
    const val SCREEN_RECORDING = "screen_recording"
}

object BundlePlanner {
    /** Default whole-bundle budget; the server accepts 4 GB. */
    const val DEFAULT_BUDGET = 1_500L * 1024 * 1024

    /** A file name that is safe inside a ZIP (no separators, no traversal) and unique among [taken]. */
    fun safeName(name: String, taken: MutableSet<String>): String {
        var base = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ').ifEmpty { "file" }
        if (base.length > 100) base = base.takeLast(100)
        var n = base
        var i = 1
        while (!taken.add(n)) {
            val dot = base.lastIndexOf('.')
            n = if (dot > 0) base.substring(0, dot) + "-" + i + base.substring(dot) else "$base-$i"
            i++
        }
        return n
    }

    /**
     * Selects what fits [budget] bytes: candidates sorted by priority (stable), each kept while the running total stays
     * within the budget; a missing file or a file that would break the budget is listed as skipped with the reason.
     * The settings switches are applied by the caller when it builds the candidate list.
     */
    fun plan(candidates: List<Candidate>, budget: Long = DEFAULT_BUDGET): BundlePlan {
        val inc = ArrayList<Candidate>()
        val skip = ArrayList<ManifestSkipped>()
        var used = 0L
        for (c in candidates.sortedBy { it.priority }) {
            val s = c.size
            when {
                s < 0 -> skip += ManifestSkipped(c.entryName, 0, "missing")
                used + s > budget -> skip += ManifestSkipped(c.entryName, s, "size budget ${budget / (1024 * 1024)} MB")
                else -> { inc += c; used += s }
            }
        }
        return BundlePlan(inc, skip)
    }
}

object SessionBundler {
    /**
     * Candidate list for [rec]: timeline, crash, diagnostics, logcat, exports, snapshots (newest first; only when
     * [policy] allows), ARCore recording (when camera video is on) and screen recording (when full screen recording is on).
     */
    fun candidates(rec: SessionRecord, texts: BundleTexts, policy: BeamPolicy): List<Candidate> {
        val out = ArrayList<Candidate>()
        val taken = HashSet<String>()
        out += Candidate("timeline.jsonl", rec.timelineFile, null, BundleRoles.TIMELINE, 1)
        texts.crash?.let { out += Candidate("crash.txt", null, it.toByteArray(Charsets.UTF_8), BundleRoles.CRASH, 1) }
        texts.diagnostics?.let { out += Candidate("diagnostics.txt", null, it.toByteArray(Charsets.UTF_8), BundleRoles.DIAGNOSTICS, 2) }
        texts.logcat?.let { out += Candidate("logcat.txt", null, it.toByteArray(Charsets.UTF_8), BundleRoles.LOGCAT, 3) }
        for (a in rec.meta.attachments) {
            val f = File(a.path)
            when (a.role) {
                BundleRoles.EXPORT -> out += Candidate("exports/" + BundlePlanner.safeName(f.name, taken), f, null, a.role, 4)
                BundleRoles.ARCORE_RECORDING -> if (policy.includeCameraVideo) out += Candidate("recordings/" + BundlePlanner.safeName(f.name, taken), f, null, a.role, 6)
                BundleRoles.SCREEN_RECORDING -> if (policy.fullScreenRecording) out += Candidate("screen/" + BundlePlanner.safeName(f.name, taken), f, null, a.role, 7)
                else -> out += Candidate("other/" + BundlePlanner.safeName(f.name, taken), f, null, a.role, 8)
            }
        }
        if (policy.includeScreenSnapshots) {
            for (s in rec.snapshots.sortedByDescending { it.name }) out += Candidate("snapshots/${s.name}", s, null, BundleRoles.SNAPSHOT, 5)
        }
        return out
    }

    fun manifest(rec: SessionRecord, id: BundleIdentity, plan: BundlePlan, nowMs: Long): BundleManifest {
        val closed = rec.closed
        return BundleManifest(
            app = id.app, version = id.version, commit = id.commit, device = id.device, androidApi = id.androidApi,
            sessionId = rec.meta.id, kind = rec.meta.kind, label = rec.meta.label, reason = closed?.reason ?: "crash",
            startWallMs = rec.meta.startWallMs, endWallMs = closed?.endWallMs ?: nowMs, createdWallMs = nowMs,
            eventTotal = rec.eventTotal, eventCounts = rec.eventCounts,
            snapshotCount = plan.included.count { it.role == BundleRoles.SNAPSHOT },
            files = plan.included.map { ManifestFile(it.entryName, it.size, it.role) }, skipped = plan.skipped,
        )
    }

    /** Writes [out] (a ZIP: manifest.json first, then the planned entries). Media is stored (level 0), text deflated. */
    fun write(out: File, manifest: BundleManifest, plan: BundlePlan) {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered(256 * 1024)).use { z ->
            z.setLevel(Deflater.DEFAULT_COMPRESSION)
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(PRETTY.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            for (c in plan.included) {
                z.setLevel(if (c.media) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                z.putNextEntry(ZipEntry(c.entryName))
                if (c.bytes != null) z.write(c.bytes) else c.file?.inputStream()?.use { it.copyTo(z, 256 * 1024) }
                z.closeEntry()
            }
        }
    }

    /** Whole pipeline for one session: plan, manifest, ZIP at [out]; returns the manifest and the ZIP's sha256. */
    fun build(rec: SessionRecord, id: BundleIdentity, texts: BundleTexts, policy: BeamPolicy, out: File, nowMs: Long, budget: Long = BundlePlanner.DEFAULT_BUDGET): Pair<BundleManifest, String> {
        val plan = BundlePlanner.plan(candidates(rec, texts, policy), budget)
        val m = manifest(rec, id, plan, nowMs)
        write(out, m, plan)
        return m to Sha256.hex(out)
    }

    /** Should a session be sent at all? An app session that only has start/stop and no error is not worth a bundle. */
    fun worthSending(rec: SessionRecord): Boolean {
        if (rec.closed == null || rec.closed.reason == "crash") return true
        val interesting = rec.eventCounts.filterKeys { it != EventTypes.APP_START && it != EventTypes.APP_STOP && it != EventTypes.SESSION_START && it != EventTypes.SESSION_END && it != EventTypes.FPS && it != EventTypes.THERMAL }
        return rec.meta.kind != "app" || interesting.isNotEmpty() || rec.snapshots.isNotEmpty() || rec.meta.attachments.isNotEmpty()
    }
}
