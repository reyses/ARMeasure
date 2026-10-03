package com.example.arruler.objscan

import com.example.arruler.depth.VoxelCloud
import com.example.arruler.geometry.Vec3

/**
 * Scan quality presets. The voxel size is an explicit parameter of every objscan stage (isolation,
 * measures, mesh, cloud); a preset only bundles consistent values.
 *
 *  - [voxelSize] (m): QUICK 5 mm, FINE 3 mm (FINE: mid/high phones; ~2.7x more voxels per surface).
 *  - [supportMargin] (m): points closer than this to the support plane are dropped. It has to exceed
 *    the plane-fit error plus the depth noise of the table surface, otherwise a fuzz layer of table points
 *    survives and fattens the footprint. Pixel depth-from-motion noise is ~4 mm sigma at 0.5 m, a
 *    voxel mean over a few hits lowers that to ~2 mm, plus ~2 mm plane error: 8 mm (1.6 voxels) at
 *    5 mm voxels and 5 mm (1.7 voxels) at 3 mm voxels, where voxel averaging is thinner but the cut
 *    removes less of the object's own base (the base is restored by projecting onto the plane).
 *  - [outlierK], [outlierStdRatio]: statistical outlier removal parameters (k nearest neighbours, mean + ratio*std).
 *  - [denoiseRadius] (m): local-plane denoise ball, ~3 x the 4 mm depth noise, see [ObjectDenoise] (0 = off).
 *  - [meshDilate]: closing radius (voxels) used before the mesh is meshed.
 */
enum class ObjectQuality(
    val voxelSize: Float,
    val supportMargin: Float,
    val outlierK: Int,
    val outlierStdRatio: Float,
    val meshDilate: Int,
    val denoiseRadius: Float
) {
    QUICK(0.005f, 0.008f, 8, 2.0f, 1, 0.012f),
    FINE(0.003f, 0.005f, 8, 2.0f, 1, 0.012f);

    fun isolation(minHits: Int = 1) =
        ObjectIsolation(voxelSize, supportMargin, outlierK, outlierStdRatio, minHits, denoiseRadius)
}

/**
 * Voxel cloud centred on the object box instead of the AR session origin. [VoxelCloud] packs 10 bits
 * per axis centred on the WORLD origin (+-512 voxels = +-1.54 m at 3 mm), which is wrong for an object
 * 3 m from where tracking started. This wrapper subtracts [origin] (normally the box centre) on the way in
 * and adds it back on the way out, so the usable range is +-512 voxels around the object
 * (+-2.56 m at 5 mm, +-1.54 m at 3 mm), ample for a 20 cm object and its surroundings. The origin is
 * fixed at construction (float precision of the subtraction is ~0.1 mm at 10 m from the session origin).
 */
class ObjectVoxelCloud(val origin: Vec3, val voxelSize: Float, maxVoxels: Int = 400_000) {
    private val inner = VoxelCloud(voxelSize, maxVoxels)

    constructor(box: ObjectBox, voxelSize: Float, maxVoxels: Int = 400_000) : this(box.volumeCentre(), voxelSize, maxVoxels)

    val count: Int get() = inner.count
    val rejectedOutOfRange: Long get() = inner.rejectedOutOfRange
    val evicted: Long get() = inner.evicted

    fun add(x: Float, y: Float, z: Float): Boolean = inner.add(x - origin.x, y - origin.y, z - origin.z)

    fun addAll(points: FloatArray, n: Int = points.size / 3) {
        for (i in 0 until n) add(points[i * 3], points[i * 3 + 1], points[i * 3 + 2])
    }

    /** World-space voxel mean positions with >= [minHits] hits. */
    fun points(minHits: Int = 1): FloatArray {
        val p = inner.points(minHits)
        for (i in 0 until p.size / 3) {
            p[i * 3] += origin.x; p[i * 3 + 1] += origin.y; p[i * 3 + 2] += origin.z
        }
        return p
    }

    /** Hit counts matching [points] order is NOT guaranteed by the cloud, so look hits up per point. */
    fun hitsAt(x: Float, y: Float, z: Float): Int = inner.hitsAt(x - origin.x, y - origin.y, z - origin.z)

    /** Hit counts for [points] (same order), for passing to [ObjectIsolation.isolate]. */
    fun hitsFor(points: FloatArray): IntArray =
        IntArray(points.size / 3) { hitsAt(points[it * 3], points[it * 3 + 1], points[it * 3 + 2]) }

    fun clear() = inner.clear()
}
