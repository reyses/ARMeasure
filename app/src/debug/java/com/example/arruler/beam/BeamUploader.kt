package com.example.arruler.beam

import com.example.arruler.devlink.Sha256
import com.example.arruler.processing.PairingInfo
import com.example.arruler.processing.ProcJson
import com.example.arruler.processing.UrlOrder
import com.example.arruler.processing.UrlPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

// Chunked, resumable bundle upload. Wire contract (server: pc-server/armeasure_pc/bundles.py, docs/BEAM.md):
//   POST /v1/dev/bundles                  {"name","size","sha256"} -> {"id","chunk_size","chunks","received":[..]}
//   PUT  /v1/dev/bundles/{id}/chunks/{n}  raw bytes of chunk n (0-based, chunk_size each, the last one shorter)
//   GET  /v1/dev/bundles/{id}             -> {"id","size","chunk_size","chunks","received":[..],"state"}
//   POST /v1/dev/bundles/{id}/complete    -> {"id","state":"complete","path"}  (422 = sha256 mismatch, 409 = chunks missing)

@Serializable
data class CreateBundleRequest(val name: String, val size: Long, val sha256: String)

@Serializable
data class BundleReply(
    val id: String = "",
    @SerialName("chunk_size") val chunkSize: Int = 0,
    val chunks: Int = 0,
    val received: List<Int> = emptyList(),
    val state: String = "",
)

/** [status] 0 = no HTTP answer (network); [retryable] = worth another try with the same ids. */
class BeamHttpException(val status: Int, message: String) : Exception(message) {
    val retryable: Boolean get() = status == 0 || status == 408 || status == 429 || status >= 500
}

/** The four calls. Blocking; called from a background thread. */
interface BeamTransport {
    fun create(name: String, size: Long, sha256: String): BundleReply
    fun putChunk(id: String, n: Int, bytes: ByteArray)
    fun status(id: String): BundleReply
    fun complete(id: String): BundleReply
}

/** The persisted progress of one queued bundle (queue/<name>.json next to queue/<name>.zip). */
@Serializable
data class UploadState(
    val name: String,
    val size: Long,
    val sha256: String,
    val serverId: String = "",
    val chunkSize: Int = 0,
    val attempts: Int = 0,
    val restarts: Int = 0,
)

sealed interface UploadResult {
    data class Done(val serverId: String) : UploadResult
    /** Stopped early (cancelled, or retries exhausted); the bundle stays queued with its [state]. */
    data class Paused(val why: String, val state: UploadState) : UploadResult
    /** The server refused it for good (4xx other than the retryable ones); the bundle is dropped. */
    data class Rejected(val why: String) : UploadResult
}

