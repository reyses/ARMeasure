package com.example.arruler.ml

import com.example.arruler.objscan.SupportPlane
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimitiveFitTest {
    private val plane = SupportPlane.horizontal(0f)
    private val sigma = 0.004   // 4 mm
    private val dimTol = 0.006  // 6 mm
    private val volTol = 0.06   // 6 %

    private fun fits(pts: FloatArray) = PrimitiveFit.fitAll(pts, plane)!!

    private fun near(tag: String, want: Double, got: Double) =
        assertTrue("$tag: want ${want * 1000} mm got ${got * 1000} mm", abs(want - got) <= dimTol)

    private fun vol(tag: String, want: Double, got: Double) =
        assertTrue("$tag volume: want $want got $got m^3", abs(got - want) / want <= volTol)

    @Test fun boxRecovered() {
        for (seed in 1..6) {
            val rnd = Random(seed.toLong())
            val w = 0.200; val d = 0.160; val h = 0.120
            val f = fits(ShapeSynth.finish(rnd, ShapeSynth.box(rnd, w, d, h, 0.2 + seed * 0.4, 0.3, -0.2), sigma))
            val p = f.fits[ShapeLabel.BOX]!!.params as BoxParams
            near("box w seed $seed", w, p.w); near("box d seed $seed", d, p.d); near("box h seed $seed", h, p.h)
            vol("box seed $seed", w * d * h, f.fits[ShapeLabel.BOX]!!.volume)
            assertEquals("box label seed $seed", ShapeLabel.BOX, PrimitiveFit.decide(f).label)
        }
    }

    @Test fun cubeRecovered() {
        val rnd = Random(21)
        val f = fits(ShapeSynth.finish(rnd, ShapeSynth.box(rnd, 0.2, 0.2, 0.2, 0.5, 0.0, 0.0), sigma))
        val p = f.fits[ShapeLabel.BOX]!!.params as BoxParams
        near("cube w", 0.2, p.w); near("cube d", 0.2, p.d); near("cube h", 0.2, p.h)
    }

    @Test fun cylinderRecovered() {
        for (seed in 1..6) {
            val rnd = Random(100L + seed)
            val r = 0.100; val h = 0.200
            val f = fits(ShapeSynth.finish(rnd, ShapeSynth.cylinder(rnd, r, h, -0.4, 0.1), sigma))
            val p = f.fits[ShapeLabel.CYLINDER]!!.params as CylinderParams
            near("cyl r seed $seed", r, p.r); near("cyl h seed $seed", h, p.h)
            near("cyl cx", -0.4, p.cx); near("cyl cz", 0.1, p.cz)
            vol("cyl seed $seed", PI * r * r * h, f.fits[ShapeLabel.CYLINDER]!!.volume)
            assertEquals("cyl label seed $seed", ShapeLabel.CYLINDER, PrimitiveFit.decide(f).label)
        }
    }

    @Test fun sphereRecovered() {
        for (seed in 1..6) {
            val rnd = Random(200L + seed)
            val r = 0.100
            val f = fits(ShapeSynth.finish(rnd, ShapeSynth.sphere(rnd, r, 0.5, 0.5), sigma))
            val p = f.fits[ShapeLabel.SPHERE]!!.params as SphereParams
            near("sphere r seed $seed", r, p.r); near("sphere cy seed $seed", r, p.cy)
            vol("sphere seed $seed", 4.0 / 3 * PI * r * r * r, f.fits[ShapeLabel.SPHERE]!!.volume)
            assertEquals("sphere label seed $seed", ShapeLabel.SPHERE, PrimitiveFit.decide(f).label)
        }
    }

    @Test fun coneRecovered() {
        for (seed in 1..6) {
            val rnd = Random(300L + seed)
            val rb = 0.100; val h = 0.200
            val f = fits(ShapeSynth.finish(rnd, ShapeSynth.cone(rnd, rb, h, 0.0, 0.3), sigma))
            val p = f.fits[ShapeLabel.CONE]!!.params as ConeParams
            near("cone rBase seed $seed", rb, p.rBase); near("cone h seed $seed", h, p.h)
            vol("cone seed $seed", PI * rb * rb * h / 3, f.fits[ShapeLabel.CONE]!!.volume)
            assertEquals("cone label seed $seed", ShapeLabel.CONE, PrimitiveFit.decide(f).label)
        }
    }

    @Test fun irregularBlobIsUnknown() {
        var unknown = 0
        val n = 30
        val sb = StringBuilder()
        for (seed in 1..n) {
            val rnd = Random(400L + seed)
            val pts = ShapeSynth.finish(rnd, ShapeSynth.blob(rnd), sigma)
            val r = PrimitiveFit.fit(pts, plane)!!
            sb.append(String.format("blob %d: best=%s excess=%.3f\n", seed, r.fits.best.label, r.excess))
            if (r.label == ShapeLabel.UNKNOWN) unknown++
        }
        println(sb)
        println("blobs UNKNOWN: $unknown / $n")
        assertTrue("only $unknown of $n blobs UNKNOWN", unknown >= 27)
    }

    @Test fun fewPointsReturnsNull() {
        assertTrue(PrimitiveFit.fitAll(FloatArray(30), plane) == null)
    }
}
