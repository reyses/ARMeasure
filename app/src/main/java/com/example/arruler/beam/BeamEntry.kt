package com.example.arruler.beam

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import com.example.arruler.processing.PairingInfo
import java.io.File

/**
 * The only door from the app into Beam (diagnostics, screen snapshots and video to the owner's PC). [BeamEntries.entry] is
 * defined twice, once in src/debug (the real thing) and once in src/release (does nothing), exactly like devlink/DevEntries.
 * App code calls the [Beam] facade below; in a release build every call returns at once and nothing is recorded.
 * See docs/BEAM.md for the call sites.
 */
interface BeamEntry {
    /** False in release builds. */
    val enabled: Boolean

    /** MainActivity.onCreate, BEFORE DevEntries.entry.onPairing (Beam copies the previous run's crash file first). */
    fun onCreate(app: Application)

    /** At launch and whenever the pairing changes (debug: kicks the upload queue). */
    fun onPairing(context: Context, pairing: PairingInfo?)

    /** Appends one event to the open sessions' timelines. Safe from any thread, never throws. */
    fun event(type: String, data: Map<String, Any?>)

    /** Opens a Scan / Object run session (kind e.g. "scan", "object"); returns its id, or "" when Beam is off. */
    fun startRun(kind: String, label: String): String

    /** Closes the run session and queues its bundle; [reason] e.g. "analyze_done", "finish", "stop", "cancel". */
    fun endRun(reason: String)

    /** Registers a file produced during the open sessions (role: arcore_recording, export, ...). It is read at bundle time. */
    fun attach(role: String, file: File)

    /** One window snapshot now, labelled [reason] (also taken automatically on every error event). */
    fun snapNow(reason: String)

    /** The body of the Settings > Beam to PC section (debug only). */
    @Composable
    fun SettingsSection(pairing: PairingInfo?)
}

/** Facade used by app code. No behaviour of its own: it forwards to [BeamEntries.entry] and swallows every failure. */
object Beam {
    private val entry: BeamEntry get() = BeamEntries.entry

    fun event(type: String, vararg data: Pair<String, Any?>) {
        try {
            if (entry.enabled) entry.event(type, if (data.isEmpty()) emptyMap() else data.toMap())
        } catch (_: Throwable) {
        }
    }

    fun startRun(kind: String, label: String = ""): String = try { if (entry.enabled) entry.startRun(kind, label) else "" } catch (_: Throwable) { "" }

    fun endRun(reason: String) {
        try { if (entry.enabled) entry.endRun(reason) } catch (_: Throwable) { }
    }

    fun attach(role: String, file: File) {
        try { if (entry.enabled) entry.attach(role, file) } catch (_: Throwable) { }
    }

    fun snapNow(reason: String) {
        try { if (entry.enabled) entry.snapNow(reason) } catch (_: Throwable) { }
    }
}
