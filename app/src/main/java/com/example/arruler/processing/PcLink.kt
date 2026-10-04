package com.example.arruler.processing

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------- pure: pairing and URL policy

/**
 * Contents of the pairing QR shown by the PC server: {"v":1,"url":...,"token":...,"name":...} and optionally
 * "urls": [tailnet, lan, tunnel] in preference order. Older QR codes carry only "url"; newer ones may carry only
 * "urls" (then [url] becomes the first entry).
 */
@Serializable
data class PairingInfo(
    val v: Int,
    val url: String = "",
    val token: String,
    val name: String = "",
    val urls: List<String> = emptyList(),
) {
    /** Every candidate base URL in the order to try them: [urls] first, then [url] when it is not already listed. */
    fun allUrls(): List<String> = (urls + url).map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

    companion object {
        const val MIN_TOKEN = 32

        /** Parses and validates; returns a failure with a user-readable message. */
        fun parse(text: String): Result<PairingInfo> = runCatching {
            val p = try {
                ProcJson.json.decodeFromString<PairingInfo>(text.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a pairing code")
            }
            require(p.v == 1) { "Unsupported pairing version ${p.v}" }
            require(p.token.length >= MIN_TOKEN) { "Token is too short" }
            val all = p.allUrls()
            require(all.isNotEmpty()) { "Pairing code has no URL" }
            for (u in all) require(UrlPolicy.isAllowed(u)) { "URL must be https, or http to a private LAN or Tailscale address" }
            p.copy(url = all.first(), urls = if (p.urls.isEmpty()) emptyList() else all)
        }
    }
}

/**
 * HTTPS always; plain http only to a private or Tailscale address: IPv4 literals in 10/8, 172.16/12, 192.168/16
 * or the Tailscale CGNAT range 100.64/10, IPv6 literals in the Tailscale ULA fd7a:115c:a1e0::/48, a bare
 * single-label host name (a MagicDNS short name such as "rxmoi") or a name under .ts.net.
 */
object UrlPolicy {
    fun isAllowed(url: String): Boolean {
        val u = try { url.trim().toHttpUrl() } catch (e: Exception) { return false }
        if (u.isHttps) return true
        val h = u.host
        return isPrivateLanIp(h) || isTailscaleIpv4(h) || isTailscaleIpv6(h) || isTailnetName(h)
    }

    private fun ipv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val n = parts.map { s -> if (s.isNotEmpty() && s.length <= 3 && s.all { it.isDigit() }) s.toInt() else return null }
        return if (n.any { it > 255 }) null else n
    }

    fun isPrivateLanIp(host: String): Boolean {
        val n = ipv4(host) ?: return false
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }

    /** 100.64.0.0/10 = 100.64.0.0 to 100.127.255.255. */
    fun isTailscaleIpv4(host: String): Boolean {
        val n = ipv4(host) ?: return false
        return n[0] == 100 && n[1] in 64..127
    }

    /** fd7a:115c:a1e0::/48: the first three 16-bit groups of the (OkHttp-canonical, unbracketed) literal. */
    fun isTailscaleIpv6(host: String): Boolean {
        if (!host.contains(':') || !host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) return false
        val b = try { java.net.InetAddress.getByName(host).address } catch (e: Exception) { return false }
        if (b.size != 16) return false
        val want = intArrayOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)
        return want.indices.all { (b[it].toInt() and 0xff) == want[it] }
    }

    /** A bare single-label name (letters, digits, hyphen, at least one letter) or "<something>.ts.net". */
    fun isTailnetName(host: String): Boolean {
        val h = host.lowercase()
        if (h.endsWith(".ts.net")) return h.length > ".ts.net".length && h.split('.').all { it.isNotEmpty() }
        if (h.contains('.') || h.contains(':')) return false
        if (h == "localhost") return false
        return h.isNotEmpty() && h.all { it.isDigit() || it in 'a'..'z' || it == '-' } && h.any { it in 'a'..'z' } && !h.startsWith('-')
    }
}

/** The order [HttpPcLink] tries the pairing URLs in: the one that worked last first, the rest as listed. */
object UrlOrder {
    fun order(urls: List<String>, lastGood: String?): List<String> =
        if (lastGood != null && lastGood in urls) listOf(lastGood) + urls.filter { it != lastGood } else urls
}

