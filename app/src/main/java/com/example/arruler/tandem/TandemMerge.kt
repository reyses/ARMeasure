package com.example.arruler.tandem

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.ObjectMeasurements
import com.example.arruler.objscan.ObjectVoxelCloud
import com.example.arruler.objscan.SupportPlane
import com.example.arruler.objscan.TriMesh
import com.example.arruler.processing.BoxSpec
import com.example.arruler.processing.CloudData
import com.example.arruler.processing.Estimate
import com.example.arruler.processing.JobPackage
import com.example.arruler.processing.JobType
import com.example.arruler.processing.Measures
import com.example.arruler.processing.ObjectDims
import com.example.arruler.processing.PackageMeta
import com.example.arruler.processing.ProcessingStats
import com.example.arruler.processing.ResultJson
import com.example.arruler.processing.SupportPlaneSpec
import java.io.File
import com.example.arruler.objscan.ObjectQuality as DepthQuality
import com.example.arruler.processing.ObjectQuality as JobQuality

/** One phone's finished capture, in that phone's own ARCore frame. Points are the voxel cloud (world, packed xyz). */
class CaptureResult(
    val points: FloatArray,
    val hits: IntArray?,
    val box: ObjectBox,
    val plane: SupportPlane,
    val quality: DepthQuality,
    /** Camera centres of the keyframes (packed xyz), for [TriangleOwnership]; empty when no photos. */
    val cameraPositions: FloatArray = FloatArray(0),
)

/** What the app provides on each phone. */
interface TandemCapture {
    /** Begin capturing when the LOCAL monotonic clock reads [atLocalNs] (the helper has already converted the leader's time). */
    suspend fun start(req: TandemMessage.StartCapture, atLocalNs: Long)

    suspend fun stop(atLocalNs: Long)

    /** After [stop]: this phone's capture. */
    fun result(): CaptureResult
}

fun BoxSpec.toObjectBox(): ObjectBox {
    val w = size[0]; val h = size[1]; val d = size[2]
    return ObjectBox(Vec3(center[0], center[1] - h / 2f, center[2]), Math.toRadians(yawDeg.toDouble()).toFloat(), w, d, h)
}

fun SupportPlaneSpec.toSupportPlane(): SupportPlane = SupportPlane(Vec3(normal[0], normal[1], normal[2]), -d)

/** A helper's isolated cloud with the box and plane (helper frame) it was isolated with. */
class PartialObject(val points: FloatArray, val box: ObjectBox, val plane: SupportPlane, val voxelMm: Int?)

object TandemMerge {

    fun jobQuality(q: DepthQuality): JobQuality = if (q == DepthQuality.FINE) JobQuality.FINE else JobQuality.QUICK

    /** The helper's compact result: its isolated points as an ordinary point-job ZIP (processing/JobPackage), box and plane in its own frame. */
    fun writePartial(dest: File, isolated: FloatArray, box: ObjectBox, plane: SupportPlane, quality: DepthQuality, appVersion: String, created: String) {
        JobPackage.writePointJob(
            dest, JobType.OBJECT_MESH, CloudData.fromXyz(isolated),
            PackageMeta(appVersion, created, null, jobQuality(quality), box, plane),
        )
    }

    fun readPartial(file: File): PartialObject {
        val c = JobPackage.read(file)
        val cloud = requireNotNull(c.cloud) { "partial result without cloud" }
        val box = requireNotNull(c.manifest.box) { "partial result without box" }.toObjectBox()
        val plane = requireNotNull(c.manifest.supportPlane) { "partial result without plane" }.toSupportPlane()
        return PartialObject(cloud.xyz, box, plane, c.manifest.voxelMm)
    }

    /** Registration constraints from the two boxes and planes (the box base centre stands on the shared object axis). */
    fun constraints(
        leaderBox: ObjectBox, leaderPlane: SupportPlane, helperBox: ObjectBox, helperPlane: SupportPlane,
        yawPriorDeg: Float? = null, halfWidthDeg: Float = 30f,
    ) = FrameConstraints(
        mode = CaptureMode.SPIN,
        leaderPlane = leaderPlane, helperPlane = helperPlane,
        leaderAxis = leaderBox.centre, helperAxis = helperBox.centre,
        yawPriorDeg = yawPriorDeg, yawPriorHalfWidthDeg = halfWidthDeg,
    )

    /** Union of both clouds on the leader's voxel grid (voxels hit by both phones average their positions). */
    fun fuse(leaderPoints: FloatArray, helperInLeaderFrame: FloatArray, box: ObjectBox, voxelSize: Float): FloatArray {
        val c = ObjectVoxelCloud(box, voxelSize, maxVoxels = 2_000_000)
        c.addAll(leaderPoints)
        c.addAll(helperInLeaderFrame)
        return c.points(1)
    }

    /** The protocol result of an object job done by tandem (or by one phone when [singlePhone]). */
    fun resultJson(
        m: ObjectMeasurements, mesh: TriMesh?, meshVolume: Double?, singlePhone: Boolean, notes: List<String>, durationMs: Long,
    ): ResultJson {
        val variants = LinkedHashMap<String, Double>()
        variants["bounding_box"] = m.orientedBoxVolume.toDouble()
        variants["convex_hull"] = m.hullVolume.toDouble()
        variants["occupancy"] = m.occupancyVolume.toDouble()
        meshVolume?.let { variants["mesh"] = it }
        return ResultJson(
            jobType = JobType.OBJECT_MESH.wire,
            measures = Measures(
                heightM = Estimate.exact(m.maxHeight.toDouble()),
                volumeM3 = Estimate(m.volumeLow.toDouble(), m.volumeHigh.toDouble(), m.volumeRecommended.toDouble()),
                volumeVariantsM3 = variants,
                objectDims = ObjectDims(m.footprint.length.toDouble(), m.footprint.width.toDouble(), m.maxHeight.toDouble()),
            ),
            stats = ProcessingStats(
                "peer", durationMs,
                notes = listOf(if (singlePhone) "single-phone" else "tandem: 2 phones") + notes + (if (mesh == null) "mesh: not built" else "mesh: ${mesh.triangleCount} triangles"),
            ),
        )
    }
}
