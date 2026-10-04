package com.example.arruler.beam

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

private val LENIENT = Json { ignoreUnknownKeys = true }

/** A file produced during a session; it stays where it is and is read when the bundle is built. */
@Serializable
data class Attachment(val role: String, val path: String)

@Serializable
data class SessionMeta(
    val id: String,
    val kind: String,
    val label: String = "",
    val startWallMs: Long,
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class SessionClosed(val reason: String, val endWallMs: Long)

/** A session as found on disk, open or closed. */
data class SessionRecord(
    val dir: File,
    val meta: SessionMeta,
    val closed: SessionClosed?,
    val eventCounts: Map<String, Int>,
    val eventTotal: Int,
    val snapshots: List<File>,
) {
    val timelineFile: File get() = File(dir, BeamSession.TIMELINE)
}

/**
 * One session: an app foreground period ("app") or a Scan / Object run ("scan", "object"). Lives in
 * <root>/<id>/ as timeline.jsonl, snapshots/, meta.json (start, attachments) and closed.json (written at the end, its
 * absence at the next launch means the app died, and the session is bundled as "crash").
 */
class BeamSession(
    val id: String,
    val kind: String,
    val label: String,
    val dir: File,
    clock: BeamClock = SystemBeamClock,
) {
    val startWallMs: Long = clock.wallMs()
    val timeline = Timeline(File(dir, TIMELINE), clock)
    val snapDir = File(dir, SNAPSHOTS)
    private val attachments = CopyOnWriteArrayList<Attachment>()
    private val clockRef = clock

    init {
        dir.mkdirs()
        writeMeta()
    }

    fun attach(role: String, file: File) {
        if (attachments.none { it.path == file.path && it.role == role }) attachments.add(Attachment(role, file.path))
        writeMeta()
    }

    fun attachments(): List<Attachment> = attachments.toList()

    private fun writeMeta() {
        try {
            File(dir, META).writeText(Json.encodeToString(SessionMeta.serializer(), SessionMeta(id, kind, label, startWallMs, attachments.toList())))
        } catch (_: Throwable) {
        }
    }

    /** Stops the timeline and writes closed.json. Idempotent. */
    fun close(reason: String): SessionClosed {
        timeline.close()
        val c = SessionClosed(reason, clockRef.wallMs())
        try {
            val f = File(dir, CLOSED)
            if (!f.exists()) f.writeText(Json.encodeToString(SessionClosed.serializer(), c))
        } catch (_: Throwable) {
        }
        return c
    }

    companion object {
        const val TIMELINE = "timeline.jsonl"
        const val SNAPSHOTS = "snapshots"
        const val META = "meta.json"
        const val CLOSED = "closed.json"

        /** `<yyyyMMdd-HHmmss UTC>-<kind>-<4 hex>`; sorts by time and is a safe directory and URL name. */
        fun newId(kind: String, wallMs: Long, rnd: Int): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(Date(wallMs))
            val k = kind.lowercase().filter { it.isLetterOrDigit() }.take(12).ifEmpty { "run" }
            return "$stamp-$k-${"%04x".format(rnd and 0xffff)}"
        }

        fun create(root: File, kind: String, label: String, clock: BeamClock = SystemBeamClock, rnd: Int = (Math.random() * 65536).toInt()): BeamSession {
            val id = newId(kind, clock.wallMs(), rnd)
            return BeamSession(id, kind, label, File(root, id), clock)
        }

        /** Reads a session directory back; null when it has no readable meta.json. */
        fun load(dir: File): SessionRecord? {
            val meta = try { LENIENT.decodeFromString(SessionMeta.serializer(), File(dir, META).readText()) } catch (_: Throwable) { return null }
            val closed = try { LENIENT.decodeFromString(SessionClosed.serializer(), File(dir, CLOSED).readText()) } catch (_: Throwable) { null }
            val counts = LinkedHashMap<String, Int>()
            var total = 0
            try {
                File(dir, TIMELINE).useLines { lines ->
                    for (l in lines) {
                        val t = try { Json.parseToJsonElement(l).jsonObject["type"]?.jsonPrimitive?.content } catch (_: Throwable) { null } ?: continue
                        counts[t] = (counts[t] ?: 0) + 1
                        total++
                    }
                }
            } catch (_: Throwable) {
            }
            val snaps = File(dir, SNAPSHOTS).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()
            return SessionRecord(dir, meta, closed, counts, total, snaps)
        }

        /** Session directories under [root] that were never closed (the app was killed or crashed). */
        fun orphans(root: File, exclude: Set<String> = emptySet()): List<File> =
            root.listFiles { f -> f.isDirectory && f.name !in exclude && File(f, META).isFile && !File(f, CLOSED).exists() }?.sortedBy { it.name } ?: emptyList()
    }
}
