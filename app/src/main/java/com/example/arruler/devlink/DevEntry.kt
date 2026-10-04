package com.example.arruler.devlink

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import com.example.arruler.processing.PairingInfo

/**
 * The only door from the app into the developer link (update-from-PC, logs to PC). [DevEntries.entry] is defined
 * twice, once in src/debug and once in src/release; the release one does nothing and the real code exists only in
 * the debug source set, so a release APK contains no dev link at all.
 */
interface DevEntry {
    /** False in release builds: Settings then shows no Dev section. */
    val enabled: Boolean

    /** Called once from MainActivity.onCreate (debug: installs the crash recorder). */
    fun onCreate(app: Application)

    /** Called at launch and whenever the pairing changes (debug: uploads a crash recorded by the previous run). */
    fun onPairing(context: Context, pairing: PairingInfo?)

    /**
     * A failure that was caught at an action boundary (or reached a coroutine exception handler): debug records it to the
     * same crash file as an uncaught crash, so it is uploaded at the next pairing; release does nothing.
     */
    fun recordError(context: Context, where: String, error: Throwable)

    /** Debug and paired: uploads [text] to the PC as a crash report and returns the line to show; otherwise null (share it instead). */
    suspend fun sendReport(context: Context, pairing: PairingInfo?, text: String): String?

    /** The body of Settings > Dev (debug only). */
    @Composable
    fun SettingsSection(pairing: PairingInfo?)
}
