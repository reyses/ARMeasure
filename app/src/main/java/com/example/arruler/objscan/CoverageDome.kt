package com.example.arruler.objscan

import com.example.arruler.geometry.Vec3
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** One viewing-direction bin: unit [direction] from the object towards the camera. */
data class DomeBin(val direction: Vec3, val observed: Boolean, val count: Int)

/**
 * Viewing-direction coverage tracker around an object.
 *
 * Bins = vertices of an icosphere (icosahedron subdivided [subdivisions] times, 162 vertices at 2) restricted to the upper
 * hemisphere plus [belowHorizonDeg] below the horizon (~100 bins). [observe] maps the direction object -> camera to
 * its nearest bin; a bin is "observed" after [minObservations] hits (hysteresis against a single jittery frame).
 * Frames are ignored when the camera is closer than [minDistance] or farther than [maxDistance] meters, or below the
 * horizon band. Distance window: see [windowFor] (small objects are scanned closely: 0.25-0.8 m below 30 cm largest
 * side, 0.3-2.0 m above).
 */
class CoverageDome(
    val centre: Vec3,
    val minDistance: Float = 0.3f,
    val maxDistance: Float = 2.0f,
    subdivisions: Int = 2,
    belowHorizonDeg: Float = 15f,
    val minObservations: Int = 3
) {
    constructor(box: ObjectBox, subdivisions: Int = 2) : this(
        box.volumeCentre(), windowFor(box.maxDimension()).first, windowFor(box.maxDimension()).second, subdivisions
    )

    private val dirs: List<Vec3>
    private val counts: IntArray
    private val minY = sin(-belowHorizonDeg * PI.toFloat() / 180f)

    init {
        dirs = icosphere(subdivisions).filter { it.y >= minY - 1e-4f }
        counts = IntArray(dirs.size)
    }

    val binCount: Int get() = dirs.size

    /** Records a camera position; returns the bin index that counted, or -1 when ignored. */
    fun observe(cameraPositionWorld: Vec3): Int {
        val v = cameraPositionWorld - centre
        val dist = v.length()
        if (dist < minDistance || dist > maxDistance) return -1
        val dir = v * (1f / dist)
        if (dir.y < minY) return -1
        var best = 0; var bestDot = -2f
        for (i in dirs.indices) { val d = dirs[i].dot(dir); if (d > bestDot) { bestDot = d; best = i } }
        counts[best]++
        return best
    }

    /** Fraction of bins observed, 0..1. */
    fun coverage(): Float = if (dirs.isEmpty()) 0f else counts.count { it >= minObservations }.toFloat() / dirs.size

    fun bins(): List<DomeBin> = dirs.indices.map { DomeBin(dirs[it], counts[it] >= minObservations, counts[it]) }

    fun reset() = counts.fill(0)

    companion object {
        /** Camera distance window (min, max) in meters for an object whose largest side is [maxDim] meters. */
        fun windowFor(maxDim: Float): Pair<Float, Float> = if (maxDim < 0.30f) 0.25f to 0.8f else 0.3f to 2.0f

        fun icosphere(subdivisions: Int): List<Vec3> {
            val t = (1f + sqrt(5f)) / 2f
            var verts = arrayListOf(
                Vec3(-1f, t, 0f), Vec3(1f, t, 0f), Vec3(-1f, -t, 0f), Vec3(1f, -t, 0f),
                Vec3(0f, -1f, t), Vec3(0f, 1f, t), Vec3(0f, -1f, -t), Vec3(0f, 1f, -t),
                Vec3(t, 0f, -1f), Vec3(t, 0f, 1f), Vec3(-t, 0f, -1f), Vec3(-t, 0f, 1f)
            ).map { it.normalized() }.toMutableList()
            var faces = listOf(
                intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
                intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
                intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
                intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1)
            )
            repeat(subdivisions) {
                val mid = HashMap<Long, Int>()
                fun midpoint(a: Int, b: Int): Int {
                    val key = minOf(a, b).toLong() * 100000 + maxOf(a, b)
                    return mid.getOrPut(key) { verts.add(((verts[a] + verts[b]) * 0.5f).normalized()); verts.size - 1 }
                }
                val nf = ArrayList<IntArray>()
                for (f in faces) {
                    val a = midpoint(f[0], f[1]); val b = midpoint(f[1], f[2]); val c = midpoint(f[2], f[0])
                    nf.add(intArrayOf(f[0], a, c)); nf.add(intArrayOf(f[1], b, a)); nf.add(intArrayOf(f[2], c, b)); nf.add(intArrayOf(a, b, c))
                }
                faces = nf
            }
            return verts
        }
    }
}
