package com.example.arruler.objscan

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-seed footprint accuracy on synthetic scenes (4 mm Gaussian depth noise, 3 samples per voxel face, table points
 * adjacent to the object, 2 % outliers). Errors in mm = measured - true. Regression for the +4-5 mm per-side bias that the
 * table-fuzz skirt at the object base used to add (see ObjectMeasures.floorGate and docs/OBJECT_SCAN.md).
 */
class FootprintAccuracyTest {
    private class Stat(val v: List<Double>) {
        val mean get() = v.average()
        val maxAbs get() = v.maxOf { abs(it) }
        override fun toString() = "mean %+.2f maxabs %.2f mm".format(mean, maxAbs)
    }

    private val seeds = 1..6

    private fun cubeErrors(q: ObjectQuality): Triple<Stat, Stat, Stat> {
        val len = ArrayList<Double>(); val hgt = ArrayList<Double>(); val hull = ArrayList<Double>()
        for (seed in seeds) {
            val s = Fixtures.boxScene(Random(100L + seed), 0.2f, 0.2f, 0.2f, 20f, 0.004f, q.voxelSize, perCell = 3f)
            val r = Fixtures.run(s, Fixtures.userBox(0.2f, 0.2f, 0.2f, 20f, 0.01f), q)
            val m = r.m!!
            len.add((m.footprint.length - 0.2) * 1000); len.add((m.footprint.width - 0.2) * 1000)
            hgt.add((m.maxHeight - 0.2) * 1000)
            hull.add((m.hullVolume / 0.008 - 1) * 100)
        }
        println("CUBE200 $q 4mm: footprint ${Stat(len)}; height ${Stat(hgt)}; hull volume error ${Stat(hull)} %")
        return Triple(Stat(len), Stat(hgt), Stat(hull))
    }

    @Test fun cubeFineFootprintMeanWithin1p5AndEverySeedWithin3() {
        val (len, hgt, _) = cubeErrors(ObjectQuality.FINE)
        assertEquals("FINE mean footprint error", 0.0, len.mean, 1.5)
        assertTrue("FINE every side within 3 mm: ${len.maxAbs}", len.maxAbs <= 3.0)
        assertTrue("height unchanged: ${hgt.maxAbs}", hgt.maxAbs <= 1.5)
    }

    @Test fun cubeQuickFootprintMeanWithin3() {
        val (len, hgt, _) = cubeErrors(ObjectQuality.QUICK)
        assertEquals("QUICK mean footprint error", 0.0, len.mean, 3.0)
        assertTrue("QUICK every side within 4 mm: ${len.maxAbs}", len.maxAbs <= 4.0)
        assertTrue("height: ${hgt.maxAbs}", hgt.maxAbs <= 6.0)
    }

    @Test fun cylinderDiameterWithin3() {
        for (q in ObjectQuality.values()) {
            val d = ArrayList<Double>(); val hgt = ArrayList<Double>(); val dOff = ArrayList<Double>()
            for (seed in seeds) {
                val s = Fixtures.cylinderScene(Random(200L + seed), 0.1f, 0.2f, 0.004f, q.voxelSize, perCell = 3f)
                val r = Fixtures.run(s, ObjectBox(Fixtures.origin, 0f, 0.22f, 0.22f, 0.21f), q)
                d.add((r.m!!.footprint.length - 0.2) * 1000); d.add((r.m.footprint.width - 0.2) * 1000)
                val b0 = ObjectMeasures.measure(r.iso.points, r.box, Fixtures.plane, q.voxelSize, floorGate = 0f)!!
                dOff.add((b0.footprint.length - 0.2) * 1000); dOff.add((b0.footprint.width - 0.2) * 1000)
                hgt.add((r.m.maxHeight - 0.2) * 1000)
            }
            println("CYL r=100 $q 4mm: diameter ${Stat(d)} (gate off: ${Stat(dOff)}); height ${Stat(hgt)}")
            assertEquals("$q diameter mean", 0.0, Stat(d).mean, 3.0)
            assertTrue("$q diameter every side: ${Stat(d)}", Stat(d).maxAbs <= 4.5)
        }
    }

    @Test fun box500x300x400Within3() {
        for (q in ObjectQuality.values()) {
            val l = ArrayList<Double>(); val w = ArrayList<Double>(); val hull = ArrayList<Double>(); val lOff = ArrayList<Double>(); val wOff = ArrayList<Double>()
            for (seed in 1..3) {
                val s = Fixtures.boxScene(Random(300L + seed), 0.5f, 0.3f, 0.4f, 30f, 0.004f, q.voxelSize, perCell = 3f)
                val r = Fixtures.run(s, Fixtures.userBox(0.5f, 0.3f, 0.4f, 30f, 0.01f), q)
                l.add((r.m!!.footprint.length - 0.5) * 1000); w.add((r.m.footprint.width - 0.3) * 1000)
                val b0 = ObjectMeasures.measure(r.iso.points, r.box, Fixtures.plane, q.voxelSize, floorGate = 0f)!!
                lOff.add((b0.footprint.length - 0.5) * 1000); wOff.add((b0.footprint.width - 0.3) * 1000)
                hull.add((r.m.hullVolume / 0.06 - 1) * 100)
            }
            println("BOX 500x300x400 $q 4mm: length ${Stat(l)} (gate off ${Stat(lOff)}); width ${Stat(w)} (gate off ${Stat(wOff)}); hull ${Stat(hull)} %")
            assertEquals("$q length mean", 0.0, Stat(l).mean, 3.0)
            assertEquals("$q width mean", 0.0, Stat(w).mean, 3.0)
        }
    }
}

/** Regression for the non-manifold quickhull on nearly coplanar denoised faces (hull volume +845 % on this seed). */
class HullRobustnessTest {
    @Test fun cubeSeed10HullVolumeStaysSane() {
        val q = ObjectQuality.FINE
        val s = Fixtures.boxScene(Random(10 * 7919L), 0.2f, 0.2f, 0.2f, 0f, 0.004f, q.voxelSize, perCell = 3f)
        val r = Fixtures.run(s, Fixtures.userBox(0.2f, 0.2f, 0.2f, 0f, 0.01f), q)
        val err = (r.m!!.hullVolume / 0.008 - 1) * 100
        println("seed 10 hull error $err % after ${ConvexHull3D.lastAttempts} build attempt(s)")
        assertTrue("hull volume error $err %", err in -1.0..5.0)
        assertTrue(r.m.volumeRecommended in 0.0075f..0.0085f)
    }

    @Test fun degenerateCoplanarInputGivesZeroNotGarbage() {
        val n = 200
        val p = DoubleArray(n * 3) { if (it % 3 == 1) 0.0 else (it % 17) * 0.001 }
        assertEquals(0.0, ConvexHull3D.volume(p, n), 0.0)
    }
}
