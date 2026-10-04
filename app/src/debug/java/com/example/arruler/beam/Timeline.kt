package com.example.arruler.beam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

// Pure parts of Beam (no Android types, JVM-tested): the event timeline of one session. Wire format: docs/BEAM.md.

/** Event type names written to timeline.jsonl; the PC inspector knows these, unknown types are still kept. */
object EventTypes {
    const val APP_START = "app_start"
    const val APP_STOP = "app_stop"
    const val SCREEN_CHANGE = "screen_change"
    const val MODE_CHANGE = "mode_change"
    const val TAP = "tap"
    const val HIT_QUALITY = "hit_quality"
    const val BOX_PLACED = "box_placed"
    const val BOX_MOVED = "box_moved"
    const val BOX_RESIZED = "box_resized"
    const val CAPTURE_START = "capture_start"
    const val CAPTURE_STOP = "capture_stop"
    const val ANALYZE_RESULT = "analyze_result"
    const val EXPORT = "export"
    const val TRACKING_STATE = "tracking_state"
    const val FPS = "fps"
    const val THERMAL = "thermal"
    const val ERROR = "error"
    const val GPU_GATE = "gpu_gate"
    const val SESSION_START = "session_start"
    const val SESSION_END = "session_end"
    const val SNAPSHOT = "snapshot"
    const val TRUNCATED = "timeline_truncated"
}

/** Two clocks: monotonic (orders events, immune to wall clock changes) and wall (aligns with the PC and the videos). */
interface BeamClock {
    fun monoMs(): Long
    fun wallMs(): Long
}

object SystemBeamClock : BeamClock {
    override fun monoMs(): Long = System.nanoTime() / 1_000_000L
    override fun wallMs(): Long = System.currentTimeMillis()
}

data class TimelineEvent(val seq: Long, val monoMs: Long, val wallMs: Long, val type: String, val json: String)

object EventJson {
    const val MAX_STRING = 4000
    const val MAX_STACK = 16000
    private const val MAX_DEPTH = 4

    fun value(v: Any?, depth: Int = 0): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Float -> if (v.isFinite()) JsonPrimitive(v) else JsonNull
        is Double -> if (v.isFinite()) JsonPrimitive(v) else JsonNull
        is Number -> JsonPrimitive(v.toDouble().takeIf { it.isFinite() })
        is CharSequence -> JsonPrimitive(clip(v.toString(), MAX_STRING))
        is Throwable -> buildJsonObject {
            put("message", JsonPrimitive(clip(v.message ?: v.javaClass.simpleName, MAX_STRING)))
            put("stack", JsonPrimitive(clip(v.stackTraceToString(), MAX_STACK)))
        }
        is Map<*, *> -> if (depth >= MAX_DEPTH) JsonPrimitive(clip(v.toString(), MAX_STRING)) else buildJsonObject {
            for ((k, x) in v) put(k.toString(), value(x, depth + 1))
        }
        is Iterable<*> -> if (depth >= MAX_DEPTH) JsonPrimitive(clip(v.toString(), MAX_STRING)) else buildJsonArray {
            var n = 0
            for (x in v) { if (n++ >= 200) break; add(value(x, depth + 1)) }
        }
        is Array<*> -> value(v.asList(), depth)
        is FloatArray -> value(v.asList(), depth)
        is DoubleArray -> value(v.asList(), depth)
        is IntArray -> value(v.asList(), depth)
        is File -> JsonPrimitive(v.name)
        else -> JsonPrimitive(clip(v.toString(), MAX_STRING))
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max) + "...[+${s.length - max} chars]"

    /** One JSONL line (no newline): {"seq","t_mono_ms","t_wall_ms","type","data"}. */
    fun line(seq: Long, monoMs: Long, wallMs: Long, type: String, data: Map<String, Any?>): String =
        Json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("seq", JsonPrimitive(seq))
            put("t_mono_ms", JsonPrimitive(monoMs))
            put("t_wall_ms", JsonPrimitive(wallMs))
            put("type", JsonPrimitive(type))
            put("data", value(data))
        })
}

