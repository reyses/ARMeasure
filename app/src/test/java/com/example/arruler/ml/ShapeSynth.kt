package com.example.arruler.ml

import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic scans of primitives resting on the plane y = 0 (test support, shared by the tests and the trainer).
 * Surface samples uniform by area, the underside never sampled, isotropic Gaussian noise per axis, points under
 * [MARGIN] dropped (the isolation stage's support margin), optional missing azimuth wedge (unseen side).
 */
object ShapeSynth {
    const val MARGIN = 0.008f

    class Spec(
        val label: ShapeLabel,
        /** Footprint extents / radii etc. in meters, see each generator. */
        val a: Double, val b: Double, val c: Double,
        val yaw: Double, val cx: Double, val cz: Double,
    )

    private fun count(area: Double) = (area / 3e-5).toInt().coerceIn(600, 4000)

    /** Box w x d x h (m), yaw about +Y. */
    fun box(rnd: Random, w: Double, d: Double, h: Double, yaw: Double, cx: Double, cz: Double): Array<DoubleArray> {
        val aTop = w * d; val aX = d * h; val aZ = w * h
        val tot = aTop + 2 * aX + 2 * aZ
        val n = count(tot)
        val pts = Array(n) { DoubleArray(3) }
        for (i in 0 until n) {
            var u = rnd.nextDouble() * tot
            val lx: Double; val ly: Double; val lz: Double
            if (u < aTop) { lx = (rnd.nextDouble() - .5) * w; lz = (rnd.nextDouble() - .5) * d; ly = h }
            else { u -= aTop
                if (u < 2 * aX) { lx = if (u < aX) -w / 2 else w / 2; lz = (rnd.nextDouble() - .5) * d; ly = rnd.nextDouble() * h }
                else { lx = (rnd.nextDouble() - .5) * w; lz = if (u - 2 * aX < aZ) -d / 2 else d / 2; ly = rnd.nextDouble() * h }
            }
            // ObjectBox convention: local x -> (cos yaw, 0, -sin yaw), local z -> (sin yaw, 0, cos yaw)
            pts[i][0] = cx + lx * cos(yaw) + lz * sin(yaw)
            pts[i][1] = ly
            pts[i][2] = cz - lx * sin(yaw) + lz * cos(yaw)
        }
        return pts
    }

    fun cylinder(rnd: Random, r: Double, h: Double, cx: Double, cz: Double): Array<DoubleArray> {
        val aTop = PI * r * r; val aW = 2 * PI * r * h; val tot = aTop + aW
        val n = count(tot)
        return Array(n) {
            val ang = rnd.nextDouble() * 2 * PI
            if (rnd.nextDouble() * tot < aTop) {
                val rr = r * sqrt(rnd.nextDouble())
                doubleArrayOf(cx + rr * cos(ang), h, cz + rr * sin(ang))
            } else doubleArrayOf(cx + r * cos(ang), rnd.nextDouble() * h, cz + r * sin(ang))
        }
    }

    fun sphere(rnd: Random, r: Double, cx: Double, cz: Double): Array<DoubleArray> {
        val n = count(4 * PI * r * r)
        val out = ArrayList<DoubleArray>(n)
        while (out.size < n) {
            val z = rnd.nextDouble() * 2 - 1; val ang = rnd.nextDouble() * 2 * PI
            val s = sqrt(1 - z * z)
            val y = r + r * z
            if (y < 0.0) continue
            out.add(doubleArrayOf(cx + r * s * cos(ang), y, cz + r * s * sin(ang)))
        }
        return out.toTypedArray()
    }

    /** Cone, apex up, base radius [rb], height [h]. */
    fun cone(rnd: Random, rb: Double, h: Double, cx: Double, cz: Double): Array<DoubleArray> {
        val slant = sqrt(rb * rb + h * h)
        val n = count(PI * rb * slant)
        return Array(n) {
            val s = sqrt(rnd.nextDouble()) * slant       // distance from the apex, area-uniform
            val rho = rb * s / slant; val ang = rnd.nextDouble() * 2 * PI
            doubleArrayOf(cx + rho * cos(ang), h * (1 - s / slant), cz + rho * sin(ang))
        }
    }

    /** Union of three spheres resting on the plane, only the exposed surface. */
    fun blob(rnd: Random): Array<DoubleArray> {
        val cs = ArrayList<DoubleArray>()
        while (cs.size < 3) {
            val r = 0.03 + rnd.nextDouble() * 0.025
            val ang = rnd.nextDouble() * 2 * PI; val rad = 0.03 + rnd.nextDouble() * 0.07
            val c = doubleArrayOf(rad * cos(ang), r, rad * sin(ang), r)
            // one lump, but clearly three: each new sphere overlaps the existing ones by 0.55-1.0 of the radii sum
            if (cs.isEmpty() || cs.all { val d = Math.hypot(Math.hypot(it[0] - c[0], it[1] - c[1]), it[2] - c[2]); d in 0.55 * (it[3] + c[3])..1.0 * (it[3] + c[3]) }) cs.add(c)
        }
        val out = ArrayList<DoubleArray>()
        for ((k, s) in cs.withIndex()) {
            val n = 700
            var made = 0
            while (made < n) {
                val z = rnd.nextDouble() * 2 - 1; val ang = rnd.nextDouble() * 2 * PI
                val q = sqrt(1 - z * z)
                val p = doubleArrayOf(s[0] + s[3] * q * cos(ang), s[1] + s[3] * z, s[2] + s[3] * q * sin(ang))
                made++
                if (p[1] < 0.0) continue
                if (cs.withIndex().any { (j, o) -> j != k && Math.hypot(Math.hypot(p[0] - o[0], p[1] - o[1]), p[2] - o[2]) < o[3] }) continue
                out.add(p)
            }
        }
        return out.toTypedArray()
    }

    /** Adds noise, removes a wedge of [wedge] rad starting at [wedgeStart] around the centroid, flattens to a packed xyz array. */
    fun finish(rnd: Random, pts: Array<DoubleArray>, sigma: Double, wedge: Double = 0.0, wedgeStart: Double = 0.0): FloatArray {
        var mx = 0.0; var mz = 0.0
        for (p in pts) { mx += p[0]; mz += p[2] }
        mx /= pts.size; mz /= pts.size
        val out = ArrayList<Float>(pts.size * 3)
        for (p in pts) {
            if (wedge > 0.0) {
                var a = atan2(p[2] - mz, p[0] - mx) - wedgeStart
                a = ((a % (2 * PI)) + 2 * PI) % (2 * PI)
                if (a < wedge) continue
            }
            val x = p[0] + rnd.nextGaussian() * sigma
            val y = p[1] + rnd.nextGaussian() * sigma
            val z = p[2] + rnd.nextGaussian() * sigma
            if (y < MARGIN) continue
            out.add(x.toFloat()); out.add(y.toFloat()); out.add(z.toFloat())
        }
        return out.toFloatArray()
    }

    /** Random sample of [label]: sizes 5-60 cm, noise 2-6 mm, 35 % with a missing wedge of 30-120 degrees, random yaw/position. */
    fun random(rnd: Random, label: ShapeLabel): FloatArray {
        val cx = rnd.nextDouble() * 2 - 1; val cz = rnd.nextDouble() * 2 - 1
        fun size() = 0.05 + rnd.nextDouble() * 0.55
        val pts = when (label) {
            ShapeLabel.BOX -> box(rnd, size(), size(), size(), rnd.nextDouble() * PI, cx, cz)
            ShapeLabel.CYLINDER -> cylinder(rnd, size() / 2, size(), cx, cz)
            ShapeLabel.SPHERE -> sphere(rnd, size() / 2, cx, cz)
            ShapeLabel.CONE -> cone(rnd, size() / 2, size(), cx, cz)
            ShapeLabel.UNKNOWN -> blob(rnd)
        }
        val sigma = 0.002 + rnd.nextDouble() * 0.004
        val wedge = if (rnd.nextDouble() < 0.35) (30 + rnd.nextDouble() * 90) * PI / 180 else 0.0
        return finish(rnd, pts, sigma, wedge, rnd.nextDouble() * 2 * PI)
    }
}
