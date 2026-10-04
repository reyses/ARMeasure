package com.example.arruler.beam

import android.content.Context
import android.content.SharedPreferences
import com.example.arruler.devlink.UrlKind

/** The Settings switches of Beam. Defaults follow the owner's brief; beam is only active when a PC is paired. */
data class BeamPolicy(
    val beamToPc: Boolean = true,
    val wifiOnly: Boolean = true,
    val allowOverTunnel: Boolean = false,
    val includeCameraVideo: Boolean = true,
    val includeScreenSnapshots: Boolean = true,
    val fullScreenRecording: Boolean = false,
)

/** Why uploading may not start now (null = allowed). Pure. */
object UploadGate {
    /** URLs of [pairingUrls] Beam may use: tailnet and LAN always, the public tunnel only with "Allow over tunnel". */
    fun allowedUrls(policy: BeamPolicy, pairingUrls: List<String>): List<String> =
        pairingUrls.filter { policy.allowOverTunnel || UrlKind.label(it) != "tunnel" }

    /**
     * [metered]: true / false from the active network's NET_CAPABILITY_NOT_METERED, null when there is no network.
     * Returns null when an upload may run, otherwise the reason shown in Settings.
     */
    fun blockedReason(policy: BeamPolicy, paired: Boolean, pairingUrls: List<String>, metered: Boolean?): String? = when {
        !policy.beamToPc -> "Beam to PC is off"
        !paired -> "no PC paired"
        metered == null -> "no network"
        policy.wifiOnly && metered -> "waiting for Wi-Fi (Wi-Fi only is on)"
        allowedUrls(policy, pairingUrls).isEmpty() -> "only the public tunnel URL is paired and 'Allow over tunnel' is off"
        else -> null
    }
}

/** SharedPreferences "beam_settings". Every read falls back to the default; nothing here throws. */
class BeamSettings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("beam_settings", Context.MODE_PRIVATE)

    fun read(): BeamPolicy {
        val d = BeamPolicy()
        return try {
            BeamPolicy(
                beamToPc = prefs.getBoolean(K_BEAM, d.beamToPc),
                wifiOnly = prefs.getBoolean(K_WIFI, d.wifiOnly),
                allowOverTunnel = prefs.getBoolean(K_TUNNEL, d.allowOverTunnel),
                includeCameraVideo = prefs.getBoolean(K_VIDEO, d.includeCameraVideo),
                includeScreenSnapshots = prefs.getBoolean(K_SNAPS, d.includeScreenSnapshots),
                fullScreenRecording = prefs.getBoolean(K_FULL, d.fullScreenRecording),
            )
        } catch (_: Throwable) {
            d
        }
    }

    fun write(p: BeamPolicy) {
        prefs.edit().putBoolean(K_BEAM, p.beamToPc).putBoolean(K_WIFI, p.wifiOnly).putBoolean(K_TUNNEL, p.allowOverTunnel)
            .putBoolean(K_VIDEO, p.includeCameraVideo).putBoolean(K_SNAPS, p.includeScreenSnapshots)
            .putBoolean(K_FULL, p.fullScreenRecording).apply()
    }

    private companion object {
        const val K_BEAM = "beam"
        const val K_WIFI = "wifi_only"
        const val K_TUNNEL = "over_tunnel"
        const val K_VIDEO = "video"
        const val K_SNAPS = "snaps"
        const val K_FULL = "full_record"
    }
}