/**
 * Bounded in-memory (last [memoryCap] events) and file-backed (JSONL, at most [fileCapBytes]) event log of one session.
 * [event] is safe from any thread, cheap, and never throws: a full disk or a closed file only counts a drop.
 */
class Timeline(
    private val file: File?,
    private val clock: BeamClock = SystemBeamClock,
    private val memoryCap: Int = 2000,
    private val fileCapBytes: Long = 8L * 1024 * 1024,
) {
    private val lock = Any()
    private val ring = ArrayDeque<TimelineEvent>()
    private val counts = LinkedHashMap<String, Int>()
    private var seq = 0L
    private var writer: BufferedWriter? = null
    private var bytes = 0L
    private var capped = false
    private var closed = false

    @Volatile var dropped = 0L
        private set

    /** Total events accepted (including ones that fell out of memory). */
    val total: Long get() = synchronized(lock) { seq }

    fun event(type: String, data: Map<String, Any?> = emptyMap()): Boolean {
        try {
            synchronized(lock) {
                if (closed) { dropped++; return false }
                val s = ++seq
                val line = EventJson.line(s, clock.monoMs(), clock.wallMs(), type, data)
                val ev = TimelineEvent(s, clock.monoMs(), clock.wallMs(), type, line)
                if (ring.size >= memoryCap) ring.removeFirst()
                ring.addLast(ev)
                counts[type] = (counts[type] ?: 0) + 1
                writeLine(line)
                return true
            }
        } catch (_: Throwable) {
            dropped++
            return false
        }
    }

    private fun writeLine(line: String) {
        val f = file ?: return
        if (capped) return
        try {
            var w = writer
            if (w == null) {
                f.parentFile?.mkdirs()
                w = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
                writer = w
                bytes = f.length()
            }
            if (bytes + line.length + 1 > fileCapBytes) {
                capped = true
                val note = EventJson.line(seq, clock.monoMs(), clock.wallMs(), EventTypes.TRUNCATED, mapOf("cap_bytes" to fileCapBytes))
                w.write(note); w.write("\n"); w.flush()
                return
            }
            w.write(line); w.write("\n"); w.flush()
            bytes += line.toByteArray(Charsets.UTF_8).size + 1
        } catch (_: Throwable) {
            dropped++
        }
    }

    fun snapshot(): List<TimelineEvent> = synchronized(lock) { ring.toList() }

    fun countsByType(): Map<String, Int> = synchronized(lock) { LinkedHashMap(counts) }

    fun close() {
        synchronized(lock) {
            closed = true
            try { writer?.close() } catch (_: Throwable) { }
            writer = null
        }
    }
}

/** hit_quality is emitted only when (quality, kind) changes, so a steady reticle costs nothing. */
class HitQualityDebouncer {
    private var last: Pair<String, String>? = null

    fun shouldEmit(quality: String, kind: String): Boolean {
        val now = quality to kind
        if (now == last) return false
        last = now
        return true
    }

    fun reset() { last = null }
}

/** Per-second frame summary: call [onFrame] per rendered frame; it returns the fps event data once a second has passed. */
class FpsAggregator(private val windowMs: Long = 1000L) {
    private var start = -1L
    private var last = -1L
    private var frames = 0
    private var maxGap = 0L

    fun onFrame(monoMs: Long): Map<String, Any?>? {
        if (start < 0) { start = monoMs; last = monoMs; frames = 1; maxGap = 0; return null }
        frames++
        maxGap = maxOf(maxGap, monoMs - last)
        last = monoMs
        val span = monoMs - start
        if (span < windowMs) return null
        val out = mapOf("fps" to Math.round(frames * 10_000.0 / span) / 10.0, "frames" to frames, "max_gap_ms" to maxGap)
        start = monoMs; frames = 0; maxGap = 0
        return out
    }
}
