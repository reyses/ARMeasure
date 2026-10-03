package com.example.arruler.ml

import com.example.arruler.objscan.SupportPlane
import java.util.Locale

/**
 * Final answer for one object. [label] comes from [ShapeClassifier] (trained), [params] / [volume] from the
 * [PrimitiveFit] of that label. [label] = UNKNOWN (no params) when even the best primitive leaves an excess
 * normalised rms above [PrimitiveFit.UNKNOWN_EXCESS].
 */
class ShapeRecognition(
    val label: ShapeLabel,
    /** Classifier probability of [label] (0..1); for UNKNOWN the probability of the classifier's top class. */
    val confidence: Double,
    val params: ShapeParams?,
    /** Formula volume of the fitted primitive, m^3. */
    val volume: Double?,
    /** Label the pure fit-score softmax would give ([PrimitiveFitResult.label] before the unknown gate). */
    val fitLabel: ShapeLabel,
    val fitConfidence: Double,
    /** (rms of the best fit - noise floor) / size (dimensionless). */
    val excess: Double,
    val fits: PrimitiveFits,
) {
    /** "Looks like a cylinder (93 %) - r 9.8 cm, h 20.1 cm, formula volume 0.00605 m3" for the object result card. */
    fun describe(): String {
        val pct = Math.round(confidence * 100).toInt()
        fun cm(m: Double) = String.format(Locale.US, "%.1f cm", m * 100)
        val v = volume?.let { String.format(Locale.US, ", formula volume %.5f m³", it) } ?: ""
        return when (val p = params) {
            is BoxParams -> "Looks like a box ($pct %) — ${cm(p.w)} × ${cm(p.d)} × ${cm(p.h)}$v"
            is CylinderParams -> "Looks like a cylinder ($pct %) — r ${cm(p.r)}, h ${cm(p.h)}$v"
            is SphereParams -> "Looks like a sphere ($pct %) — r ${cm(p.r)}$v"
            is ConeParams ->
                if (p.rTop < 0.002) "Looks like a cone ($pct %) — r ${cm(p.rBase)}, h ${cm(p.h)}$v"
                else "Looks like a cone ($pct %) — r ${cm(p.rBase)} to ${cm(p.rTop)}, h ${cm(p.h)}$v"
            null -> "No simple shape fits this object"
        }
    }
}

object ShapeRecognizer {
    /** Null when there are too few points above the plane (< 30). */
    fun recognize(points: FloatArray, plane: SupportPlane): ShapeRecognition? =
        PrimitiveFit.fitAll(points, plane)?.let { recognize(it) }

    fun recognize(f: PrimitiveFits): ShapeRecognition {
        val fitRes = PrimitiveFit.decide(f)
        val (cls, prob) = ShapeClassifier.classify(f)
        val unknown = fitRes.label == ShapeLabel.UNKNOWN
        val chosen = f.fits[cls]!!
        return ShapeRecognition(
            if (unknown) ShapeLabel.UNKNOWN else cls, prob,
            if (unknown) null else chosen.params, if (unknown) null else chosen.volume,
            f.best.label, fitRes.confidence, fitRes.excess, f,
        )
    }
}
