package com.example.arruler.texture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Spin capture: the phone stands still and the object turns (turntable, lazy-susan, by hand).
 * Keyframes are chosen by IMAGE CHANGE inside the box's projected region, not by camera angle.
 */
data class SpinConfig(
    val maxPerTurn: Int = 72,
    val turns: Int = 2,
    /**
     * A new keyframe is kept when the mean absolute luma difference inside the box ROI against the last kept keyframe
     * exceeds this fraction of that keyframe's own ROI contrast (mean absolute deviation of the ROI luma from its mean,
     * floored at [minContrast] levels so a flat or noisy object cannot trigger on sensor noise). Normalising by contrast
     * makes the rule independent of how contrasty the object is. See docs/TEXTURE.md for the derivation.
     */
    val roiDiffThreshold: Float = DEFAULT_ROI_DIFF,
    val minContrast: Float = 4f,
    val minSharpnessRatio: Double = 0.6,
    val medianWindow: Int = 40,
    val minMedianSamples: Int = 5,
    /** PhoneMoved when the camera pose drifts more than this from the pose at spin start. */
    val moveToleranceM: Float = 0.01f,
    val moveToleranceDeg: Double = 1.5,
) {
    companion object {
        /** Fraction of the ROI contrast; derived on a synthetic rotating textured 200 mm cube at 40 cm (docs/TEXTURE.md). */
        const val DEFAULT_ROI_DIFF = 0.65f
    }
}

/** Grid of luma cells covering the box's projected hull; [cellValid] marks cells whose centre is inside the hull. */
class SpinRoi(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val grid: Int, val cellValid: BooleanArray) {
    val validCount: Int get() = cellValid.count { it }

    /** Mean luma per grid cell (cell block averaged on a stride of at most 4 px) from the Y plane. */
    fun signature(y: ByteArray, rowStride: Int, pixelStride: Int = 1): FloatArray {
        val out = FloatArray(grid * grid)
        val cw = (x1 - x0).toFloat() / grid; val ch = (y1 - y0).toFloat() / grid
        for (gy in 0 until grid) for (gx in 0 until grid) {
            if (!cellValid[gy * grid + gx]) continue
            val xa = x0 + (gx * cw).toInt(); val xb = maxOf(xa + 1, x0 + ((gx + 1) * cw).toInt())
            val ya = y0 + (gy * ch).toInt(); val yb = maxOf(ya + 1, y0 + ((gy + 1) * ch).toInt())
            val sx = maxOf(1, (xb - xa) / 4); val sy = maxOf(1, (yb - ya) / 4)
            var s = 0; var n = 0
            var yy = ya
            while (yy < yb) {
                var xx = xa
                while (xx < xb) { s += y[yy * rowStride + xx * pixelStride].toInt() and 0xFF; n++; xx += sx }
                yy += sy
            }
            out[gy * grid + gx] = if (n > 0) s.toFloat() / n else 0f
        }
        return out
    }

    /** Mean absolute difference of two signatures over the valid cells (0 if there are none). */
    fun meanAbsDiff(a: FloatArray, b: FloatArray): Float {
        var s = 0f; var n = 0
        for (i in cellValid.indices) if (cellValid[i]) { s += abs(a[i] - b[i]); n++ }
        return if (n == 0) 0f else s / n
    }

    /** Mean absolute deviation of a signature from its own mean over the valid cells. */
    fun contrast(a: FloatArray): Float {
        var s = 0f; var n = 0
        for (i in cellValid.indices) if (cellValid[i]) { s += a[i]; n++ }
        if (n == 0) return 0f
        val mean = s / n
        var d = 0f
        for (i in cellValid.indices) if (cellValid[i]) d += abs(a[i] - mean)
        return d / n
    }