class ChunkedUploader(
    private val transport: BeamTransport,
    private val chunkRetries: Int = 4,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val maxRestarts: Int = 2,
) {
    /**
     * Uploads [zip] (resuming [state] when it has a server id). [onState] is called whenever the state changes and must
     * persist it; [onProgress] gets 0..1; [cancelled] is polled between chunks.
     */
    fun upload(zip: File, state: UploadState, onState: (UploadState) -> Unit, onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): UploadResult {
        var st = state
        try {
            var reply: BundleReply? = null
            if (st.serverId.isNotEmpty()) {
                reply = try {
                    transport.status(st.serverId)
                } catch (e: BeamHttpException) {
                    if (e.status == 404) null else throw e
                }
            }
            if (reply == null) {
                reply = transport.create(st.name, st.size, st.sha256)
                st = st.copy(serverId = reply.id, chunkSize = reply.chunkSize)
                onState(st)
            }
            if (reply.state == "complete") return Done(st)
            val chunkSize = reply.chunkSize.takeIf { it > 0 } ?: return UploadResult.Rejected("server announced no chunk size")
            val total = ChunkMath.count(st.size, chunkSize)
            val have = reply.received.toMutableSet()
            RandomAccessFile(zip, "r").use { raf ->
                for (n in 0 until total) {
                    if (n in have) continue
                    if (cancelled()) return UploadResult.Paused("cancelled", st)
                    val len = ChunkMath.length(st.size, chunkSize, n)
                    val buf = ByteArray(len)
                    raf.seek(n.toLong() * chunkSize)
                    raf.readFully(buf)
                    putWithRetry(st.serverId, n, buf)
                    have += n
                    onProgress(have.size.toFloat() / total)
                }
            }
            return try {
                transport.complete(st.serverId)
                Done(st)
            } catch (e: BeamHttpException) {
                when {
                    e.status == 422 && st.restarts < maxRestarts -> {
                        // sha256 mismatch: the server dropped the chunks; start over under a new id.
                        st = st.copy(serverId = "", chunkSize = 0, restarts = st.restarts + 1)
                        onState(st)
                        UploadResult.Paused("sha256 mismatch on the server, restarting", st)
                    }
                    e.status == 409 -> {
                        UploadResult.Paused("server is missing chunks, will re-check", st)
                    }
                    else -> throw e
                }
            }
        } catch (e: BeamHttpException) {
            return if (e.retryable) UploadResult.Paused(e.message ?: "network", st.copy(attempts = st.attempts + 1))
            else UploadResult.Rejected("HTTP ${e.status}: ${e.message}")
        }
    }

    private fun Done(st: UploadState) = UploadResult.Done(st.serverId)

    private fun putWithRetry(id: String, n: Int, bytes: ByteArray) {
        var attempt = 0
        while (true) {
            try {
                transport.putChunk(id, n, bytes)
                return
            } catch (e: BeamHttpException) {
                attempt++
                if (!e.retryable || attempt > chunkRetries) throw e
                sleep(minOf(30_000L, 500L shl attempt))
            }
        }
    }
}

object ChunkMath {
    fun count(size: Long, chunkSize: Int): Int = if (size <= 0) 0 else ((size + chunkSize - 1) / chunkSize).toInt()

    fun length(size: Long, chunkSize: Int, n: Int): Int = minOf(chunkSize.toLong(), size - n.toLong() * chunkSize).toInt()
}

/** Real transport over OkHttp: tries the allowed pairing URLs in [UrlOrder] order, 2 s connect on all but the last. */
class HttpBeamTransport(
    private val pairing: PairingInfo,
    private val urls: List<String>,
    client: OkHttpClient? = null,
) : BeamTransport {
    private val http: OkHttpClient = (client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build())
        .newBuilder().followRedirects(false).followSslRedirects(false)
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (!UrlPolicy.isAllowed(req.url.toString())) throw IOException("Blocked by URL policy: ${req.url.host}")
            chain.proceed(req.newBuilder().header("Authorization", "Bearer ${pairing.token}").build())
        }).build()
    @Volatile private var lastGood: String? = null

    private fun call(build: (String) -> Request): String {
        val order = UrlOrder.order(urls, lastGood)
        var failure: BeamHttpException? = null
        for ((i, base) in order.withIndex()) {
            val client = if (i < order.size - 1) http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).build() else http
            val resp = try {
                client.newCall(build(base.trimEnd('/'))).execute()
            } catch (e: IOException) {
                failure = BeamHttpException(0, e.message ?: "network error")
                continue
            }
            lastGood = base
            resp.use { r ->
                val text = try { r.body.string() } catch (e: IOException) { throw BeamHttpException(0, e.message ?: "network error") }
                if (!r.isSuccessful) {
                    val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "HTTP ${r.code}"
                    throw BeamHttpException(r.code, msg)
                }
                return text
            }
        }
        throw failure ?: BeamHttpException(0, "no allowed PC address")
    }

    private fun json(t: String) = ProcJson.json.decodeFromString<BundleReply>(t)

    override fun create(name: String, size: Long, sha256: String): BundleReply {
        val body = ProcJson.json.encodeToString(CreateBundleRequest.serializer(), CreateBundleRequest(name, size, sha256))
            .toRequestBody("application/json".toMediaType())
        return json(call { Request.Builder().url("$it/v1/dev/bundles").post(body).build() })
    }

    override fun putChunk(id: String, n: Int, bytes: ByteArray) {
        val body: RequestBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        call { Request.Builder().url("$it/v1/dev/bundles/$id/chunks/$n").put(body).build() }
    }

    override fun status(id: String): BundleReply = json(call { Request.Builder().url("$it/v1/dev/bundles/$id").get().build() })

    override fun complete(id: String): BundleReply =
        json(call { Request.Builder().url("$it/v1/dev/bundles/$id/complete").post(ByteArray(0).toRequestBody(null)).build() })
}

