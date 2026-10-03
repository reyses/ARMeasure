package com.example.arruler.texture

import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session

/** What the ranking needs to know about one ARCore [CameraConfig] (CPU image size, fps range, depth-sensor use). */
data class ConfigInfo(
    val index: Int,
    val width: Int,
    val height: Int,
    val minFps: Int,
    val maxFps: Int,
    val usesDepthSensor: Boolean,
) {
    val pixels: Int get() = width * height
}

/**
 * Pure ranking of camera configs for photo capture: the largest CPU image up to [maxPixels] (bigger images cost
 * copy and JPEG time and the 1 s ARCore frame budget), at least [minFps] when any config reaches it, preferring
 * configs that do not need a hardware depth sensor, then the lower index (ARCore lists its default first).
 */
object CameraConfigRanking {
    const val DEFAULT_MAX_PIXELS = 1920 * 1080

    fun rank(configs: List<ConfigInfo>, maxPixels: Int = DEFAULT_MAX_PIXELS, minFps: Int = 30): List<ConfigInfo> {
        val fast = configs.filter { it.maxFps >= minFps }
        val pool = if (fast.isNotEmpty()) fast else configs
        return pool.sortedWith(
            compareByDescending<ConfigInfo> { minOf(it.pixels, maxPixels) }
                .thenBy { if (it.pixels > maxPixels) it.pixels else 0 }
                .thenBy { it.usesDepthSensor }
                .thenBy { it.index }
        )
    }
}

/**
 * Picks the camera config for the AR session. Use as SceneView's `sessionCameraConfig = CameraConfigChooser::select`
 * (it runs before the session resumes, which is the only time ARCore accepts a config change). Walks the ranked
 * configs, applies each and keeps the first for which `isDepthModeSupported(AUTOMATIC)` is true, so the depth the
 * scan relies on survives; otherwise falls back to ARCore's default. Device behaviour: see docs/TEXTURE.md.
 */
object CameraConfigChooser {
    fun select(session: Session, maxPixels: Int = CameraConfigRanking.DEFAULT_MAX_PIXELS): CameraConfig {
        val original = session.cameraConfig
        val configs = try {
            session.getSupportedCameraConfigs(CameraConfigFilter(session).setFacingDirection(CameraConfig.FacingDirection.BACK))
        } catch (e: Exception) {
            return original
        }
        val infos = configs.mapIndexed { i, c ->
            ConfigInfo(i, c.imageSize.width, c.imageSize.height, c.fpsRange.lower, c.fpsRange.upper,
                c.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE)
        }
        for (info in CameraConfigRanking.rank(infos, maxPixels).take(4)) {
            val cfg = configs[info.index]
            try {
                session.cameraConfig = cfg
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) return cfg
            } catch (e: Exception) {
                // try the next candidate
            }
        }
        session.cameraConfig = original
        return original
    }
}
