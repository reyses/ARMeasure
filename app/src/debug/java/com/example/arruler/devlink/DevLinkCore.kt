package com.example.arruler.devlink

import com.example.arruler.processing.UrlPolicy
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Pure parts of the debug dev link (no Android types, JVM-tested). See docs/DEV_LINK.md for the wire contract.

/** GET /v1/dev/apk?package=<applicationId> answer. [url] is a server-relative path under /v1/dev/apk/. */
@Serializable
data class ApkInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val commit: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val url: String = "",
    /** Optional: the APK file's modification time (epoch seconds or ms) when the server sends it; 0 = unknown. */
    val mtime: Long = 0,
)

sealed interface UpdateCheck {
    /** The installed build is the one the PC offers. */
    data class UpToDate(val commit: String) : UpdateCheck

    data class Available(val info: ApkInfo) : UpdateCheck

    /** The server answer cannot be acted on (no commit, no url, no hash, or an unsafe path). */
    data class Unusable(val why: String) : UpdateCheck
}

object UpdateDecision {
    const val UNKNOWN = "unknown"
    private const val MIN_COMMIT = 7

    /** Short git hashes of possibly different lengths denote the same commit when the shorter is a prefix of the longer (>= 7 chars). */
    fun sameCommit(a: String, b: String): Boolean {
        val x = a.trim().lowercase()
        val y = b.trim().lowercase()
        if (x.length < MIN_COMMIT || y.length < MIN_COMMIT || x == UNKNOWN || y == UNKNOWN) return false
        val n = minOf(x.length, y.length)
        return x.regionMatches(0, y, 0, n)
    }

    /** Only a server path under /v1/dev/apk/ with no traversal is ever fetched. */
    fun isSafeApkPath(path: String): Boolean =
        path.startsWith("/v1/dev/apk/") && path.length > "/v1/dev/apk/".length &&
            !path.contains("..") && !path.contains('\\') && !path.contains("//")

    /**
     * Debug builds all carry versionCode 1, so the embedded git commit decides: a higher versionCode is newer; otherwise a
     * different commit is offered (the server returns its newest build by modification time); the same commit is up to date.
     * An installed commit of "unknown" can never be proven equal, so the offer stands.
     */
    fun decide(installedCommit: String, installedVersionCode: Int, remote: ApkInfo): UpdateCheck {
        if (!isSafeApkPath(remote.url)) return UpdateCheck.Unusable("server sent an unusable download path")
        if (remote.sha256.isBlank()) return UpdateCheck.Unusable("server sent no sha256")
        if (remote.versionCode > installedVersionCode) return UpdateCheck.Available(remote)
        if (remote.commit.isBlank()) return UpdateCheck.Unusable("server reported no commit")
        return if (sameCommit(installedCommit, remote.commit)) UpdateCheck.UpToDate(remote.commit) else UpdateCheck.Available(remote)
    }

    fun upToDateText(commit: String) = "Up to date (commit $commit)"
}

/** Pure decisions of the automatic update check (banner, throttle, mobile-data prompt). JVM-tested. */
object AutoUpdate {
    const val MIN_INTERVAL_MS = 30L * 60 * 1000

    /** At most one check per [intervalMs]; a clock that went backwards allows one. */
    fun shouldCheck(nowMs: Long, lastCheckMs: Long, intervalMs: Long = MIN_INTERVAL_MS): Boolean =
        lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs

    fun short(commit: String): String = commit.trim().take(7)

    fun bannerText(installedCommit: String, remoteCommit: String): String =
        "Update available (${short(installedCommit)} → ${short(remoteCommit)}) · Install"

    /** A server time in seconds or milliseconds as milliseconds; 0 when unknown. */
    fun toMillis(v: Long): Long = when {
        v <= 0L -> 0L
        v < 100_000_000_000L -> v * 1000
        else -> v
    }

    /** The offered file counts as newer unless both times are known and it is not after the installed build's install time. */
    fun isNewerFile(remoteMtime: Long, installedUpdatedMs: Long): Boolean {
        val r = toMillis(remoteMtime)
        return r == 0L || installedUpdatedMs <= 0L || r > installedUpdatedMs
    }

    sealed interface Offer {
        data class Show(val info: ApkInfo) : Offer
        data class None(val why: String) : Offer
    }

    /** Whether to show the banner: a different commit (or higher versionCode), a newer file, and not dismissed by the user. */
    fun offer(installedCommit: String, installedVersionCode: Int, installedUpdatedMs: Long, remote: ApkInfo, dismissedCommit: String?): Offer =
        when (val d = UpdateDecision.decide(installedCommit, installedVersionCode, remote)) {
            is UpdateCheck.UpToDate -> Offer.None("up to date")
            is UpdateCheck.Unusable -> Offer.None(d.why)
            is UpdateCheck.Available -> when {
                !isNewerFile(remote.mtime, installedUpdatedMs) -> Offer.None("the PC's file is older than this install")
                dismissedCommit != null && UpdateDecision.sameCommit(dismissedCommit, remote.commit) -> Offer.None("dismissed")
                else -> Offer.Show(d.info)
            }
        }

