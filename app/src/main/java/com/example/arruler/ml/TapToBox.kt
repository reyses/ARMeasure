package com.example.arruler.ml

import com.example.arruler.depth.Intrinsics
import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.IsolationStats
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.ObjectIsolation
import com.example.arruler.objscan.ObjectMeasurements
import com.example.arruler.objscan.ObjectMeasures
import com.example.arruler.objscan.SupportPlane
import kotlin.math.hypot
import kotlin.math.sqrt

/** One ML Kit detection: 2D box in sensor IMAGE_PIXELS (u right, v down), best label (null when unclassified) and confidence 0..1. */
data class Detection(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val label: String?, val confidence: Float, val trackingId: Int? = null,
) {
    val area: Float get() = (right - left) * (bottom - top)
    fun contains(u: Float, v: Float) = u in left..right && v in top..bottom

    /** Distance (px) from ([u], [v]) to the box; 0 inside. */
    fun distanceTo(u: Float, v: Float): Float {
        val dx = maxOf(left - u, 0f, u - right); val dy = maxOf(top - v, 0f, v - bottom)
        return hypot(dx, dy)
    }
}

/** World point -> sensor image pixels. */
interface PointProjector {
    /** Writes (u, v) to [out] and returns the depth along the optical axis (m), or <= 0 when the point is behind the camera. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray): Float
}

/**
 * Pinhole projector for ARCore's camera: [pose] = column-major camera-to-world matrix (Pose.toMatrix of
 * Camera.getPose(), the sensor-aligned pose the depth sampler uses), [k] = Camera.getImageIntrinsics() on the CPU image
 * (so (u, v) are the pixels of the image ML Kit saw). Camera space: +X right, +Y up, -Z forward; v grows down.
 */
class CameraProjector(private val pose: FloatArray, private val k: Intrinsics) : PointProjector {
    override fun project(x: Float, y: Float, z: Float, out: FloatArray): Float {
        val dx = x - pose[12]; val dy = y - pose[13]; val dz = z - pose[14]
        val cx = pose[0] * dx + pose[1] * dy + pose[2] * dz
        val cy = pose[4] * dx + pose[5] * dy + pose[6] * dz
        val cz = pose[8] * dx + pose[9] * dy + pose[10] * dz
        val depth = -cz
        if (depth <= 1e-4f) return depth
        out[0] = k.fx * cx / depth + k.cx
        out[1] = k.cy - k.fy * cy / depth
        return depth
    }
}

/** An aligned box proposed from one tap. [shape] is the primitive recognition of the isolated points (null if too few). */
class TapBox(
    val box: ObjectBox,
    val detection: Detection,
    val isolated: FloatArray,
    val measures: ObjectMeasurements,
    val stats: IsolationStats,
    val shape: ShapeRecognition?,
)

/** Tap -> detection -> aligned [ObjectBox]. Pure; no Android types. */
object TapToBox {
    /** Fallback radius: a detection whose box is within this fraction of the image diagonal of the tap counts. */
    const val NEAR_FRACTION = 0.15f

    /** Padding added on every side of the fitted footprint, and on top of the height (m). */
    const val PADDING = 0.01f

    /**
     * Detection under the tap ([u], [v] in sensor IMAGE_PIXELS of a [imageW] x [imageH] image):
     * 1. the detection whose box contains the tap (the smallest if several: the innermost object);
     * 2. else the nearest one whose box is within [NEAR_FRACTION] of the image diagonal;
     * 3. else null.
     */
    fun pick(dets: List<Detection>, u: Float, v: Float, imageW: Int, imageH: Int): Detection? {
        dets.filter { it.contains(u, v) }.minByOrNull { it.area }?.let { return it }
        val limit = NEAR_FRACTION * sqrt(imageW.toFloat() * imageW + imageH.toFloat() * imageH)
        val near = dets.minByOrNull { it.distanceTo(u, v) } ?: return null
        return if (near.distanceTo(u, v) <= limit) near else null
    }

