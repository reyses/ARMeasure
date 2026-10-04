package com.example.arruler.tandem

import com.example.arruler.processing.BoxSpec
import com.example.arruler.processing.SupportPlaneSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class TandemRole { LEADER, HELPER }
enum class CaptureMode { SPIN, WALK }

/** What a [TandemMessage.Task] asks the other phone to do. */
enum class TaskKind {
    /** Run a job ZIP (processing/JobPackage layout) on the phone runner and return a result ZIP; the PcLink-over-peer path. */
    PACKAGE_JOB,

    /** Process my own capture locally (isolation, denoise) and return the isolated cloud as a point-job ZIP. */
    PARTIAL_OBJECT,

    /** Marching cubes on a field slab (file `FieldSlab`), return a mesh slab PLY. */
    MC_SLAB,

    /** Texture-bake a triangle subset with the local keyframes, return an atlas + uvs. */
    TEXTURE_RANGE,
}

/**
 * Control messages of the tandem session, JSON, one per BYTES frame, wrapped in an [Envelope]. The wire name of
 * every message is its `t` field. Blobs never travel in these: a message names a file tag and the file goes as a FILE payload.
 */
@Serializable
sealed interface TandemMessage {
    /** First message each way. [tier] is processing/DeviceProfile's [com.example.arruler.processing.Tier] name. */
    @Serializable @SerialName("hello")
    data class Hello(
        val device: String,
        val tier: String,
        @SerialName("depth_supported") val depthSupported: Boolean,
        @SerialName("app_version") val appVersion: String,
        @SerialName("bench_ms") val benchMs: Long? = null,
        @SerialName("protocol_max") val protocolMax: Int = TandemCodec.VERSION,
        /** Stable id of the sender within the session. Empty in a two-phone session; a third peer would get its own id without a version bump. */
        @SerialName("peer_id") val peerId: String = "",
    ) : TandemMessage

    /** [peerId] names who has [role]; [leaderId] names the leader the receiver reports to (both empty for the classic two-phone case). */
    @Serializable @SerialName("role")
    data class Role(val role: TandemRole, @SerialName("peer_id") val peerId: String = "", @SerialName("leader_id") val leaderId: String = "") : TandemMessage

    /** Leader to helper, [id] echoed back; [t0] = leader clock (ns) at send. */
    @Serializable @SerialName("clock_ping")
    data class ClockPing(val id: Int, val t0: Long) : TandemMessage

    /** Helper reply: [t1] = helper clock at receive, [t2] = helper clock at send (ns). */
    @Serializable @SerialName("clock_pong")
    data class ClockPong(val id: Int, val t0: Long, val t1: Long, val t2: Long) : TandemMessage

    /** Leader to helper after the ping round: helperClock - leaderClock ([offsetNs]) and the best round trip, so the helper can convert leader times. */
    @Serializable @SerialName("clock_set")
    data class ClockSet(@SerialName("offset_ns") val offsetNs: Long, @SerialName("rtt_ns") val rttNs: Long) : TandemMessage

    /**
     * Start capturing at [atLeaderNs] on the LEADER's clock (the helper converts with its estimated offset). [box] /
     * [supportPlane] / [axis] are in the LEADER's world frame and only a hint for the helper (it places its own box in its own frame).
     */
    @Serializable @SerialName("start_capture")
    data class StartCapture(
        @SerialName("at_leader_ns") val atLeaderNs: Long,
        val mode: CaptureMode,
        @SerialName("voxel_mm") val voxelMm: Int,
        val box: BoxSpec? = null,
        @SerialName("support_plane") val supportPlane: SupportPlaneSpec? = null,
        val axis: List<Float>? = null,
        /** Compass-style hint (degrees) of the helper's frame yaw against the leader's, if both phones know it; null = unknown. */
        @SerialName("yaw_prior_deg") val yawPriorDeg: Float? = null,
    ) : TandemMessage

    @Serializable @SerialName("stop_capture")
    data class StopCapture(@SerialName("at_leader_ns") val atLeaderNs: Long) : TandemMessage

    @Serializable @SerialName("task")
    data class Task(
        val id: String,
        val kind: TaskKind,
        /** Tag of the FILE payload that carries the input (sent before this message), or empty. */
        val ref: String = "",
        val params: Map<String, String> = emptyMap(),
        /** Peer that should run it; empty = the other end of this link. */
        val target: String = "",
    ) : TandemMessage

