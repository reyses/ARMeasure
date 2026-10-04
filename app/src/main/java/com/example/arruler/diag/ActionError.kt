package com.example.arruler.diag

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A failure of one user action (Analyze, View 3D) caught at its boundary: the card shows [reason], 'Send report' sends
 * [report] (dev link upload in debug builds, the share sheet otherwise).
 */
class ActionError(val action: String, val reason: String, val report: String)

/** Pure text of an [ActionError]. */
object ActionErrors {
    const val MAX_REASON = 140

    /** One line: the exception's simple class name and message (the root cause's when it adds one), at most [MAX_REASON] chars. */
    fun shortReason(t: Throwable): String {
        var root = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        fun line(e: Throwable) = e.javaClass.simpleName.ifEmpty { "Error" } + (e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        val text = if (root === t) line(t) else "${line(t)} (${line(root)})"
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_REASON) flat else flat.take(MAX_REASON - 3) + "..."
    }

    /** The report: [header] (app, commit, device), the action, the UTC time and the full stack trace. */
    fun report(action: String, t: Throwable, header: String, nowMs: Long): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))
        return "$header\naction $action at $stamp\nreason ${shortReason(t)}\n\n$sw"
    }

    fun of(action: String, t: Throwable, header: String, nowMs: Long): ActionError =
        ActionError(action, shortReason(t), report(action, t, header, nowMs))
}
