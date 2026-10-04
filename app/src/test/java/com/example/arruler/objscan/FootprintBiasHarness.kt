package com.example.arruler.objscan

import java.util.Random
import kotlin.math.sqrt
import org.junit.Test

/**
 * Diagnostic harness for the footprint bias, run only when the environment variable FOOTPRINT_HARNESS is set
 * (otherwise it returns at once). Prints mean +- sd of the footprint side error (mm) per stage:
 *  raw    = measure() on the exact noisy object-surface points (no voxelisation, no isolation)
 *  voxel  = same points after the voxel cloud (voxel means)
 *  denoise= voxel points after the local-plane denoise
 *  full   = the whole pipeline (box, support margin, outliers, component)
 * Quantities: err = mean over the two sides of (measured - true) in mm.
 */
class FootprintBiasHarness {
    private class Acc { val v = ArrayList<Double>(); fun add(x: Double) { v.add(x) }
        fun mean() = v.average()
        fun sd(): Double { val m = mean(); return sqrt(v.sumOf { (it - m) * (it - m) } / v.size) }
        fun fmt() = "%+6.2f±%4.2f".format(mean(), sd()) }

    private fun side(m: ObjectMeasurements?, w: Float, d: Float): Double {
        m ?: return Double.NaN
        val t = listOf(w, d).sorted()
        return ((m.footprint.length - t[1]) + (m.footprint.width - t[0])) * 500.0
    }

    @Test fun skirt() {
        if (System.getenv("FOOTPRINT_SKIRT") == null) return
        for (q in listOf(ObjectQuality.QUICK, ObjectQuality.FINE)) {
            val size = 0.2f
            val s = Fixtures.boxScene(Random(5), size, size, size, 20f, (System.getenv("FOOTPRINT_SKIRT")!!.toFloat()) / 1000f, q.voxelSize, perCell = 3f)
            val ub = Fixtures.userBox(size, size, size, 20f, 0.01f)
            val r = Fixtures.run(s, ub, q)
            val hist = IntArray(12); var tot = 0
            val p = r.iso.points
            for (i in 0 until p.size / 3) {
                val l = ub.toLocal(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
                val out = maxOf(kotlin.math.abs(l.x), kotlin.math.abs(l.z)) - size / 2
                if (out > 0.0005f) {
                    tot++
                    val h = (Fixtures.plane.signedDistance(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]) * 1000).toInt() / 3
                    hist[h.coerceIn(0, 11)]++
                }
            }
            println("SKIRT q=$q outside>2mm=$tot of ${p.size / 3}; height hist (5mm bins) ${hist.toList()}")
        }
    }

    @Test fun run() {
        if (System.getenv("FOOTPRINT_HARNESS") == null) return
        val seeds = (System.getenv("FOOTPRINT_SEEDS") ?: "20").toInt()
        val sizes = (System.getenv("FOOTPRINT_SIZES") ?: "0.1,0.2,0.4").split(",").map { it.toFloat() }
        for (q in ObjectQuality.values().filter { System.getenv("FOOTPRINT_Q") == null || it.name == System.getenv("FOOTPRINT_Q") })
            for (noise in (System.getenv("FOOTPRINT_NOISES") ?: "2,3,4,6").split(",").map { it.toFloat() / 1000f })
                for (size in sizes)
                    for (yaw in listOf(0f, 30f))
                        for (floor in (System.getenv("FOOTPRINT_FLOORS") ?: "true,false").split(",").map { it.toBoolean() }) {
                            val raw = Acc(); val vox = Acc(); val den = Acc(); val full = Acc(); val hgt = Acc(); val vh = Acc(); val vm = Acc(); val vr = Acc(); val b0 = Acc(); val bh = Acc(); val br = Acc(); val tr = (System.getenv("FOOTPRINT_TRIMS") ?: "0.005,0.01,0.02").split(",").map { it.toFloat() }; val tacc = tr.map { Acc() }
                            for (seed in 1..seeds) {
                                val s = Fixtures.boxScene(
                                    Random(seed * 7919L), size, size, size, yaw, noise, q.voxelSize, perCell = 3f,
                                    floorHalf = if (floor) 0.6f else 0.0001f
                                )
                                val ub = Fixtures.userBox(size, size, size, yaw, 0.01f)
                                val objPts = s.points.copyOf(s.objectPoints * 3)
                                raw.add(side(ObjectMeasures.measure(objPts, ub, Fixtures.plane, q.voxelSize), size, size))
                                val cloud = ObjectVoxelCloud(ub, q.voxelSize); cloud.addAll(objPts)
                                val vp = cloud.points()
                                vox.add(side(ObjectMeasures.measure(vp, ub, Fixtures.plane, q.voxelSize), size, size))
                                val dp = ObjectDenoise.smooth(vp, q.denoiseRadius, 3)
                                den.add(side(ObjectMeasures.measure(dp, ub, Fixtures.plane, q.voxelSize), size, size))
                                val r = Fixtures.run(s, ub, q)
                                full.add(side(r.m, size, size))
                                for ((ti, t) in tr.withIndex()) tacc[ti].add(side(ObjectMeasures.measure(r.iso.points, ub, Fixtures.plane, q.voxelSize, trim = t), size, size))
                                val base = ObjectMeasures.measure(r.iso.points, ub, Fixtures.plane, q.voxelSize, floorGate = 0f)!!
                                b0.add(side(base, size, size)); bh.add((base.hullVolume / (size.toDouble() * size * size) - 1) * 100); br.add((base.volumeRecommended / (size.toDouble() * size * size) - 1) * 100)
                                hgt.add((r.m!!.maxHeight - size) * 1000.0)
                                val tv = size.toDouble() * size * size
                                vh.add((r.m.hullVolume / tv - 1) * 100); vm.add((r.mesh!!.volume() / tv - 1) * 100); vr.add((r.m.volumeRecommended / tv - 1) * 100)
                            }
                            System.gc()
                            println("H q=$q n=${noise * 1000} size=${(size * 1000).toInt()} yaw=${yaw.toInt()} floor=$floor | raw ${raw.fmt()} vox ${vox.fmt()} den ${den.fmt()} full ${full.fmt()} | h ${hgt.fmt()} | vol% hull ${vh.fmt()} mesh ${vm.fmt()} rec ${vr.fmt()} | trims ${tr.zip(tacc).joinToString { "${it.first}:${it.second.fmt()}" }} | BASE(no gate) err ${b0.fmt()} hull ${bh.fmt()} rec ${br.fmt()}")
                        }
    }
}

/** Per-seed hull volume error, FOOTPRINT_HULL=1 (looks for single-outlier hull blow-ups). */
class HullSeedProbe {
    @Test fun probe() {
        if (System.getenv("FOOTPRINT_HULL") == null) return
        val q = ObjectQuality.FINE
        for (seed in 1..20) {
            val s = Fixtures.boxScene(Random(seed * 7919L), 0.2f, 0.2f, 0.2f, 0f, 0.004f, q.voxelSize, perCell = 3f)
            val ub = Fixtures.userBox(0.2f, 0.2f, 0.2f, 0f, 0.01f)
            val r = Fixtures.run(s, ub, q)
            val m = r.m!!
            println("HULL attempts=${ConvexHull3D.lastAttempts} seed=$seed hull%=${(m.hullVolume / 0.008 - 1) * 100} n=${r.iso.count} H=${m.maxHeight} extW=${m.extentW} extD=${m.extentD}")
        }
    }
}
