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
    /** Bundles above [UploadGate.BIG_BYTES] may use mobile data (default off). Small ones always may, over a tailnet / LAN URL. */
    val allowLargeOnMobile: Boolean = false,
)

/** Why uploading may not start now (null = allowed). Pure. */
object UploadGate {
    /** URLs of [pairingUrls] Beam may use: tailnet and LAN always, the public tunnel only with "Allow over tunnel". */
    fun allowedUrls(policy: BeamPolicy, pairingUrls: List<String>): List<String> =
        pairingUrls.filter { policy.allowOverTunnel || UrlKind.label(it) != "tunnel" }

    const val BIG_BYTES = 20L * 1024 * 1024

    /**
     * URLs one bundle of [bytes] may use right now. [metered]: true on mobile data, false on Wi-Fi / unmetered, null no network.
     * On mobile data a small bundle (<= [BIG_BYTES]) may use a tailnet / LAN URL even with "Wi-Fi only"; a large one needs
     * "Allow large uploads on mobile data" (or Wi-Fi only switched off). The public tunnel on mobile data needs Wi-Fi only off.
     */
    fun urlsFor(policy: BeamPolicy, pairingUrls: List<String>, bytes: Long, metered: Boolean?): List<String> {
        if (metered == null) return emptyList()
        val allowed = allowedUrls(policy, pairingUrls)
        if (!metered || !policy.wifiOnly) return allowed
        val small = bytes <= BIG_BYTES
        return allowed.filter { UrlKind.label(it) != "tunnel" && (small || policy.allowLargeOnMobile) }
    }

    /** True when a bundle of [bytes] would have to wait for unmetered Wi-Fi under [policy]. */
    fun needsWifi(policy: BeamPolicy, bytes: Long): Boolean =
        policy.wifiOnly && !policy.allowLargeOnMobile && bytes > BIG_BYTES

    /**
     * Why a bundle of [bytes] (0 = a small one) may not upload now, for the Settings status line; null = allowed.
     */
    fun blockedReason(policy: BeamPolicy, paired: Boolean, pairingUrls: List<String>, metered: Boolean?, bytes: Long = 0): String? = when {
        !policy.beamToPc -> "Beam to PC is off"
        !paired -> "no PC paired"
        metered == null -> "no network"
        allowedUrls(policy, pairingUrls).isEmpty() -> "only the public tunnel URL is paired and 'Allow over tunnel' is off"
        urlsFor(policy, pairingUrls, bytes, metered).isEmpty() ->
            if (bytes > BIG_BYTES) "waiting for Wi-Fi (bundle over 20 MB, 'Allow large uploads on mobile data' is off)"
            else "waiting for Wi-Fi (Wi-Fi only is on and no tailnet / LAN address is paired)"
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
                allowLargeOnMobile = prefs.getBoolean(K_LARGE, d.allowLargeOnMobile),
            )
        } catch (_: Throwable) {
            d
        }
    }

    fun write(p: BeamPolicy) {
        prefs.edit().putBoolean(K_BEAM, p.beamToPc).putBoolean(K_WIFI, p.wifiOnly).putBoolean(K_TUNNEL, p.allowOverTunnel)
            .putBoolean(K_VIDEO, p.includeCameraVideo).putBoolean(K_SNAPS, p.includeScreenSnapshots)
            .putBoolean(K_FULL, p.fullScreenRecording).putBoolean(K_LARGE, p.allowLargeOnMobile).apply()
    }

    private companion object {
        const val K_BEAM = "beam"
        const val K_WIFI = "wifi_only"
        const val K_TUNNEL = "over_tunnel"
        const val K_VIDEO = "video"
        const val K_SNAPS = "snaps"
        const val K_FULL = "full_record"
        const val K_LARGE = "large_mobile"
    }
}