    /** "67 MB" (binary megabytes, rounded, at least 1). */
    fun sizeText(bytes: Long): String = "${maxOf(1L, (bytes + 512 * 1024) / (1024 * 1024))} MB"

    fun mobilePrompt(bytes: Long): String = "Download ${sizeText(bytes)} on mobile data?"

    sealed interface Gate {
        data object Go : Gate
        data class AskMobile(val prompt: String) : Gate
        data object NoNetwork : Gate
    }

    /** [metered]: true on mobile data, false on an unmetered network, null when offline. */
    fun downloadGate(metered: Boolean?, alwaysAllowMobile: Boolean, sizeBytes: Long): Gate = when {
        metered == null -> Gate.NoNetwork
        !metered || alwaysAllowMobile -> Gate.Go
        else -> Gate.AskMobile(mobilePrompt(sizeBytes))
    }
}

object Sha256 {
    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    fun matches(expected: String, actual: String): Boolean = expected.isNotBlank() && expected.trim().equals(actual.trim(), ignoreCase = true)

    /** True when [file] has the announced size (when given) and sha256. */
    fun verify(file: File, expectedSha256: String, expectedSize: Long): Boolean =
        file.isFile && (expectedSize <= 0 || file.length() == expectedSize) && matches(expectedSha256, hex(file))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Keeps the last [max] bytes written, trimming lazily so a long logcat never sits fully in memory. */
class TailBuffer(private val max: Int) {
    private var buf = ByteArray(0)
    private var len = 0

    fun write(b: ByteArray, off: Int = 0, n: Int = b.size) {
        if (buf.size < len + n) buf = buf.copyOf(maxOf(len + n, minOf(buf.size * 2, 2 * max + n)))
        System.arraycopy(b, off, buf, len, n)
        len += n
        if (len > 2 * max) {
            System.arraycopy(buf, len - max, buf, 0, max)
            len = max
        }
    }

    /** The last up-to-[max] bytes, cut forward to the next line start when something was dropped. */
    fun bytes(): ByteArray {
        if (len <= max) return buf.copyOf(len)
        var start = len - max
        val nl = (start until len).firstOrNull { buf[it] == NEWLINE }
        if (nl != null && nl + 1 < len) start = nl + 1
        return buf.copyOfRange(start, len)
    }

    private companion object {
        const val NEWLINE: Byte = 10
    }
}

/** Multipart assembly for POST /v1/dev/logs: fields device, app, commit, kind (logs|crash|diagnostics) and file. */
object DevUpload {
    val KINDS = listOf("logs", "crash", "diagnostics")
    const val MAX_LOG_BYTES = 2 * 1024 * 1024

    fun fields(device: String, app: String, commit: String, kind: String): List<Pair<String, String>> {
        require(kind in KINDS) { "unknown kind '$kind'" }
        return listOf("device" to device, "app" to app, "commit" to commit, "kind" to kind)
    }

    fun fileName(kind: String, commit: String, nowMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))
        return "$kind-$commit-$stamp.txt"
    }

    fun body(fields: List<Pair<String, String>>, fileName: String, content: ByteArray): MultipartBody {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        for ((k, v) in fields) b.addFormDataPart(k, v)
        b.addFormDataPart("file", fileName, content.toRequestBody("text/plain; charset=utf-8".toMediaType()))
        return b.build()
    }

    @Serializable
    data class Receipt(val id: String = "")
}

/** Which kind of pairing URL is in use, for the Settings line. */
object UrlKind {
    fun label(url: String): String {
        val host = try { url.trim().toHttpUrl().host } catch (e: Exception) { return "unknown" }
        return when {
            UrlPolicy.isTailscaleIpv4(host) || UrlPolicy.isTailscaleIpv6(host) || UrlPolicy.isTailnetName(host) -> "tailnet"
            UrlPolicy.isPrivateLanIp(host) -> "LAN"
            else -> "tunnel"
        }
    }
}

/** The crash recorder's file: filesDir/crash/last.txt, written by the uncaught-exception handler, sent on the next launch. */
class CrashStore(private val dir: File) {
    private val file get() = File(dir, "last.txt")

    fun write(thread: String, t: Throwable, nowMs: Long, commit: String) {
        dir.mkdirs()
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))
        file.writeText("crash at $stamp\ncommit $commit\nthread $thread\n\n$sw")
    }

    /** The recorded crash text, or null when there is none. */
    fun pending(): String? = file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear() { file.delete() }
}
