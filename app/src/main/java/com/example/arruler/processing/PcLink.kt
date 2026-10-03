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

/** Contents of the pairing QR shown by the PC server: {"v":1,"url":...,"token":...,"name":...}. */
@Serializable
data class PairingInfo(val v: Int, val url: String, val token: String, val name: String = "") {
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
            require(UrlPolicy.isAllowed(p.url)) { "URL must be https, or http to a private LAN address" }
            p.copy(url = p.url.trim().trimEnd('/'))
        }
    }
}

/** HTTPS always; plain http only to an IPv4 literal in 10/8, 172.16/12 or 192.168/16. */
object UrlPolicy {
    fun isAllowed(url: String): Boolean {
        val u = try { url.trim().toHttpUrl() } catch (e: Exception) { return false }
        if (u.isHttps) return true
        return isPrivateLanIp(u.host)
    }

    fun isPrivateLanIp(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val n = parts.map { s -> if (s.isNotEmpty() && s.length <= 3 && s.all { it.isDigit() }) s.toInt() else return false }
        if (n.any { it > 255 }) return false
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }
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
        return PairingInfo(1, url, token, prefs.getString("name", "") ?: "")
    }

    override fun save(info: PairingInfo) {
        val framed = try {
            cipher.encrypt(info.token)
        } catch (e: Exception) {
            throw IllegalStateException("Secure storage (Android Keystore) is unavailable: ${e.message}", e)
        }
        prefs.edit().putString("url", info.url).putString("token_enc", framed).putString("name", info.name).apply()
    }

    override fun clear() { prefs.edit().clear().apply() }
}

// ---------------------------------------------------------------- OkHttp implementation

class HttpPcLink(
    private val pairing: PairingInfo,
    client: OkHttpClient? = null
) : PcLink {
    private val base = pairing.url.trimEnd('/')

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
        ProcJson.json.decodeFromString(execute(Request.Builder().url("$base/v1/ping").get().build()).use { bodyText(it) })

    override suspend fun submit(zip: File, type: JobType, onProgress: (Float) -> Unit): String {
        val file = ProgressBody(zip, "application/zip".toMediaType(), onProgress)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("type", type.wire)
            .addFormDataPart("file", zip.name, file)
            .build()
        val resp = execute(Request.Builder().url("$base/v1/jobs").post(body).build())
        return resp.use { ProcJson.json.decodeFromString<SubmitDto>(bodyText(it)).id }
    }

    override suspend fun status(jobId: String): JobStatus {
        val r = execute(Request.Builder().url("$base/v1/jobs/${enc(jobId)}").get().build())
        return r.use { ProcJson.json.decodeFromString<StatusDto>(bodyText(it)).toStatus() }
    }

    override suspend fun download(jobId: String, dest: File, onProgress: (Float) -> Unit): File {
        val resp = execute(Request.Builder().url("$base/v1/jobs/${enc(jobId)}/result").get().build())
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
        execute(Request.Builder().url("$base/v1/jobs/${enc(jobId)}").delete().build()).close()
    }

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8")

    private fun bodyText(r: Response): String = r.body.string()

    /** Runs the call; non-2xx becomes a [PcLinkException] carrying the server's error code. */
    private suspend fun execute(req: Request): Response {
        val resp = try {
            await(http.newCall(req))
        } catch (e: IOException) {
            throw PcLinkException(0, "network", e.message ?: "network error", network = true)
        }
        if (resp.isSuccessful) return resp
        val text = resp.use { runCatching { it.body.string() }.getOrDefault("") }
        val err = runCatching { ProcJson.json.decodeFromString<ErrorEnvelope>(text).error }.getOrNull()
        throw PcLinkException(resp.code, err?.code?.ifEmpty { null } ?: "http_${resp.code}", err?.message?.ifEmpty { null } ?: "HTTP ${resp.code}")
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