// ---------------------------------------------------------------- API types

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val gpu: String = "",
    @SerialName("api_version") val apiVersion: Int = 1,
    @SerialName("max_upload_bytes") val maxUploadBytes: Long = 2L * 1024 * 1024 * 1024
)

sealed interface JobStatus {
    data class Queued(val position: Int = 0) : JobStatus
    data class Running(val progress: Float, val stage: String) : JobStatus
    data object Done : JobStatus
    data class Failed(val message: String) : JobStatus
    data object Cancelled : JobStatus
}

@Serializable
internal data class StatusDto(
    val id: String = "",
    val state: String,
    val progress: Float = 0f,
    val stage: String = "",
    val position: Int = 0,
    val error: String? = null
) {
    fun toStatus(): JobStatus = when (state) {
        "queued" -> JobStatus.Queued(position)
        "running" -> JobStatus.Running(progress.coerceIn(0f, 1f), stage)
        "done" -> JobStatus.Done
        "failed" -> JobStatus.Failed(error ?: "failed")
        "cancelled" -> JobStatus.Cancelled
        else -> JobStatus.Failed("unknown state '$state'")
    }
}

@Serializable
internal data class SubmitDto(val id: String)

@Serializable
internal data class ErrorBody(val code: String = "", val message: String = "")

@Serializable
internal data class ErrorEnvelope(val error: ErrorBody = ErrorBody())

class PcLinkException(val httpStatus: Int, val code: String, message: String, val network: Boolean = false) : Exception(message)

/** What [ProcessingService] needs from the PC; a fake implements it in tests. */
interface PcLink {
    suspend fun ping(): ServerInfo
    suspend fun submit(zip: File, type: JobType, onProgress: (Float) -> Unit = {}): String
    suspend fun status(jobId: String): JobStatus
    suspend fun download(jobId: String, dest: File, onProgress: (Float) -> Unit = {}): File
    suspend fun cancel(jobId: String)
}

// ---------------------------------------------------------------- pairing storage

interface PairingStore {
    fun load(): PairingInfo?
    fun save(info: PairingInfo)
    fun clear()
}

/**
 * Pairing in plain SharedPreferences "pc_pairing": url and name as text, the token only as an AES/GCM
 * frame ([TokenFraming]) made with the Android Keystore key [KeystoreTokenCipher.ALIAS]. If the key is
 * unusable [save] throws instead of storing the token in the clear, and [load] reports unpaired (and
 * forgets the entry) when the stored token can no longer be decrypted.
 */
class AndroidPairingStore(
    context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher(),
) : PairingStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("pc_pairing", Context.MODE_PRIVATE)

    override fun load(): PairingInfo? {
        val url = prefs.getString("url", null) ?: return null
        val framed = prefs.getString("token_enc", null) ?: return null
        val token = cipher.decrypt(framed)
        if (token == null) {
            clear()
            return null
        }
        val urls = prefs.getString("urls", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        return PairingInfo(1, url, token, prefs.getString("name", "") ?: "", urls)
    }

    override fun save(info: PairingInfo) {
        val framed = try {
            cipher.encrypt(info.token)
        } catch (e: Exception) {
            throw IllegalStateException("Secure storage (Android Keystore) is unavailable: ${e.message}", e)
        }
        prefs.edit().putString("url", info.url).putString("token_enc", framed).putString("name", info.name)
            .putString("urls", info.urls.joinToString("\n")).apply()
    }

    override fun clear() { prefs.edit().clear().apply() }
}

// ---------------------------------------------------------------- OkHttp implementation

