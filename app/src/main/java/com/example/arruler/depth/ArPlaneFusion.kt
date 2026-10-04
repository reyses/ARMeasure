package com.example.arruler.depth

import com.example.arruler.geometry.PlaneBasis
import com.example.arruler.geometry.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Uses ARCore's own planes as anchors for the depth planes. ARCore fits its planes to tracked feature
 * points, whose position is far better than depth-from-motion at room range (a floor polygon's height is
 * typically within 1-2 cm), so where both see the same surface ARCore's equation wins:
 *
 * - Floor: the lowest HORIZONTAL_UP plane of at least [MIN_HORIZONTAL_AREA]. A depth FLOOR (or a depth
 *   horizontal plane) within [HEIGHT_MATCH] of it is moved to ARCore's height (source FUSED). If depth has no
 *   floor and nothing in the cloud lies well below the ARCore plane, the ARCore plane becomes the floor
 *   (source ARCORE): depth often misses the floor, ARCore rarely does. An ARCore plane clearly BELOW the
 *   depth floor (by more than [HEIGHT_MATCH], and >= 1 m^2) means the depth "floor" was a table: it becomes OTHER.
 * - Ceiling: the same with the highest HORIZONTAL_DOWN plane (ARCore seldom finds ceilings).
 * - Walls: each VERTICAL plane of at least [MIN_WALL_AREA] is matched to a depth WALL (normals within
 *   [MATCH_ANGLE_DEG], ARCore centre within max(10 cm, 2 sigma) of the wall's plane); the wall takes ARCore's
 *   (gravity-flattened) normal and offset. An unmatched ARCore wall of at least [NEW_WALL_AREA] is added.
 */
internal object ArPlaneFusion {
    const val MIN_HORIZONTAL_AREA = 0.3f
    const val HEIGHT_MATCH = 0.30f
    const val MIN_WALL_AREA = 0.25f
    const val NEW_WALL_AREA = 1.0f
    const val MATCH_ANGLE_DEG = 12.0
    private val UP = Vec3(0f, 1f, 0f)

    /**
     * [lo] / [hi] are the cloud's 0.5th / 99.5th height percentiles and [centre] its centroid (NaN / null when
     * there is no cloud); [heightBand] is the extractor's floor / ceiling band.
     */
    fun fuse(
        planes: List<ExtractedPlane>,
        priors: List<ArPlaneObservation>,
        lo: Float,
        hi: Float,
        centre: Vec3?,
        heightBand: Float,
    ): List<ExtractedPlane> {
        if (priors.isEmpty()) return planes
        val out = planes.toMutableList()

        priors.filter { it.type == ArPlaneType.HORIZONTAL_UP && it.area >= MIN_HORIZONTAL_AREA }
            .minByOrNull { it.centre.y }
            ?.let { fuseHorizontal(out, it, PlaneKind.FLOOR, lo.isNaN() || it.centre.y <= lo + heightBand + 0.5f) }
        priors.filter { it.type == ArPlaneType.HORIZONTAL_DOWN && it.area >= MIN_HORIZONTAL_AREA }
            .maxByOrNull { it.centre.y }
            ?.let { fuseHorizontal(out, it, PlaneKind.CEILING, hi.isNaN() || it.centre.y >= hi - heightBand - 0.5f) }

        val cosMatch = cos(Math.toRadians(MATCH_ANGLE_DEG)).toFloat()
        val fused = HashSet<Int>()
        for (prior in priors.filter { it.type == ArPlaneType.VERTICAL && it.area >= MIN_WALL_AREA }.sortedByDescending { it.area }) {
            val n = Vec3(prior.normal.x, 0f, prior.normal.z)
            if (n.length() < 1e-3f) continue
            val nn = n.normalized()
            val match = out.indices
                .filter { it !in fused && out[it].kind == PlaneKind.WALL }
                .filter { abs(out[it].normal.dot(nn)) >= cosMatch }
                .filter { abs(out[it].distanceTo(prior.centre)) <= max(0.10f, 2f * out[it].sigma) }
                .maxByOrNull { out[it].inlierCount }
            if (match != null) {
                val w = out[match]
                val oriented = if (w.normal.dot(nn) < 0f) nn * -1f else nn
                out[match] = reoriented(w, oriented, oriented.dot(prior.centre))
                fused += match
            } else if (prior.area >= NEW_WALL_AREA) {
                val inward = if (centre != null && nn.dot(centre - prior.centre) < 0f) nn * -1f else nn
                out += fromPrior(prior, inward, PlaneKind.WALL)
                fused += out.size - 1
            }
        }
        return out
    }

    private fun fuseHorizontal(out: MutableList<ExtractedPlane>, prior: ArPlaneObservation, kind: PlaneKind, plausible: Boolean) {
        val y = prior.centre.y
        val current = out.withIndex().filter { it.value.kind == kind }.maxByOrNull { it.value.inlierCount }
        if (current != null) {
            val dy = y - current.value.centroid.y
            when {
                abs(dy) <= HEIGHT_MATCH -> out[current.index] = atHeight(current.value, y)
                kind == PlaneKind.FLOOR && dy < -HEIGHT_MATCH && prior.area >= 1f -> {
                    out[current.index] = current.value.with(kind = PlaneKind.OTHER)
                    out += fromPrior(prior, UP, kind)
                }
                kind == PlaneKind.CEILING && dy > HEIGHT_MATCH && prior.area >= 1f -> {
                    out[current.index] = current.value.with(kind = PlaneKind.OTHER)
                    out += fromPrior(prior, UP, kind)
                }
            }
            return
        }
        // a depth horizontal plane that the height bands did not classify, at ARCore's height
        val near = out.withIndex()
            .filter { abs(it.value.normal.y) > 0.96f && abs(it.value.centroid.y - y) <= HEIGHT_MATCH }
            .maxByOrNull { it.value.inlierCount }
        if (near != null) {
            out[near.index] = atHeight(near.value, y).with(kind = kind)
            return
        }
        if (plausible) out += fromPrior(prior, UP, kind)
    }

    /** The horizontal [p] moved to height [y] (same outline), marked FUSED. */
    private fun atHeight(p: ExtractedPlane, y: Float): ExtractedPlane {
        val c = Vec3(p.centroid.x, y, p.centroid.z)
        val basis = PlaneBasis(UP, c)
        val outline = ConvexHull.of(p.outline3d().map { basis.project(it) })
        return ExtractedPlane(
            UP, y, c, p.inlierCount, p.kind, basis, outline,
            p.sigma, true, p.freeTiltDeg, p.freeRms, p.fitRms, PlaneSource.FUSED, p.mergedFrom,
        )
    }

    /** [p] with normal [n] and offset [d] (outline re-projected), marked FUSED. */
    private fun reoriented(p: ExtractedPlane, n: Vec3, d: Float): ExtractedPlane {
        val c = p.centroid - n * (n.dot(p.centroid) - d)
        val basis = PlaneBasis(n, c)
        val outline = ConvexHull.of(p.outline3d().map { basis.project(it) })
        return ExtractedPlane(
            n, d, c, p.inlierCount, p.kind, basis, outline,
            p.sigma, true, p.freeTiltDeg, p.freeRms, p.fitRms, PlaneSource.FUSED, p.mergedFrom,
        )
    }

    private fun fromPrior(prior: ArPlaneObservation, n: Vec3, kind: PlaneKind): ExtractedPlane {
        val c = prior.centre
        val basis = PlaneBasis(n, c)
        val outline = ConvexHull.of(prior.polygon.map { basis.project(it) })
        return ExtractedPlane(n, n.dot(c), c, 0, kind, basis, outline, gravityAligned = true, source = PlaneSource.ARCORE)
    }
}
