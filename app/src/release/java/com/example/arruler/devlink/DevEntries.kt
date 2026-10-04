package com.example.arruler.devlink

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import com.example.arruler.processing.PairingInfo

/** Release build: there is no dev link. */
object DevEntries {
    val entry: DevEntry = object : DevEntry {
        override val enabled = false
        override fun onCreate(app: Application) = Unit
        override fun onPairing(context: Context, pairing: PairingInfo?) = Unit
        override fun recordError(context: Context, where: String, error: Throwable) = Unit
        override suspend fun sendReport(context: Context, pairing: PairingInfo?, text: String): String? = null

        @Composable
        override fun UpdateBanner() = Unit

        @Composable
        override fun SettingsSection(pairing: PairingInfo?) = Unit
    }
}