class HttpPcLink(
    private val pairing: PairingInfo,
    client: OkHttpClient? = null
) : PcLink {
    private val bases: List<String> = pairing.allUrls().ifEmpty { listOf(pairing.url.trimEnd('/')) }
    private val memoryKey = bases.joinToString("|")

    /** The URL that answered last (this process); null until a call succeeded. */
    val activeUrl: String? get() = lastGood[memoryKey]

    private val http: OkHttpClient = (client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()).newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (!UrlPolicy.isAllowed(req.url.toString())) throw IOException("Blocked by URL policy: ${req.url.host}")
            chain.proceed(req.newBuilder().header("Authorization", "Bearer ${pairing.token}").build())
        })
        .build()

    override suspend fun ping(): ServerInfo =
        ProcJson.json.decodeFromString(execute { Request.Builder().url("$it/v1/ping").get().build() }.use { bodyText(it) })

    override suspend fun submit(zip: File, type: JobType, onProgress: (Float) -> Unit): String {
        val file = ProgressBody(zip, "application/zip".toMediaType(), onProgress)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("type", type.wire)
            .addFormDataPart("file", zip.name, file)
            .build()
        val resp = execute { Request.Builder().url("$it/v1/jobs").post(body).build() }
        return resp.use { ProcJson.json.decodeFromString<SubmitDto>(bodyText(it)).id }
    }

    override suspend fun status(jobId: String): JobStatus {
        val r = execute { Request.Builder().url("$it/v1/jobs/${enc(jobId)}").get().build() }
        return r.use { ProcJson.json.decodeFromString<StatusDto>(bodyText(it)).toStatus() }
    }

    override suspend fun download(jobId: String, dest: File, onProgress: (Float) -> Unit): File {
        val resp = execute { Request.Builder().url("$it/v1/jobs/${enc(jobId)}/result").get().build() }
        return writeBody(resp, dest, onProgress)
    }

    private fun writeBody(resp: Response, dest: File, onProgress: (Float) -> Unit): File {
        val part = File(dest.path + ".part")
        resp.use { r ->
            val body = r.body
            val total = body.contentLength()
            var done = 0L
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("cannot move download into place")
        return dest
    }

    override suspend fun cancel(jobId: String) {
        execute { Request.Builder().url("$it/v1/jobs/${enc(jobId)}").delete().build() }.close()
    }

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8")

    private fun bodyText(r: Response): String = r.body.string()

    /** Generic authenticated GET returning the body text (used by the debug Dev link). */
    suspend fun getText(path: String): String = execute { Request.Builder().url("$it$path").get().build() }.use { bodyText(it) }

    /** Generic authenticated multipart POST returning the body text (used by the debug Dev link). */
    suspend fun postMultipart(path: String, body: RequestBody): String =
        execute { Request.Builder().url("$it$path").post(body).build() }.use { bodyText(it) }

    /** Streams GET [path] into [dest] (via .part), reporting 0..1 progress. */
    suspend fun downloadTo(path: String, dest: File, onProgress: (Float) -> Unit = {}): File {
        val resp = execute { Request.Builder().url("$it$path").get().build() }
        return writeBody(resp, dest, onProgress)
    }

    /**
     * Runs the call against each candidate base URL in order (the last one that worked first), with a 2 s connect
     * timeout on every candidate but the final one. A network failure moves on to the next candidate; an HTTP answer
     * (even an error) is final, because it proves the PC was reached. Non-2xx becomes a [PcLinkException].
     */
    private suspend fun execute(build: (String) -> Request): Response {
        val order = UrlOrder.order(bases, lastGood[memoryKey])
        var failure: PcLinkException? = null
        for ((i, b) in order.withIndex()) {
            val client = if (i < order.size - 1) http.newBuilder().connectTimeout(FAST_CONNECT_S, TimeUnit.SECONDS).build() else http
            val resp = try {
                await(client.newCall(build(b)))
            } catch (e: IOException) {
                failure = PcLinkException(0, "network", e.message ?: "network error", network = true)
                continue
            }
            lastGood[memoryKey] = b
            if (resp.isSuccessful) return resp
            val text = resp.use { runCatching { it.body.string() }.getOrDefault("") }
            val err = runCatching { ProcJson.json.decodeFromString<ErrorEnvelope>(text).error }.getOrNull()
            throw PcLinkException(resp.code, err?.code?.ifEmpty { null } ?: "http_${resp.code}", err?.message?.ifEmpty { null } ?: "HTTP ${resp.code}")
        }
        throw failure ?: PcLinkException(0, "network", "no PC address", network = true)
    }

    companion object {
        const val FAST_CONNECT_S = 2L

        /** The URL of [p] that answered last in this process, or null when none has yet. */
        fun lastWorkingUrl(p: PairingInfo): String? = lastGood[p.allUrls().joinToString("|")]
        private val lastGood = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }

    private class ProgressBody(
        private val file: File,
        private val type: okhttp3.MediaType,
        private val onProgress: (Float) -> Unit
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1)
            var done = 0L
            file.source().use { src ->
                val buf = okio.Buffer()
                while (true) {
                    val n = src.read(buf, 64 * 1024)
                    if (n < 0) break
                    sink.write(buf, n)
                    done += n
                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
    }
}
