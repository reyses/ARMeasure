package com.example.arruler.objscan

import com.example.arruler.geometry.Vec3
import kotlin.math.PI

enum class BoxDim(val label: String) { WIDTH("W"), DEPTH("D"), HEIGHT("H") }

/**
 * Box placement logic of the OBJECT mode, kept pure (no ARCore, no Compose): the default box, the +/-
 * size steps, the rotate step, the 12 drawn edges, the "Fit" computation and the capture hints.
 * All lengths are meters.
 */
object ObjectPlacement {
    const val DEFAULT_SIDE = 0.25f
    const val MIN_SIDE = 0.02f
    const val MAX_SIDE = 2.0f
    const val STEP = 0.01f
    const val LONG_STEP = 0.05f
    const val ROTATE_STEP_DEG = 15f

    /** Fit looks at depth points above the plane within this horizontal distance of the tap. */
    const val FIT_RADIUS = 0.40f
    const val FIT_HEIGHT = 0.80f
    const val FIT_SLACK = 0.01f

    /** Elevation below which a bin counts as the "low band" of the dome (sin 30 deg). */
    const val LOW_BAND_Y = 0.5f

    /** Coverage at which the hint says "good enough" (docs/OBJECT_SCAN.md). */
    const val GOOD_COVERAGE = 0.6f

    /** The support plane and 25 cm cube dropped where the user tapped (the hit lies on the plane). */
    fun defaultBox(tap: Vec3): ObjectBox = ObjectBox(tap, 0f, DEFAULT_SIDE, DEFAULT_SIDE, DEFAULT_SIDE)

    fun supportPlane(tap: Vec3): SupportPlane = SupportPlane.horizontal(tap.y)

    /** 'Bigger' / 'Smaller' multiply all three sides by this / its inverse. */
    const val SCALE_STEP = 1.05f

    /** The box moves with a drag: the touch hit on the support plane plus the offset grabbed at the start of the drag. */
    fun grabOffset(box: ObjectBox, hit: Vec3): Pair<Float, Float> = (box.centre.x - hit.x) to (box.centre.z - hit.z)

    fun dragMove(box: ObjectBox, offset: Pair<Float, Float>, hit: Vec3): ObjectBox =
        box.moveTo(Vec3(hit.x + offset.first, box.centre.y, hit.z + offset.second))

    /** All three sides times [factor], each clamped to [MIN_SIDE]..[MAX_SIDE]; the base centre stays put. */
    fun scaleUniform(box: ObjectBox, factor: Float): ObjectBox = box.copy(
        w = (box.w * factor).coerceIn(MIN_SIDE, MAX_SIDE),
        d = (box.d * factor).coerceIn(MIN_SIDE, MAX_SIDE),
        h = (box.h * factor).coerceIn(MIN_SIDE, MAX_SIDE),
    )

    /** One +/- step on [dim] (positive [deltaM] grows), clamped to [MIN_SIDE]..[MAX_SIDE]. */
    fun resize(box: ObjectBox, dim: BoxDim, deltaM: Float): ObjectBox {
        fun c(v: Float) = (v + deltaM).coerceIn(MIN_SIDE, MAX_SIDE)
        return when (dim) {
            BoxDim.WIDTH -> box.copy(w = c(box.w))
            BoxDim.DEPTH -> box.copy(d = c(box.d))
            BoxDim.HEIGHT -> box.copy(h = c(box.h))
        }
    }

    fun rotate(box: ObjectBox, degrees: Float = ROTATE_STEP_DEG): ObjectBox {
        var yaw = box.yaw + degrees * PI.toFloat() / 180f
        val twoPi = (2 * PI).toFloat()
        while (yaw >= twoPi) yaw -= twoPi
        while (yaw < 0f) yaw += twoPi
        return box.withYaw(yaw)
    }

    /** The 12 edges of the box: 4 base, 4 top, 4 verticals, as world point pairs. */
    fun edges(box: ObjectBox): List<Pair<Vec3, Vec3>> {
        val c = box.corners()
        val out = ArrayList<Pair<Vec3, Vec3>>(12)
        for (i in 0 until 4) {
            out += c[i] to c[(i + 1) % 4]          // base ring
            out += c[4 + i] to c[4 + (i + 1) % 4]  // top ring
            out += c[i] to c[4 + i]                // verticals
        }
        return out
    }

    /** The region searched by Fit: [FIT_RADIUS] around the tap, [FIT_HEIGHT] up, axis aligned. */
    fun searchBox(tap: Vec3): ObjectBox = ObjectBox(tap, 0f, 2 * FIT_RADIUS, 2 * FIT_RADIUS, FIT_HEIGHT)

    /**
     * "Fit": a quick isolation pass (no denoise, [ObjectQuality.QUICK] margin and outlier settings) over the
     * depth [points] inside [searchBox], then the footprint's oriented rectangle and the maximum height give
     * the new box (grown by [slack] on every side, the base stays on the plane). Null when fewer than 50
     * points survive (nothing near the tap was seen yet).
     */
    fun fit(
        points: FloatArray, searchBox: ObjectBox, plane: SupportPlane,
        voxelSize: Float = ObjectQuality.QUICK.voxelSize, slack: Float = FIT_SLACK,
    ): ObjectBox? {
        val q = ObjectQuality.QUICK
        val iso = ObjectIsolation(voxelSize, q.supportMargin, q.outlierK, q.outlierStdRatio, 1, 0f)
        val kept = iso.isolate(points, null, searchBox, plane)
        if (kept.count < 50) return null
        val m = ObjectMeasures.measure(kept.points, searchBox, plane, voxelSize) ?: return null
        val r = m.footprint
        val centre = searchBox.toWorld(Vec3(r.centreX, 0f, r.centreZ))
        // long side direction in the search frame is (cos a, sin a) in (x, z) = world (cos a, 0, sin a);
        // the new box's width axis is world (cos yaw, 0, -sin yaw), so yaw = searchYaw - a
        val yaw = searchBox.yaw - r.angle
        return ObjectBox(
            centre, yaw,
            (r.length + 2 * slack).coerceIn(MIN_SIDE, MAX_SIDE),
            (r.width + 2 * slack).coerceIn(MIN_SIDE, MAX_SIDE),
            (m.maxHeight + slack).coerceIn(MIN_SIDE, MAX_SIDE),
        ).onPlane(plane)
    }

    /**
     * The capture hint. [distanceM] is the camera's distance to the box centre; [window] the distance window of
     * [CoverageDome.windowFor]. Priority: distance, nothing seen yet, an empty low band, good coverage, keep going.
     */
    fun hint(bins: List<DomeBin>, coverage: Float, distanceM: Float?, window: Pair<Float, Float>): String {
        if (distanceM != null) {
            if (distanceM < window.first) return "Back off a little"
            if (distanceM > window.second) return "Move closer to the object"
        }
        val observed = bins.count { it.observed }
        if (observed == 0) return "Walk slowly around the object"
        val low = bins.filter { it.direction.y < LOW_BAND_Y }
        if (low.isNotEmpty() && low.none { it.observed }) return "Lower the phone and circle again"
        if (coverage >= GOOD_COVERAGE) return "Good coverage - tap Finish"
        return "Keep circling the object"
    }
}
