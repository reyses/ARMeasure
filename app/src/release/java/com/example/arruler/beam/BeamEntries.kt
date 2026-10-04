package com.example.arruler.beam

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import com.example.arruler.processing.PairingInfo
import java.io.File

/** Release build: there is no Beam. Nothing is recorded, nothing is uploaded. */
object BeamEntries {
    val entry: BeamEntry = object : BeamEntry {
        override val enabled = false
        override fun onCreate(app: Application) = Unit
        override fun onPairing(context: Context, pairing: PairingInfo?) = Unit
        override fun event(type: String, data: Map<String, Any?>) = Unit
        override fun startRun(kind: String, label: String) = ""
        override fun endRun(reason: String) = Unit
        override fun attach(role: String, file: File) = Unit
        override fun snapNow(reason: String) = Unit

        @Composable
        override fun SettingsSection(pairing: PairingInfo?) = Unit
    }
}
