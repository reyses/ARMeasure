package com.example.arruler.ar

import com.example.arruler.geometry.ColorRamp
import com.example.arruler.measure.MeasurePoint
import dev.romainguy.kotlin.math.Quaternion
import kotlin.math.sqrt

/** One detected plane to draw: [id] is stable per ARCore plane, [alpha] already clamped. */
class SurfacePatch(val id: Int, val kind: SurfaceKind, val worldPolygon: List<MeasurePoint>, val alpha: Float)

/** Pure geometry of the SURFACES overlay: no ARCore or SceneView types. */
object SurfaceMath {
    const val ALPHA_MIN = 0.15f
    const val ALPHA_MAX = 0.45f

    /** Area (m2) at and above which a patch is drawn at [ALPHA_MAX]. */
    const val ALPHA_FULL_AREA_M2 = 6f
    const val ALPHA_BUCKETS = 4

    /** Opacity grows linearly with the polygon's area (m2) and is clamped to 0.15..0.45. */
    fun alphaFromArea(areaM2: Float): Float =
        (ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * (areaM2 / ALPHA_FULL_AREA_M2)).coerceIn(ALPHA_MIN, ALPHA_MAX)

    /** Quantises an alpha to one of [ALPHA_BUCKETS] shared material instances. */
    fun alphaBucket(alpha: Float): Int {
        val step = (ALPHA_MAX - ALPHA_MIN) / (ALPHA_BUCKETS - 1)
        return ((alpha - ALPHA_MIN) / step + 0.5f).toInt().coerceIn(0, ALPHA_BUCKETS - 1)
    }

    fun bucketAlpha(bucket: Int): Float =
        ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * bucket.coerceIn(0, ALPHA_BUCKETS - 1) / (ALPHA_BUCKETS - 1)

    /** Polygon area (m2) in 3D, from the Newell vector. */
    fun area(poly: List<MeasurePoint>): Float {
        val n = newell(poly)
        return 0.5f * sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
    }

    private fun newell(p: List<MeasurePoint>): FloatArray {
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            nx += (a.y - b.y) * (a.z + b.z)
            ny += (a.z - b.z) * (a.x + b.x)
            nz += (a.x - b.x) * (a.y + b.y)
        }
        return floatArrayOf(nx, ny, nz)
    }

    /**
     * A polygon in its own plane: [centroid], the rotation taking +Z onto the plane normal (the
     * Shape geometry lies in XY facing +Z) and the vertices as 2D coordinates along the rotated
     * X and Y axes, counter-clockwise seen from +Z.
     */
    class Frame(val centroid: MeasurePoint, val rotation: Quaternion, val local: List<Pair<Float, Float>>)

    /** Null for fewer than 3 points or a degenerate (zero-area) polygon. */
    fun frameOf(poly: List<MeasurePoint>): Frame? {
        if (poly.size < 3) return null
        val nw = newell(poly)
        val len = sqrt(nw[0] * nw[0] + nw[1] * nw[1] + nw[2] * nw[2])
        if (len < 1e-9f) return null
        val nx = nw[0] / len; val ny = nw[1] / len; val nz = nw[2] / len
        val cx = poly.map { it.x }.sum() / poly.size
        val cy = poly.map { it.y }.sum() / poly.size
        val cz = poly.map { it.z }.sum() / poly.size
        val q = rotationFromZTo(nx, ny, nz)
        val u = rotate(q, 1f, 0f, 0f)
        val v = rotate(q, 0f, 1f, 0f)
        val local = poly.map { p ->
            val dx = p.x - cx; val dy = p.y - cy; val dz = p.z - cz
            (dx * u[0] + dy * u[1] + dz * u[2]) to (dx * v[0] + dy * v[1] + dz * v[2])
        }
        return Frame(MeasurePoint(cx, cy, cz), q, local)
    }

    /** Shortest rotation taking +Z onto the unit vector (dx, dy, dz). */
    fun rotationFromZTo(dx: Float, dy: Float, dz: Float): Quaternion {
        if (dz < -0.99999f) return Quaternion(1f, 0f, 0f, 0f)
        // axis = z x dir = (-dy, dx, 0); w = 1 + z.dir
        val w = 1f + dz
        val l = sqrt(dy * dy + dx * dx + w * w)
        return Quaternion(-dy / l, dx / l, 0f, w / l)
    }

    /** Rotates the vector by the unit quaternion. */
    fun rotate(q: Quaternion, x: Float, y: Float, z: Float): FloatArray {
        val tx = 2f * (q.y * z - q.z * y)
        val ty = 2f * (q.z * x - q.x * z)
        val tz = 2f * (q.x * y - q.y * x)
        return floatArrayOf(
            x + q.w * tx + (q.y * tz - q.z * ty),
            y + q.w * ty + (q.z * tx - q.x * tz),
            z + q.w * tz + (q.x * ty - q.y * tx),
        )
    }

    /** Signed area of a 2D polygon; positive when counter-clockwise. */
    fun signedArea2d(p: List<Pair<Float, Float>>): Float {
        var s = 0f
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.first * b.second - b.first * a.second
        }
        return s / 2f
    }

    /**
     * Triangle fan from the polygon's centroid (convex polygons): index triples into the vertex
     * list with the centroid appended last (index [vertexCount]).
     */
    fun fanTriangles(vertexCount: Int): List<IntArray> =
        if (vertexCount < 3) emptyList()
        else List(vertexCount) { i -> intArrayOf(vertexCount, i, (i + 1) % vertexCount) }

    // ---- depth confidence colouring ----

    /**
     * ARGB of one depth pixel: transparent when there is no depth (0 mm), else a red (low
     * confidence) to yellow to green (high) ramp at [alpha] (0..255).
     */
    fun confidenceColor(confidence: Int, depthMm: Int, alpha: Int = 102): Int {
        if (depthMm <= 0) return 0
        return ColorRamp.argb((confidence and 0xFF) / 255f, alpha)
    }
}
