package com.example.arruler.depth

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.random.Random

/** Test fixture: a prismatic room (floor y=0, ceiling y=height) sampled on a grid with Gaussian noise. */
object SyntheticRoom {

    /** [corners] are (x, z) pairs in order around the footprint. Returns packed xyz. */
    fun sample(
        corners: List<Pair<Float, Float>>,
        height: Float,
        spacing: Float = 0.03f,
        sigma: Float = 0.005f,
        seed: Int = 7
    ): FloatArray {
        val rnd = Random(seed)
        val out = ArrayList<Float>()
        fun add(x: Float, y: Float, z: Float) {
            out += x + gauss(rnd) * sigma; out += y + gauss(rnd) * sigma; out += z + gauss(rnd) * sigma
        }
        val xs = corners.map { it.first }; val zs = corners.map { it.second }
        val x0 = xs.min(); val x1 = xs.max(); val z0 = zs.min(); val z1 = zs.max()
        var x = x0 + spacing / 2
        while (x < x1) {
            var z = z0 + spacing / 2
            while (z < z1) {
                if (inside(corners, x, z)) { add(x, 0f, z); add(x, height, z) }
                z += spacing
            }
            x += spacing
        }
        for (i in corners.indices) {
            val (ax, az) = corners[i]; val (bx, bz) = corners[(i + 1) % corners.size]
            val len = hypot(bx - ax, bz - az)
            val n = floor(len / spacing).toInt()
            for (s in 0..n) {
                val t = s.toFloat() / n
                var y = spacing / 2
                while (y < height) { add(ax + (bx - ax) * t, y, az + (bz - az) * t); y += spacing }
            }
        }
        return out.toFloatArray()
    }

    fun box(w: Float, d: Float, h: Float, seed: Int = 7) =
        sample(listOf(0f to 0f, w to 0f, w to d, 0f to d), h, seed = seed)

    private fun gauss(r: Random): Float {
        val u1 = 1.0 - r.nextDouble(); val u2 = r.nextDouble()
        return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }

    private fun inside(poly: List<Pair<Float, Float>>, x: Float, z: Float): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, zi) = poly[i]; val (xj, zj) = poly[j]
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) c = !c
            j = i
        }
        return c
    }
}
