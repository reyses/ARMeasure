package com.example.arruler.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The "bit of ML": a multinomial logistic regression (softmax regression) over 10 hand-crafted geometric
 * features of an isolated object. 4 classes x 10 weights + 4 biases = 44 numbers, trained offline on synthetic noisy
 * shapes by `ShapeTrainer` (app/src/test/.../ml, the @Ignore'd `trainer` test prints the block pasted below) and
 * hard-coded. No model file, no runtime dependency, ~microseconds per object.
 *
 * Features (index: meaning, unit):
 *  0 nrms BOX, 1 nrms CYLINDER, 2 nrms SPHERE, 3 nrms CONE: rms point-to-surface distance of each fit / object size (dimensionless)
 *  4 footprint circularity 4 pi A / P^2 of the footprint hull (dimensionless, circle 1, square 0.785)
 *  5 footprint aspect = short / long side of the min-area rectangle (dimensionless, 0..1)
 *  6 ln(height / footprint length), clipped to +-2 (dimensionless)
 *  7 radius slope d(radius)/d(height) of the cone fit, clipped to +-2 (dimensionless; 0 = cylinder, -R/H = cone)
 *  8 top / bottom area: hull area of the top 20 % of the height over that of the lowest 25 %, clipped to 0..3 (dimensionless)
 *  9 |sphere centre height - sphere radius| / size, clipped to 0..1 (dimensionless; 0 for a ball resting on the plane)
 *
 * Features are standardised with [MEAN] / [STD] (training set) before the dot product.
 */
object ShapeClassifier {
    val CLASSES = arrayOf(ShapeLabel.BOX, ShapeLabel.CYLINDER, ShapeLabel.SPHERE, ShapeLabel.CONE)
    const val N_FEATURES = 10

    fun features(f: PrimitiveFits): DoubleArray {
        val fb = f.fits[ShapeLabel.BOX]!!; val fc = f.fits[ShapeLabel.CYLINDER]!!
        val fs = f.fits[ShapeLabel.SPHERE]!!; val fk = f.fits[ShapeLabel.CONE]!!
        val circ = if (f.hullPerimeter > 1e-9) 4.0 * PI * f.hullArea / (f.hullPerimeter * f.hullPerimeter) else 0.0
        val aspect = if (f.rectLength > 1e-9) f.rectWidth / f.rectLength else 1.0
        val hl = ln(max(f.heightMax, 1e-3) / max(f.rectLength, 1e-3)).coerceIn(-2.0, 2.0)
        val tb = if (f.bottomArea > 1e-9) (f.topArea / f.bottomArea).coerceIn(0.0, 3.0) else 3.0
        val sp = fs.params as SphereParams
        return doubleArrayOf(
            fb.nrms, fc.nrms, fs.nrms, fk.nrms,
            circ, aspect, hl, f.coneSlope.coerceIn(-2.0, 2.0), tb,
            (abs(sp.cy - sp.r) / f.size).coerceIn(0.0, 1.0),
        )
    }

    /** Class probabilities (order of [CLASSES]) for raw [feat]. */
    fun probabilities(feat: DoubleArray, mean: DoubleArray = MEAN, std: DoubleArray = STD,
                      w: Array<DoubleArray> = WEIGHTS, b: DoubleArray = BIAS): DoubleArray {
        val z = DoubleArray(CLASSES.size) { c ->
            var s = b[c]
            for (k in 0 until N_FEATURES) s += w[c][k] * (feat[k] - mean[k]) / std[k]
            s
        }
        val m = z.max()
        var t = 0.0
        for (c in z.indices) { z[c] = exp(z[c] - m); t += z[c] }
        for (c in z.indices) z[c] /= t
        return z
    }

    /** Most probable class and its probability. */
    fun classify(f: PrimitiveFits): Pair<ShapeLabel, Double> {
        val p = probabilities(features(f))
        var bi = 0
        for (c in p.indices) if (p[c] > p[bi]) bi = c
        return CLASSES[bi] to p[bi]
    }

    // ---- trained weights (paste target of the trainer) ----
    // BEGIN TRAINED
    val MEAN = doubleArrayOf(0.056590, 0.058782, 0.060245, 0.042883, 0.915372, 0.861981, -0.088203, -0.192335, 0.727209, 0.296867)
    val STD = doubleArrayOf(0.041084, 0.032362, 0.181725, 0.033501, 0.120576, 0.204653, 0.710050, 0.411610, 0.379418, 0.364745)
    val WEIGHTS = arrayOf(
        doubleArrayOf(-3.385096, 0.693519, 0.522823, 0.803382, -3.808475, -0.073190, 0.139939, -0.125270, 1.136553, 0.006569),
        doubleArrayOf(0.668041, -2.925509, 0.543991, -1.975601, 3.117550, -0.262179, -1.010212, -0.284114, 2.246610, 0.178100),
        doubleArrayOf(1.059727, 1.513299, -1.631139, 1.566657, 1.208339, 0.093525, -0.319868, 2.016588, 0.203240, -1.099480),
        doubleArrayOf(1.870443, 1.059412, 0.557350, -0.427961, -0.101244, 0.204330, 1.192422, -1.595508, -3.052509, 0.893492),
    )
    val BIAS = doubleArrayOf(0.311713, -0.157572, 0.176887, -0.486593)
    // END TRAINED
}
