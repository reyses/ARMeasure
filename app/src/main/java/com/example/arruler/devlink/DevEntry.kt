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

    /** The body of Settings > Dev (debug only). */
    @Composable
    fun SettingsSection(pairing: PairingInfo?)
}
