package com.example.arruler.ar

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** How trustworthy a hit is, best first (declaration order is the ranking). */
enum class HitQuality { PLANE, PLANE_EXTENDED, DEPTH, POINT }

/** What the hit surface is, from its normal against world +Y. */
enum class SurfaceKind { FLOOR, WALL, CEILING, OTHER }

/** The part of a hit the ranking needs; no ARCore types. */
data class HitCandidate(val quality: HitQuality, val distanceM: Float, val kind: SurfaceKind)

/** What the UI shows about the current centre hit. */
data class HitInfo(val quality: HitQuality, val kind: SurfaceKind)

/** Pure ranking and classification rules behind `ArSessionController.hitTest`. */
object HitRanking {
    /** A plane hit outside its polygon still counts if it is this close to it (meters). */
    const val EXTEND_MAX_M = 1.5f

    /** Normal Y at or above this (up) is a floor, at or below its negative a ceiling. */
    const val HORIZONTAL_NY = 0.8f

    /** |normal Y| at or below this is a wall. */
    const val VERTICAL_NY = 0.3f

    /** Index of the best candidate: best quality first, then nearest; -1 for an empty list. */
    fun best(candidates: List<HitCandidate>): Int {
        var bi = -1
        for (i in candidates.indices) {
            if (bi < 0 || better(candidates[i], candidates[bi])) bi = i
        }
        return bi
    }

    private fun better(a: HitCandidate, b: HitCandidate): Boolean =
        if (a.quality != b.quality) a.quality.ordinal < b.quality.ordinal else a.distanceM < b.distanceM

    fun kindFromNormalY(ny: Float): SurfaceKind = when {
        ny >= HORIZONTAL_NY -> SurfaceKind.FLOOR
        ny <= -HORIZONTAL_NY -> SurfaceKind.CEILING
        abs(ny) <= VERTICAL_NY -> SurfaceKind.WALL
        else -> SurfaceKind.OTHER
    }

    /**
     * Quality of a plane hit: PLANE inside the polygon, PLANE_EXTENDED within [EXTEND_MAX_M] of it
     * while the plane is tracking, else null (rejected).
     */
    fun planeQuality(inPolygon: Boolean, distToPolygonM: Float, tracking: Boolean): HitQuality? = when {
        inPolygon -> HitQuality.PLANE
        tracking && distToPolygonM <= EXTEND_MAX_M -> HitQuality.PLANE_EXTENDED
        else -> null
    }

    /** DEPTH and POINT hits are noisier than planes; the UI flags measurements that used them. */
    fun isLowConfidence(q: HitQuality): Boolean = q == HitQuality.DEPTH || q == HitQuality.POINT


    /** Tiny reticle label, e.g. 'Wall · depth', 'Floor · plane', 'Ceiling · points'. */
    fun label(info: HitInfo): String {
        val kind = when (info.kind) {
            SurfaceKind.FLOOR -> "Floor"
            SurfaceKind.WALL -> "Wall"
            SurfaceKind.CEILING -> "Ceiling"
            SurfaceKind.OTHER -> "Surface"
        }
        val source = when (info.quality) {
            HitQuality.PLANE -> "plane"
            HitQuality.PLANE_EXTENDED -> "plane (extended)"
            HitQuality.DEPTH -> "depth"
            HitQuality.POINT -> "points"
        }
        return "$kind · $source"
    }

    /**
     * Distance from (px, pz) to a polygon given as packed x,z pairs; 0 when inside (even-odd),
     * else the distance to the nearest edge.
     */
    fun polygonDistance(xz: FloatArray, px: Float, pz: Float): Float {
        val n = xz.size / 2
        if (n == 0) return Float.MAX_VALUE
        var inside = false
        var best = Float.MAX_VALUE
        var j = n - 1
        for (i in 0 until n) {
            val xi = xz[2 * i]; val zi = xz[2 * i + 1]
            val xj = xz[2 * j]; val zj = xz[2 * j + 1]
            if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) inside = !inside
            best = min(best, segmentDistance(px, pz, xj, zj, xi, zi))
            j = i
        }
        return if (inside) 0f else best
    }

    private fun segmentDistance(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = bx - ax; val dz = bz - az
        val len2 = dx * dx + dz * dz
        val t = if (len2 < 1e-12f) 0f else max(0f, min(1f, ((px - ax) * dx + (pz - az) * dz) / len2))
        return hypot(px - (ax + t * dx), pz - (az + t * dz))
    }
}
