package com.example.arruler.objscan

import com.example.arruler.geometry.Vec3
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic scenes for the objscan tests. Seeded, sizes in meters. */
class Scene(val points: FloatArray, val objectPoints: Int)

object Fixtures {
    val origin = Vec3(1.0f, 0.8f, -0.5f)      // object base centre, deliberately far from the session origin
    val plane = SupportPlane.horizontal(0.8f)

    private fun addNoisy(out: ArrayList<Float>, rng: Random, l: Vec3, obj: ObjectBox, noise: Float) {
        val w = obj.toWorld(l)
        out.add(w.x + rng.nextGaussian().toFloat() * noise)
        out.add(w.y + rng.nextGaussian().toFloat() * noise)
        out.add(w.z + rng.nextGaussian().toFloat() * noise)
    }

    /** Surface points of a w x d x h box (no bottom) at ~[perCell] samples per (voxel x voxel). */
    fun boxScene(
        rng: Random, w: Float, d: Float, h: Float, yawDeg: Float, noise: Float, voxel: Float,
        perCell: Float = 4f, floorHalf: Float = 0.6f, outlierFrac: Float = 0.02f
    ): Scene {
        val obj = ObjectBox(origin, (yawDeg * PI / 180).toFloat(), w, d, h)
        val out = ArrayList<Float>()
        val dens = perCell / (voxel * voxel)
        val faces = listOf(w * d, d * h, d * h, w * h, w * h)
        for ((f, area) in faces.withIndex()) {
            val n = (area * dens).toInt()
            repeat(n) {
                val a = rng.nextFloat(); val b = rng.nextFloat()
                val l = when (f) {
                    0 -> Vec3((a - 0.5f) * w, h, (b - 0.5f) * d)
                    1 -> Vec3(-w / 2, b * h, (a - 0.5f) * d)
                    2 -> Vec3(w / 2, b * h, (a - 0.5f) * d)
                    3 -> Vec3((a - 0.5f) * w, b * h, -d / 2)
                    else -> Vec3((a - 0.5f) * w, b * h, d / 2)
                }
                addNoisy(out, rng, l, obj, noise)
            }
        }
        return finish(out, rng, obj, noise, voxel, perCell, floorHalf, outlierFrac) { x, z -> kotlin.math.abs(x) < w / 2 && kotlin.math.abs(z) < d / 2 }
    }

    /** Upright cylinder radius r, height h: lateral surface + top cap. */
    fun cylinderScene(
        rng: Random, r: Float, h: Float, noise: Float, voxel: Float,
        perCell: Float = 4f, floorHalf: Float = 0.6f, outlierFrac: Float = 0.02f
    ): Scene {
        val obj = ObjectBox(origin, 0f, 2 * r, 2 * r, h)
        val out = ArrayList<Float>()
        val dens = perCell / (voxel * voxel)
        repeat((2 * PI * r * h * dens).toInt()) {
            val a = rng.nextFloat() * 2 * PI.toFloat()
            addNoisy(out, rng, Vec3(r * cos(a), rng.nextFloat() * h, r * sin(a)), obj, noise)
        }
        repeat((PI * r * r * dens).toInt()) {
            val a = rng.nextFloat() * 2 * PI.toFloat(); val rr = r * sqrt(rng.nextFloat())
            addNoisy(out, rng, Vec3(rr * cos(a), h, rr * sin(a)), obj, noise)
        }
        return finish(out, rng, obj, noise, voxel, perCell, floorHalf, outlierFrac) { x, z -> x * x + z * z < r * r }
    }

    private fun finish(
        out: ArrayList<Float>, rng: Random, obj: ObjectBox, noise: Float, voxel: Float, perCell: Float,
        floorHalf: Float, outlierFrac: Float, insideFootprint: (Float, Float) -> Boolean
    ): Scene {
        val nObj = out.size / 3
        val dens = perCell / (voxel * voxel)
        // floor: table plane, with the object's footprint hidden
        val nFloor = ((2 * floorHalf) * (2 * floorHalf) * dens).toInt().coerceAtMost(120_000)
        var made = 0
        while (made < nFloor) {
            val l = Vec3((rng.nextFloat() * 2 - 1) * floorHalf, 0f, (rng.nextFloat() * 2 - 1) * floorHalf)
            if (insideFootprint(l.x, l.z)) continue
            addNoisy(out, rng, l, obj, noise); made++
        }
        val total = out.size / 3
        repeat((total * outlierFrac).toInt()) {
            val l = Vec3((rng.nextFloat() * 2 - 1) * 0.75f, rng.nextFloat() * 0.9f - 0.1f, (rng.nextFloat() * 2 - 1) * 0.75f)
            val w = obj.toWorld(l)
            out.add(w.x); out.add(w.y); out.add(w.z)
        }
        return Scene(out.toFloatArray(), nObj)
    }

    class Result(
        val iso: IsolationResult, val m: ObjectMeasurements?, val mesh: TriMesh?, val box: ObjectBox
    )

    /** Full pipeline as the app would run it: object-local voxel cloud -> isolation -> measures -> mesh. */
    fun run(scene: Scene, userBox: ObjectBox, q: ObjectQuality): Result {
        val cloud = ObjectVoxelCloud(userBox, q.voxelSize)
        cloud.addAll(scene.points)
        val pts = cloud.points()
        val hits = cloud.hitsFor(pts)
        val iso = q.isolation().isolate(pts, hits, userBox, plane)
        val m = ObjectMeasures.measure(iso.points, userBox, plane, q.voxelSize)
        val mesh = ObjectMeshBuilder.build(iso.points, userBox, plane, q.voxelSize, q.meshDilate)
        return Result(iso, m, mesh, userBox)
    }

    fun userBox(w: Float, d: Float, h: Float, yawDeg: Float, slack: Float) =
        ObjectBox(origin, (yawDeg * PI / 180).toFloat(), w + 2 * slack, d + 2 * slack, h + slack)
}
