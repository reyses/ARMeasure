package com.example.arruler.geometry

import kotlin.math.roundToInt

/**
 * The one red -> yellow -> green ramp of the app (red at 0, yellow at 0.5, green at 1), 0..255 channels.
 * Shared by the AR depth-confidence heatmap and the 3D scan quality colours; pure, no Android/SceneView types.
 */
object ColorRamp {
    fun r(t: Float): Int = (255 * (2f * (1f - t.coerceIn(0f, 1f))).coerceAtMost(1f)).roundToInt()
    fun g(t: Float): Int = (255 * (2f * t.coerceIn(0f, 1f)).coerceAtMost(1f)).roundToInt()

    /** Packed 0xRRGGBB. */
    fun rgb(t: Float): Int = (r(t) shl 16) or (g(t) shl 8)

    /** Packed 0xAARRGGBB with [alpha] 0..255. */
    fun argb(t: Float, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or rgb(t)
}