    /**
     * 3D box for [det] from one frame's world depth points [world] (packed xyz, DepthSample.xyz):
     * 1. keep the points whose projection falls inside the 2D box and that lie more than [supportMargin] above [plane];
     * 2. range gate: median camera range of the points in the middle 40 % of the 2D box (the object, not the background it
     *    overlaps at its silhouette); keep points within +-[depthSlab] of it, then only the depth cluster around it
     *    (points sorted by range, split at gaps > [rangeGap]: a gap is a silhouette edge to the background);
     * 3. [ObjectIsolation] (outliers, largest connected component) inside a generous axis-aligned box of those points,
     *    [ObjectMeasures.measure] for the oriented footprint (min-area rectangle) and the height;
     * 4. [ObjectBox] aligned to the footprint, padded [PADDING] per side and on top, base on the plane.
     * The plane is horizontal (normal +Y) as everywhere in objscan. Null when fewer than [minPoints] points remain.
     */
    fun fitBox(
        world: FloatArray, projector: PointProjector, det: Detection, plane: SupportPlane,
        voxelSize: Float = 0.01f, supportMargin: Float = 0.012f, depthSlab: Float = 0.35f, minPoints: Int = 40,
        rangeGap: Float = 0.04f,
    ): TapBox? {
        val n = world.size / 3
        val uv = FloatArray(2)
        val sel = IntArray(n); val range = FloatArray(n); var m = 0
        val cu0 = det.left + 0.3f * (det.right - det.left); val cu1 = det.left + 0.7f * (det.right - det.left)
        val cv0 = det.top + 0.3f * (det.bottom - det.top); val cv1 = det.top + 0.7f * (det.bottom - det.top)
        val centreRanges = ArrayList<Float>()
        for (i in 0 until n) {
            val x = world[i * 3]; val y = world[i * 3 + 1]; val z = world[i * 3 + 2]
            if (plane.signedDistance(x, y, z) <= supportMargin) continue
            val r = projector.project(x, y, z, uv)
            if (r <= 0f || !det.contains(uv[0], uv[1])) continue
            sel[m] = i; range[m] = r; m++
            if (uv[0] in cu0..cu1 && uv[1] in cv0..cv1) centreRanges.add(r)
        }
        if (m < minPoints) return null
        val ref = if (centreRanges.size >= 5) median(centreRanges) else median(range.copyOf(m).toList())
        // depth-gap clustering: sort by range, split where consecutive ranges differ by more than rangeGap (a depth
        // discontinuity = silhouette edge), keep the cluster that contains the centre reference range
        val order = (0 until m).filter { kotlin.math.abs(range[it] - ref) <= depthSlab }.sortedBy { range[it] }
        if (order.isEmpty()) return null
        var refPos = 0
        for (q in order.indices) if (kotlin.math.abs(range[order[q]] - ref) < kotlin.math.abs(range[order[refPos]] - ref)) refPos = q
        var lo = refPos; var hi = refPos
        while (lo > 0 && range[order[lo]] - range[order[lo - 1]] <= rangeGap) lo--
        while (hi < order.size - 1 && range[order[hi + 1]] - range[order[hi]] <= rangeGap) hi++
        val kept = ArrayList<Int>(hi - lo + 1)
        for (q in lo..hi) kept.add(sel[order[q]])
        if (kept.size < minPoints) return null
        val pts = FloatArray(kept.size * 3)
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        var hMax = 0f
        for ((q, i) in kept.withIndex()) {
            pts[q * 3] = world[i * 3]; pts[q * 3 + 1] = world[i * 3 + 1]; pts[q * 3 + 2] = world[i * 3 + 2]
            x0 = minOf(x0, pts[q * 3]); x1 = maxOf(x1, pts[q * 3]); z0 = minOf(z0, pts[q * 3 + 2]); z1 = maxOf(z1, pts[q * 3 + 2])
            hMax = maxOf(hMax, plane.signedDistance(pts[q * 3], pts[q * 3 + 1], pts[q * 3 + 2]))
        }
        val planeY = plane.d / plane.normal.y
        val init = ObjectBox(
            Vec3((x0 + x1) / 2, planeY, (z0 + z1) / 2), 0f,
            (x1 - x0) + 0.02f, (z1 - z0) + 0.02f, hMax + 0.02f,
        )
        val iso = ObjectIsolation(voxelSize, supportMargin).isolate(pts, null, init, plane)
        if (iso.count < minPoints) return null
        val meas = ObjectMeasures.measure(iso.points, init, plane, voxelSize) ?: return null
        val fp = meas.footprint
        // footprint centre is in the init box frame (yaw 0): world offset from its centre
        val cx = init.centre.x + fp.centreX; val cz = init.centre.z + fp.centreZ
        // long side direction (cos a, sin a) in (x, z) -> ObjectBox x axis (cos yaw, -sin yaw) => yaw = -a
        val box = ObjectBox(
            Vec3(cx, planeY, cz), -fp.angle,
            fp.length + 2 * PADDING, fp.width + 2 * PADDING, meas.maxHeight + PADDING,
        )
        return TapBox(box, det, iso.points, meas, iso.stats, ShapeRecognizer.recognize(iso.points, plane))
    }

    private fun median(v: List<Float>): Float {
        val s = v.sorted()
        return s[s.size / 2]
    }
}
