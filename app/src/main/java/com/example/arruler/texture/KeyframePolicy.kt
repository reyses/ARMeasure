package com.example.arruler.texture

import com.example.arruler.geometry.Vec3
import kotlin.math.acos
import kotlin.math.max

/** Image sharpness: variance of a 4-neighbour Laplacian on a box-downsampled Y (luma) plane. Pure. */
object Sharpness {
    /**
     * [y] is the Y plane (row [rowStride] bytes, [pixelStride] bytes between pixels). [downsample] <= 0 picks a
     * factor so the short side is about 240 px. Returns 0 for images too small to filter.
     */
    fun laplacianVariance(y: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int = 1, downsample: Int = 0): Double {
        val ds = if (downsample > 0) downsample else max(1, minOf(width, height) / 240)
        val w = width / ds; val h = height / ds
        if (w < 3 || h < 3) return 0.0
        val g = FloatArray(w * h)
        val inv = 1f / (ds * ds)
        for (j in 0 until h) for (i in 0 until w) {
            var s = 0
            for (dj in 0 until ds) {
                val row = (j * ds + dj) * rowStride
                for (di in 0 until ds) s += y[row + (i * ds + di) * pixelStride].toInt() and 0xFF
            }
            g[j * w + i] = s * inv
        }
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (j in 1 until h - 1) for (i in 1 until w - 1) {
            val l = g[(j - 1) * w + i] + g[(j + 1) * w + i] + g[j * w + i - 1] + g[j * w + i + 1] - 4f * g[j * w + i]
            sum += l; sum2 += l.toDouble() * l; n++
        }
        val mean = sum / n
        return sum2 / n - mean * mean
    }
}

/** Running median over the last [window] values. */
class RunningMedian(private val window: Int) {
    private val buf = ArrayDeque<Double>()
    val size: Int get() = buf.size
    fun add(v: Double) { buf.addLast(v); if (buf.size > window) buf.removeFirst() }
    fun median(): Double {
        if (buf.isEmpty()) return 0.0
        val s = buf.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

data class KeyframeConfig(
    val minAngleDeg: Double = 8.0,
    val maxKeyframes: Int = 120,
    /** The box centre must project inside the central fraction of the image (0.8 = middle 80 % each way). */
    val centralFraction: Float = 0.8f,
    /** Reject a frame whose sharpness is below this fraction of the running median. */
    val minSharpnessRatio: Double = 0.6,
    val medianWindow: Int = 40,
    val minMedianSamples: Int = 5,
)

enum class KeyframeVerdict { KEEP, NOT_TRACKING, CAP_REACHED, TOO_CLOSE, OUT_OF_FRAME, BLURRY }

/**
 * Walk-around keyframe selection (pure). Rules: tracking == TRACKING; the box centre projects inside the central
 * 80 % of the image; at least [KeyframeConfig.minAngleDeg] from every kept keyframe as seen from the box centre;
 * sharpness at least 0.6 x the running median; at most [KeyframeConfig.maxKeyframes].
 */
class KeyframePolicy(val config: KeyframeConfig = KeyframeConfig()) {
    private val dirs = ArrayList<Vec3>()
    private val median = RunningMedian(config.medianWindow)
    val keptCount: Int get() = dirs.size

    /** Everything that does not need the image (so the caller can skip acquiring it). No state change. */
    fun checkGeometry(tracking: Boolean, pose: FloatArray, k: Intrinsics, boxCentre: Vec3): KeyframeVerdict {
        if (!tracking) return KeyframeVerdict.NOT_TRACKING
        if (dirs.size >= config.maxKeyframes) return KeyframeVerdict.CAP_REACHED
        val cam = CameraPose(pose)
        val p = cam.project(boxCentre, k) ?: return KeyframeVerdict.OUT_OF_FRAME
        val m = (1f - config.centralFraction) / 2f
        if (p[0] < m * k.width || p[0] > (1f - m) * k.width || p[1] < m * k.height || p[1] > (1f - m) * k.height) return KeyframeVerdict.OUT_OF_FRAME
        val d = (cam.position - boxCentre).normalized()
        val cosMax = Math.cos(Math.toRadians(config.minAngleDeg))
        for (o in dirs) if (o.dot(d) > cosMax) return KeyframeVerdict.TOO_CLOSE
        return KeyframeVerdict.KEEP
    }

    /** Full decision; on KEEP the keyframe is recorded. Sharpness of every geometry-passing frame feeds the median. */
    fun decide(tracking: Boolean, pose: FloatArray, k: Intrinsics, boxCentre: Vec3, sharpness: Double): KeyframeVerdict {
        val g = checkGeometry(tracking, pose, k, boxCentre)
        if (g != KeyframeVerdict.KEEP) return g
        val ref = if (median.size >= config.minMedianSamples) median.median() else 0.0
        median.add(sharpness)
        if (sharpness < config.minSharpnessRatio * ref) return KeyframeVerdict.BLURRY
        dirs.add((CameraPose(pose).position - boxCentre).normalized())
        return KeyframeVerdict.KEEP
    }

    /** Smallest angle in degrees between the kept directions (diagnostic; 180 with fewer than two). */
    fun minKeptSeparationDeg(): Double {
        var best = 180.0
        for (i in dirs.indices) for (j in i + 1 until dirs.size) best = minOf(best, Math.toDegrees(acos(dirs[i].dot(dirs[j]).toDouble().coerceIn(-1.0, 1.0))))
        return best
    }
}
