package com.example.arruler.ml

import com.example.arruler.objscan.SupportPlane
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class ShapeClassifierTest {
    private val plane = SupportPlane.horizontal(0f)

    /** Re-trains on synthetic data and prints the weights block for ShapeClassifier.kt. Remove @Ignore to run. */
    @Ignore("offline trainer: prints weights to paste into ShapeClassifier.kt")
    @Test fun trainer() {
        println(ShapeTrainer.run())
    }

    @Test fun heldOutAccuracyAtLeast90Percent() {
        val ho = ShapeTrainer.generate(250, 777L)        // seed differs from the trainer's (1 train, 99 held-out)
        val cm = Array(4) { IntArray(4) }
        var falseUnknown = 0
        val rnd = Random(0)
        for (i in ho.x.indices) {
            val p = ShapeClassifier.probabilities(ho.x[i])
            var bi = 0
            for (c in p.indices) if (p[c] > p[bi]) bi = c
            cm[ho.y[i]][bi]++
        }
        // end-to-end (classifier + unknown gate) on fresh shapes
        var n = 0; var ok = 0
        for (k in 0 until 400) {
            val label = ShapeClassifier.CLASSES[k % 4]
            val r = ShapeRecognizer.recognize(ShapeSynth.random(rnd, label), plane) ?: continue
            n++
            if (r.label == ShapeLabel.UNKNOWN) falseUnknown++ else if (r.label == label) ok++
        }
        val acc = ShapeTrainer.accuracy(cm)
        println("held-out n=${ho.x.size} classifier accuracy=$acc\n" + ShapeTrainer.format(cm))
        println("end-to-end (with UNKNOWN gate) n=$n correct=$ok falseUnknown=$falseUnknown")
        assertTrue("held-out accuracy $acc", acc >= 0.90)
        assertTrue("end-to-end correct $ok / $n", ok.toDouble() / n >= 0.90)
    }

    @Test fun probabilitiesSumToOne() {
        val rnd = Random(5)
        val f = PrimitiveFit.fitAll(ShapeSynth.random(rnd, ShapeLabel.CYLINDER), plane)!!
        val p = ShapeClassifier.probabilities(ShapeClassifier.features(f))
        assertEquals(1.0, p.sum(), 1e-9)
        assertEquals(ShapeClassifier.N_FEATURES, ShapeClassifier.features(f).size)
    }

    @Test fun describeReadsLikeTheResultCard() {
        val rnd = Random(9)
        val pts = ShapeSynth.finish(rnd, ShapeSynth.cylinder(rnd, 0.098, 0.201, 0.0, 0.0), 0.003)
        val r = ShapeRecognizer.recognize(pts, plane)!!
        println(r.describe())
        assertEquals(ShapeLabel.CYLINDER, r.label)
        assertTrue(r.describe().startsWith("Looks like a cylinder ("))
        assertTrue(r.describe().contains("formula volume"))
    }
}