    @Serializable @SerialName("task_progress")
    data class TaskProgress(val id: String, val fraction: Float, val stage: String = "") : TandemMessage

    @Serializable @SerialName("task_result")
    data class TaskResult(
        val id: String,
        val ok: Boolean,
        /** Tag of the FILE payload carrying the output (sent before this message), or empty. */
        val ref: String = "",
        val error: String? = null,
        @SerialName("duration_ms") val durationMs: Long = 0,
        val notes: List<String> = emptyList(),
    ) : TandemMessage

    /** Live health so the planner can re-balance (thermal throttling, battery). [speedFactor] 1.0 = nominal. */
    @Serializable @SerialName("status")
    data class Status(
        @SerialName("thermal_status") val thermalStatus: Int = 0,
        @SerialName("thermal_headroom") val thermalHeadroom: Float? = null,
        @SerialName("battery_pct") val batteryPct: Int = -1,
        val charging: Boolean = false,
        @SerialName("speed_factor") val speedFactor: Float = 1f,
        @SerialName("peer_id") val peerId: String = "",
    ) : TandemMessage

    /** Leader to helper at the end of a job: lets the helper show the result. [singlePhone] = the helper's data was not merged. */
    @Serializable @SerialName("complete")
    data class Complete(
        @SerialName("job_id") val jobId: String,
        @SerialName("single_phone") val singlePhone: Boolean,
        val summary: Map<String, String> = emptyMap(),
    ) : TandemMessage

    @Serializable @SerialName("error")
    data class Error(val code: String, val message: String) : TandemMessage

    @Serializable @SerialName("bye")
    data class Bye(val reason: String = "") : TandemMessage
}

@Serializable
data class Envelope(val v: Int, val seq: Long, val msg: TandemMessage)

sealed interface Decoded {
    class Ok(val seq: Long, val msg: TandemMessage) : Decoded
    data class UnsupportedVersion(val version: Int) : Decoded
    data class Malformed(val reason: String) : Decoded
}

/** Versioned JSON codec; [decode] never throws. */
object TandemCodec {
    const val VERSION = 1

    val json: Json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(msg: TandemMessage, seq: Long = 0): ByteArray =
        json.encodeToString(Envelope.serializer(), Envelope(VERSION, seq, msg)).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Decoded {
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return Decoded.Malformed("not UTF-8")
        }
        return try {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return Decoded.Malformed("not a JSON object")
            val v = root["v"]?.jsonPrimitive?.intOrNull ?: return Decoded.Malformed("no version")
            if (v > VERSION || v < 1) return Decoded.UnsupportedVersion(v)
            val env = json.decodeFromJsonElement(Envelope.serializer(), root)
            Decoded.Ok(env.seq, env.msg)
        } catch (e: Exception) {
            Decoded.Malformed(e.message ?: e.javaClass.simpleName)
        }
    }
}

/** One NTP-style exchange. Times in ns: t0 leader send, t1 helper receive, t2 helper send, t3 leader receive. */
data class ClockSample(val t0: Long, val t1: Long, val t2: Long, val t3: Long) {
    /** Round trip without the helper's processing time. */
    val rttNs: Long get() = (t3 - t0) - (t2 - t1)

    /** helperClock - leaderClock, assuming symmetric one-way delays. */
    val offsetNs: Long get() = ((t1 - t0) + (t2 - t3)) / 2
}

/**
 * Keeps the last [keep] samples and uses the one with the smallest round trip (queueing and radio jitter only ever add delay,
 * so the fastest exchange has the least asymmetry: offset error <= (rtt - minimum possible rtt) / 2).
 */
class ClockEstimator(private val keep: Int = 16) {
    private val samples = ArrayDeque<ClockSample>()

    fun add(s: ClockSample) {
        if (s.rttNs < 0) return
        samples.addLast(s)
        while (samples.size > keep) samples.removeFirst()
    }

    val count: Int get() = samples.size
    private val best: ClockSample? get() = samples.minByOrNull { it.rttNs }

    /** helperClock - leaderClock in ns, or null before any sample. */
    val offsetNs: Long? get() = best?.offsetNs
    val bestRttNs: Long? get() = best?.rttNs
    fun leaderToHelper(leaderNs: Long): Long = leaderNs + (offsetNs ?: 0L)
    fun helperToLeader(helperNs: Long): Long = helperNs - (offsetNs ?: 0L)
}