/** On-disk upload queue: queue/<name>.zip with queue/<name>.json (an [UploadState]). */
class BeamQueue(private val dir: File) {
    data class Entry(val zip: File, val stateFile: File, val state: UploadState)

    fun enqueue(zip: File, name: String): Entry {
        dir.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(dir, "$safe.zip")
        if (zip.canonicalPath != dest.canonicalPath && !zip.renameTo(dest)) { zip.copyTo(dest, overwrite = true); zip.delete() }
        val st = UploadState(name = "$safe.zip", size = dest.length(), sha256 = Sha256.hex(dest))
        val sf = File(dir, "$safe.json")
        save(sf, st)
        return Entry(dest, sf, st)
    }

    /** Oldest first. Entries whose zip or state is unreadable are deleted. */
    fun pending(): List<Entry> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }.mapNotNull { sf ->
            val zip = File(dir, sf.name.removeSuffix(".json") + ".zip")
            val st = try { ProcJson.json.decodeFromString(UploadState.serializer(), sf.readText()) } catch (_: Throwable) { null }
            if (st == null || !zip.isFile) { sf.delete(); zip.delete(); null } else Entry(zip, sf, st)
        }

    fun save(stateFile: File, st: UploadState) {
        stateFile.writeText(ProcJson.json.encodeToString(UploadState.serializer(), st))
    }

    fun remove(e: Entry) { e.zip.delete(); e.stateFile.delete() }

    /** Keeps the newest bundles within [maxBytes] and [maxCount]; returns how many were dropped. */
    fun prune(maxBytes: Long = 3L * 1024 * 1024 * 1024, maxCount: Int = 12): Int {
        val all = pending().reversed() // newest first
        var used = 0L
        var dropped = 0
        for ((i, e) in all.withIndex()) {
            used += e.zip.length()
            if (i >= maxCount || used > maxBytes) { remove(e); dropped++ }
        }
        return dropped
    }

    fun pendingBytes(): Long = pending().sumOf { it.zip.length() }
}

/** Runs the whole queue once with one transport; used by the JobService and by tests. */
class QueueRunner(private val queue: BeamQueue, private val uploaderFor: (BeamQueue.Entry) -> ChunkedUploader?) {
    constructor(queue: BeamQueue, uploader: ChunkedUploader) : this(queue, { uploader })

    data class Summary(val sent: Int, val kept: Int, val rejected: Int, val lastReason: String)

    fun run(onProgress: (name: String, p: Float) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): Summary {
        var sent = 0; var kept = 0; var rejected = 0; var reason = ""
        for (e in queue.pending()) {
            if (cancelled()) { kept++; continue }
            // null = this bundle may not use the current network (large bundle on mobile data): it stays queued.
            val uploader = uploaderFor(e)
            if (uploader == null) { kept++; reason = "waiting for Wi-Fi"; continue }
            val r = uploader.upload(e.zip, e.state, { queue.save(e.stateFile, it) }, { onProgress(e.state.name, it) }, cancelled)
            when (r) {
                is UploadResult.Done -> { queue.remove(e); sent++ }
                is UploadResult.Paused -> { queue.save(e.stateFile, r.state); kept++; reason = r.why }
                is UploadResult.Rejected -> { queue.remove(e); rejected++; reason = r.why }
            }
        }
        return Summary(sent, kept, rejected, reason)
    }
}
