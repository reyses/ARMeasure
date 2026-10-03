package com.example.arruler.objscan

/** Everything the phone computes for one object scan. Lengths in m, volumes in m^3. */
class ObjectScanOutput(
    val stats: IsolationStats,
    val measures: ObjectMeasurements,
    /** Null when the mesh was too large to build ([ObjectMeshBuilder.build] cell cap) or too sparse. */
    val mesh: TriMesh?,
    val meshVolume: Double?,
    /** Isolated points (world, packed xyz). */
    val isolated: FloatArray,
)

/** Isolation -> measures -> mesh in one call; pure, run it on a background dispatcher. */
object ObjectPipeline {
    const val MIN_POINTS = 100

    /**
     * [points] / [hits] come from [ObjectVoxelCloud]. Throws [IllegalStateException] with a user-readable message
     * when nothing usable is left (the caller turns it into a failed job).
     */
    fun run(
        points: FloatArray, hits: IntArray?, box: ObjectBox, plane: SupportPlane, quality: ObjectQuality,
    ): ObjectScanOutput {
        check(points.size / 3 >= MIN_POINTS) { "Too few depth points (${points.size / 3}); scan the object from more sides" }
        val iso = quality.isolation().isolate(points, hits, box, plane)
        check(iso.count >= MIN_POINTS) {
            "Only ${iso.count} points left inside the box above the surface; enlarge the box or scan closer"
        }
        val m = ObjectMeasures.measure(iso.points, box, plane, quality.voxelSize)
            ?: error("The object points could not be measured")
        val mesh = ObjectMeshBuilder.build(iso.points, box, plane, quality.voxelSize, dilate = quality.meshDilate)
        return ObjectScanOutput(iso.stats, m, mesh, mesh?.volume(), iso.points)
    }
}
