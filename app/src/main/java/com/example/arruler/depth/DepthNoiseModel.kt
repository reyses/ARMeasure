package com.example.arruler.depth

/**
 * Per-pixel depth error of ARCore depth-from-motion as a function of the observed depth z (meters):
 * sigma(z) = [a] + [b] * z^2 (stereo-like: the disparity error is roughly constant, so the depth error grows
 * with z^2). sigma(1 m) = 3.5 cm, sigma(2.3 m) = 16 cm, sigma(4 m) = 49 cm with the defaults.
 *
 * Calibrated on the owner's first real scan (Pixel, 2026-10-03, fixture real_scan_2026-10-03_surfaces.obj):
 * standing near the session origin and turning, one wall ~2.3 m away came out of the old extractor (fixed 2 cm
 * RANSAC band) as 7 parallel slices, tilted 3-17 deg, whose offsets along their own normals spread 2.14-2.42 m
 * (0.28 m). The test simulator ([b] here, plus a per-frame scale error of +-3 % and pose tilt of +-2 deg, 2 %
 * speckle) pushed through the same voxel cloud and old extractor reproduces 5-7 slices spreading 0.20-0.29 m
 * at b = 0.03 (0.16-0.21 m at b = 0.02, 0.21-0.31 m at b = 0.04, both with +-1.5 deg tilt). [a] = 5 mm is the near-range floor.
 *
 * The extractor does not trust the model blindly: it rescales it from the data when the cloud is noisier
 * (PlaneExtractor.noiseScale), because depth quality depends on how the phone moved.
 */
data class DepthNoiseModel(val a: Float = 0.005f, val b: Float = 0.030f) {
    init { require(a >= 0f && b >= 0f) }

    /** One-sigma depth error in meters at depth [z] meters. */
    fun sigma(z: Float): Float = a + b * z * z

    companion object {
        val DEFAULT = DepthNoiseModel()
    }
}
