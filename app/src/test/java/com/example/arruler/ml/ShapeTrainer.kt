package com.example.arruler.ml

import com.example.arruler.objscan.SupportPlane
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Offline trainer for [ShapeClassifier] (test support): synthetic shapes -> features -> softmax regression by Adam.
 * Run via the @Ignore'd `trainer` test in ShapeClassifierTest (remove @Ignore, run, paste the printed block between
 * BEGIN TRAINED / END TRAINED in ShapeClassifier.kt).
 */
object ShapeTrainer {
    private val plane = SupportPlane.horizontal(0f)

    class Data(val x: Array<DoubleArray>, val y: IntArray, val fitLabel: Array<ShapeLabel>)

    /** [perClass] shapes per class, drawn from [seed]. */
    fun generate(perClass: Int, seed: Long): Data {
        val rnd = Random(seed)
        val xs = ArrayList<DoubleArray>(); val ys = ArrayList<Int>(); val fl = ArrayList<ShapeLabel>()
        for (i in 0 until perClass) for ((c, label) in ShapeClassifier.CLASSES.withIndex()) {
            val f = PrimitiveFit.fitAll(ShapeSynth.random(rnd, label), plane) ?: continue
            xs.add(ShapeClassifier.features(f)); ys.add(c); fl.add(f.best.label)
        }
        return Data(xs.toTypedArray(), ys.toIntArray(), fl.toTypedArray())
    }

    class Model(val mean: DoubleArray, val std: DoubleArray, val w: Array<DoubleArray>, val b: DoubleArray)

    fun train(d: Data, epochs: Int = 4000, lr: Double = 0.03, l2: Double = 1e-4): Model {
        val n = d.x.size; val k = ShapeClassifier.N_FEATURES; val c = ShapeClassifier.CLASSES.size
        val mean = DoubleArray(k); val std = DoubleArray(k)
        for (j in 0 until k) {
            for (i in 0 until n) mean[j] += d.x[i][j]
            mean[j] /= n
            for (i in 0 until n) std[j] += (d.x[i][j] - mean[j]) * (d.x[i][j] - mean[j])
            std[j] = sqrt(std[j] / n).coerceAtLeast(1e-6)
        }
        val z = Array(n) { i -> DoubleArray(k) { (d.x[i][it] - mean[it]) / std[it] } }
        val w = Array(c) { DoubleArray(k) }; val b = DoubleArray(c)
        val mw = Array(c) { DoubleArray(k) }; val vw = Array(c) { DoubleArray(k) }
        val mb = DoubleArray(c); val vb = DoubleArray(c)
        val b1 = 0.9; val b2 = 0.999
        for (ep in 1..epochs) {
            val gw = Array(c) { DoubleArray(k) }; val gb = DoubleArray(c)
            for (i in 0 until n) {
                val s = DoubleArray(c) { cc -> b[cc] + (0 until k).sumOf { w[cc][it] * z[i][it] } }
                val m = s.max(); var t = 0.0
                for (cc in 0 until c) { s[cc] = exp(s[cc] - m); t += s[cc] }
                for (cc in 0 until c) {
                    val g = s[cc] / t - (if (d.y[i] == cc) 1.0 else 0.0)
                    gb[cc] += g / n
                    for (j in 0 until k) gw[cc][j] += g * z[i][j] / n
                }
            }
            val c1 = 1 - Math.pow(b1, ep.toDouble()); val c2 = 1 - Math.pow(b2, ep.toDouble())
            for (cc in 0 until c) {
                for (j in 0 until k) {
                    val g = gw[cc][j] + l2 * w[cc][j]
                    mw[cc][j] = b1 * mw[cc][j] + (1 - b1) * g; vw[cc][j] = b2 * vw[cc][j] + (1 - b2) * g * g
                    w[cc][j] -= lr * (mw[cc][j] / c1) / (sqrt(vw[cc][j] / c2) + 1e-8)
                }
                mb[cc] = b1 * mb[cc] + (1 - b1) * gb[cc]; vb[cc] = b2 * vb[cc] + (1 - b2) * gb[cc] * gb[cc]
                b[cc] -= lr * (mb[cc] / c1) / (sqrt(vb[cc] / c2) + 1e-8)
            }
        }
        return Model(mean, std, w, b)
    }

    fun predict(m: Model, x: DoubleArray): Int {
        val p = ShapeClassifier.probabilities(x, m.mean, m.std, m.w, m.b)
        var bi = 0
        for (i in p.indices) if (p[i] > p[bi]) bi = i
        return bi
    }

    /** confusion[true][predicted] */
    fun confusion(m: Model, d: Data): Array<IntArray> {
        val cm = Array(4) { IntArray(4) }
        for (i in d.x.indices) cm[d.y[i]][predict(m, d.x[i])]++
        return cm
    }

    fun accuracy(cm: Array<IntArray>): Double {
        var ok = 0; var all = 0
        for (r in cm.indices) for (c in cm[r].indices) { all += cm[r][c]; if (r == c) ok += cm[r][c] }
        return ok.toDouble() / all
    }

    fun format(cm: Array<IntArray>): String {
        val sb = StringBuilder("true\\pred      BOX  CYLINDER  SPHERE  CONE\n")
        for (r in cm.indices) sb.append(String.format("%-12s", ShapeClassifier.CLASSES[r]))
            .append(cm[r].joinToString("") { String.format("%9d", it) }).append('\n')
        return sb.toString()
    }

    fun kotlinBlock(m: Model): String {
        fun arr(a: DoubleArray) = a.joinToString(", ") { String.format(java.util.Locale.US, "%.6f", it) }
        val sb = StringBuilder()
        sb.append("    val MEAN = doubleArrayOf(${arr(m.mean)})\n")
        sb.append("    val STD = doubleArrayOf(${arr(m.std)})\n")
        sb.append("    val WEIGHTS = arrayOf(\n")
        for (r in m.w) sb.append("        doubleArrayOf(${arr(r)}),\n")
        sb.append("    )\n    val BIAS = doubleArrayOf(${arr(m.b)})\n")
        return sb.toString()
    }

    /** Trains on 1500 shapes per class (seed 1) and prints weights, train / held-out (seed 99, 500 per class) results. */
    fun run(): String {
        val tr = generate(1500, 1L)
        val ho = generate(500, 99L)
        val m = train(tr)
        val sb = StringBuilder()
        sb.append("train n=${tr.x.size} accuracy=${accuracy(confusion(m, tr))}\n")
        val cm = confusion(m, ho)
        sb.append("held-out n=${ho.x.size} accuracy=${accuracy(cm)}\n").append(format(cm))
        var fitOk = 0
        for (i in ho.x.indices) if (ho.fitLabel[i] == ShapeClassifier.CLASSES[ho.y[i]]) fitOk++
        sb.append("pure PrimitiveFit (score softmax, no learning) held-out accuracy=${fitOk.toDouble() / ho.x.size}\n")
        sb.append("// BEGIN TRAINED\n").append(kotlinBlock(m)).append("// END TRAINED\n")
        return sb.toString()
    }
}
