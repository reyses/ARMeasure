package com.example.arruler.objscan

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Local-plane denoising (a one-step MLS flavour): every point is moved along the local surface normal onto the
 * plane through the centroid of its neighbours within [radius].
 *
 * Why: depth-from-motion noise (~4 mm sigma at a phone's working distance) makes a flat face a fuzzy slab; the extremes of
 * the slab sit ~3 sigma (12 mm) outside the true face, so a convex hull or oriented rectangle of the raw points is ~10 mm too
 * big per side (measured: 0.5 x 0.3 m box reads 0.519 x 0.319 at 3 mm noise). Projecting onto local planes collapses the
 * slab onto the face while leaving along-surface positions almost untouched. Choose radius ~ 3 x the depth noise:
 * a smaller ball sees only the tail of the slab and moves the point only part of the way.
 *
 * Cost of the trade: points near an edge get a tilted plane and move inward by ~0.2 x radius (an edge chamfer of 2-3 mm
 * at 12 mm), so volumes of very sharp objects read a fraction of a percent low; face interiors are unbiased.
 * Points with fewer than [minNeighbours] neighbours (isolated outliers) are left untouched for the outlier stage.
 * Complexity O(n * m) for m neighbours per ball (~100-400), one spatial hash build per iteration.
 */
object ObjectDenoise {
    fun smooth(points: FloatArray, radius: Float, iterations: Int = 2, minNeighbours: Int = 8, maxNeighbours: Int = 48): FloatArray {
        var cur = points
        val n = points.size / 3
        if (n == 0 || radius <= 0f) return points
        repeat(iterations) {
            val grid = PointGrid(cur, n, radius)
            val out = cur.copyOf()
            val buf = IntArray(4000)
            val a = DoubleArray(9)
            val ev = DoubleArray(3)
            for (i in 0 until n) {
                val m = grid.within(i, radius, buf)
                if (m < minNeighbours) continue
                // cap the work per ball: use every stride-th neighbour (the cell order is spatially arbitrary enough)
                val stride = if (m > maxNeighbours) m / maxNeighbours else 1
                val cnt = (m + stride - 1) / stride
                var cx = 0.0; var cy = 0.0; var cz = 0.0
                for (q in 0 until m step stride) { val j = buf[q]; cx += cur[j * 3]; cy += cur[j * 3 + 1]; cz += cur[j * 3 + 2] }
                cx /= cnt; cy /= cnt; cz /= cnt
                var xx = 0.0; var xy = 0.0; var xz = 0.0; var yy = 0.0; var yz = 0.0; var zz = 0.0
                for (q in 0 until m step stride) {
                    val j = buf[q]
                    val dx = cur[j * 3] - cx; val dy = cur[j * 3 + 1] - cy; val dz = cur[j * 3 + 2] - cz
                    xx += dx * dx; xy += dx * dy; xz += dx * dz; yy += dy * dy; yz += dy * dz; zz += dz * dz
                }
                a[0] = xx; a[1] = xy; a[2] = xz; a[3] = xy; a[4] = yy; a[5] = yz; a[6] = xz; a[7] = yz; a[8] = zz
                smallestEigenvector(a, ev)
                val dx = cur[i * 3] - cx; val dy = cur[i * 3 + 1] - cy; val dz = cur[i * 3 + 2] - cz
                val dist = dx * ev[0] + dy * ev[1] + dz * ev[2]
                out[i * 3] = (cur[i * 3] - dist * ev[0]).toFloat()
                out[i * 3 + 1] = (cur[i * 3 + 1] - dist * ev[1]).toFloat()
                out[i * 3 + 2] = (cur[i * 3 + 2] - dist * ev[2]).toFloat()
            }
            cur = out
        }
        return cur
    }

    /** Smallest-eigenvalue unit eigenvector of a symmetric 3x3 (row-major [m], destroyed) by cyclic Jacobi. */
    internal fun smallestEigenvector(m: DoubleArray, out: DoubleArray) {
        val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(12) {
            for ((p, q) in arrayOf(0 to 1, 0 to 2, 1 to 2)) {
                val apq = m[p * 3 + q]
                if (abs(apq) < 1e-18) continue
                val theta = (m[q * 3 + q] - m[p * 3 + p]) / (2 * apq)
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0..2) {
                    val akp = m[k * 3 + p]; val akq = m[k * 3 + q]
                    m[k * 3 + p] = c * akp - s * akq; m[k * 3 + q] = s * akp + c * akq
                }
                for (k in 0..2) {
                    val apk = m[p * 3 + k]; val aqk = m[q * 3 + k]
                    m[p * 3 + k] = c * apk - s * aqk; m[q * 3 + k] = s * apk + c * aqk
                }
                for (k in 0..2) {
                    val vkp = v[k * 3 + p]; val vkq = v[k * 3 + q]
                    v[k * 3 + p] = c * vkp - s * vkq; v[k * 3 + q] = s * vkp + c * vkq
                }
            }
        }
        var best = 0
        for (i in 1..2) if (m[i * 3 + i] < m[best * 3 + best]) best = i
        out[0] = v[best]; out[1] = v[3 + best]; out[2] = v[6 + best]
    }
}