    companion object {
        /** ROI from the box hull ([BoxMask.hull]) clipped to the image; null if the hull misses the image. */
        fun fromHull(hull: List<FloatArray>, width: Int, height: Int, grid: Int = 48): SpinRoi? {
            if (hull.size < 3) return null
            val x0 = maxOf(0, hull.minOf { it[0] }.toInt()); val x1 = minOf(width, hull.maxOf { it[0] }.toInt() + 1)
            val y0 = maxOf(0, hull.minOf { it[1] }.toInt()); val y1 = minOf(height, hull.maxOf { it[1] }.toInt() + 1)
            if (x1 - x0 < grid || y1 - y0 < grid) return null
            val cw = (x1 - x0).toFloat() / grid; val ch = (y1 - y0).toFloat() / grid
            val valid = BooleanArray(grid * grid) { BoxMask.inside(hull, x0 + ((it % grid) + 0.5f) * cw, y0 + ((it / grid) + 0.5f) * ch) }
            return SpinRoi(x0, y0, x1, y1, grid, valid)
        }
    }
}

enum class SpinVerdict { KEEP, NOT_TRACKING, CAP_REACHED, UNCHANGED, BLURRY }

/** Camera pose drift from the pose at spin start. */
data class SpinDrift(val metres: Float, val degrees: Double)

/**
 * Pure spin keyframe policy. [begin] stores the reference pose; [drift] / [phoneMoved] report whether the phone was
 * bumped (> [SpinConfig.moveToleranceM] or > [SpinConfig.moveToleranceDeg]); [decide] keeps a frame when the ROI
 * signature differs from the last kept one by more than [SpinConfig.roiDiffThreshold] and the sharpness is at least
 * 0.6 x the running median. At most [SpinConfig.maxPerTurn] per turn, [SpinConfig.turns] turns ([nextTurn]).
 */
class SpinKeyframePolicy(val config: SpinConfig = SpinConfig(), val roi: SpinRoi) {
    private var ref: FloatArray? = null
    private var last: FloatArray? = null
    private val median = RunningMedian(config.medianWindow)
    var turn = 0; private set
    var keptInTurn = 0; private set
    var totalKept = 0; private set

    val finished: Boolean get() = turn >= config.turns - 1 && keptInTurn >= config.maxPerTurn

    fun begin(pose: FloatArray) { ref = pose.copyOf() }

    fun drift(pose: FloatArray): SpinDrift {
        val r = ref ?: return SpinDrift(0f, 0.0)
        val dx = pose[12] - r[12]; val dy = pose[13] - r[13]; val dz = pose[14] - r[14]
        var tr = 0f
        for (c in 0..2) tr += r[c * 4] * pose[c * 4] + r[c * 4 + 1] * pose[c * 4 + 1] + r[c * 4 + 2] * pose[c * 4 + 2]
        val ang = Math.toDegrees(acos(((tr - 1f) / 2f).toDouble().coerceIn(-1.0, 1.0)))
        return SpinDrift(sqrt(dx * dx + dy * dy + dz * dz), ang)
    }

    fun phoneMoved(pose: FloatArray): Boolean {
        val d = drift(pose)
        return d.metres > config.moveToleranceM || d.degrees > config.moveToleranceDeg
    }

    /** Starts the next turn (the first frame of it is always kept); false when all turns are used. */
    fun nextTurn(): Boolean {
        if (turn >= config.turns - 1) return false
        turn++; keptInTurn = 0; last = null
        return true
    }

    fun decide(tracking: Boolean, signature: FloatArray, sharpness: Double): SpinVerdict {
        if (!tracking) return SpinVerdict.NOT_TRACKING
        if (keptInTurn >= config.maxPerTurn) return SpinVerdict.CAP_REACHED
        val l = last
        if (l != null && roi.meanAbsDiff(l, signature) <= config.roiDiffThreshold * maxOf(roi.contrast(l), config.minContrast)) return SpinVerdict.UNCHANGED
        val refSharp = if (median.size >= config.minMedianSamples) median.median() else 0.0
        median.add(sharpness)
        if (sharpness < config.minSharpnessRatio * refSharp) return SpinVerdict.BLURRY
        last = signature.copyOf()
        keptInTurn++; totalKept++
        return SpinVerdict.KEEP
    }
}
