package com.example.arruler.tandem

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.arruler.processing.DeviceProfile

/**
 * Reads the live thermal state into [PeerCaps] (thermal status API 29+, forecast headroom API 30+). Call it before planning and
 * between tasks; send the result with [TandemHelper.reportStatus] so the leader re-plans from fresh numbers.
 */
object ThermalSampler {
    /** [forecastSeconds] is how far ahead getThermalHeadroom looks (the platform allows 0..60). */
    fun headroom(context: Context, forecastSeconds: Int = 10): Float? {
        if (Build.VERSION.SDK_INT < 30) return null
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val h = pm.getThermalHeadroom(forecastSeconds)
        return if (h.isNaN()) null else h
    }

    fun caps(context: Context, name: String, profile: DeviceProfile, depthSupported: Boolean = true, measuredFactor: Double = 1.0): PeerCaps =
        PeerCaps(
            name, profile.tier, profile.signals.benchMs, profile.thermalStatus, headroom(context),
            profile.batteryPercent, profile.charging, depthSupported, measuredFactor,
        )
}
