package com.example.arruler.tandem

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Runtime permissions Nearby Connections needs, per API level (checked against the Nearby Connections
 * "get started" manifest, 2026-10): the manifest entries are in docs/TANDEM.md.
 *
 *  - API 37+ (target 37): ACCESS_LOCAL_NETWORK for the Wi-Fi LAN medium, plus the API 33+ set.
 *  - API 33+: BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, NEARBY_WIFI_DEVICES.
 *  - API 31-32: the three BLUETOOTH_* permissions and ACCESS_FINE_LOCATION.
 *  - API 29-30: ACCESS_FINE_LOCATION (the legacy BLUETOOTH / BLUETOOTH_ADMIN are install-time).
 *  - API 24-28: ACCESS_COARSE_LOCATION.
 * ACCESS_WIFI_STATE / CHANGE_WIFI_STATE are normal permissions (no prompt).
 */
object PeerPermissions {
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Permissions to request at runtime on a device running [sdkInt]. Order is stable. */
    fun required(sdkInt: Int): List<String> {
        val out = ArrayList<String>()
        if (sdkInt >= 31) {
            out += BLUETOOTH_SCAN; out += BLUETOOTH_ADVERTISE; out += BLUETOOTH_CONNECT
        }
        if (sdkInt >= 33) out += NEARBY_WIFI_DEVICES
        if (sdkInt >= 37) out += LOCAL_NETWORK
        if (sdkInt in 29..32) out += FINE_LOCATION
        if (sdkInt in 1..28) out += COARSE_LOCATION
        return out
    }

    /** The subset of [required] not yet granted. */
    fun missing(context: Context, sdkInt: Int = android.os.Build.VERSION.SDK_INT): List<String> =
        required(sdkInt).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /** Pure core of [missing] for tests. */
    fun missing(sdkInt: Int, granted: Set<String>): List<String> = required(sdkInt).filter { it !in granted }
}
