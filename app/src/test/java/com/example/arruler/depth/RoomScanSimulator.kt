package com.example.arruler.depth

import com.example.arruler.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Realistic depth-from-motion error for the room simulator. Per pixel: Gaussian with [model]'s
 * sigma(z) = a + b z^2. Per frame (correlated over the whole image, which is what slices one wall into
 * several tilted planes once frames are fused): a depth scale error uniform in +-[scaleBias] and a camera
 * rotation error of uniform +-[tiltBiasDeg] about a random axis. Speckle: a fraction [speckle] of pixels
 * get a uniform depth between 0.3 m and the true depth (flying pixels / mismatches sit in front) and a
 * uniform confidence, so about 60 % of them fall under the sampler's 0.4 confidence cut.
 *
 * Defaults are calibrated in [DepthNoiseModel] (and asserted by RoomScanRealismTest): the old extractor slices
 * one simulated wall the way it sliced the owner's real one on 2026-10-03.
 */
data class DepthNoiseSim(
    val model: DepthNoiseModel = DepthNoiseModel.DEFAULT,
    val scaleBias: Float = 0.03f,
    val tiltBiasDeg: Float = 2.0f,
    val speckle: Float = 0.02f,
) {
    companion object {
        /** The old synthetic tests' world: 5 mm everywhere, no bias, no speckle. */
        val CLEAN = DepthNoiseSim(DepthNoiseModel(0.005f, 0f), 0f, 0f, 0f)
    }
}

/** A prismatic room: footprint [corners] (x, z) in order, floor at [floorY], ceiling at [ceilingY] (world meters). */
class SimRoom(val corners: List<Pair<Float, Float>>, val floorY: Float, val ceilingY: Float) {
    val height: Float get() = ceilingY - floorY

    val area: Float
        get() {
            var s = 0.0
            for (i in corners.indices) {
                val (ax, az) = corners[i]; val (bx, bz) = corners[(i + 1) % corners.size]
                s += ax.toDouble() * bz - bx.toDouble() * az
            }
            return abs(s / 2).toFloat()
        }

    fun inside(x: Double, z: Double): Boolean {
        var c = false
        var j = corners.size - 1
        for (i in corners.indices) {
            val (xi, zi) = corners[i]; val (xj, zj) = corners[j]
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) c = !c
            j = i
        }
        return c
    }

    /** Distance t > 0 along (o + t d) to the first surface, or NaN. */
    fun raycast(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Double {
        var best = Double.NaN
        fun take(t: Double) { if (t > 1e-6 && (best.isNaN() || t < best)) best = t }
        if (abs(dy) > 1e-12) {
            for (h in doubleArrayOf(floorY.toDouble(), ceilingY.toDouble())) {
                val t = (h - oy) / dy
                if (t > 0 && inside(ox + t * dx, oz + t * dz)) take(t)
            }
        }
        for (i in corners.indices) {
            val (ax, az) = corners[i]; val (bx, bz) = corners[(i + 1) % corners.size]
            val ex = (bx - ax).toDouble(); val ez = (bz - az).toDouble()
            val den = dx * ez - dz * ex
            if (abs(den) < 1e-12) continue
            val wx = ax - ox; val wz = az - oz
            val t = (wx * ez - wz * ex) / den
            val s = (wx * dz - wz * dx) / den
            if (t <= 0 || s < 0 || s > 1) continue
            val y = oy + t * dy
            if (y in floorY.toDouble()..ceilingY.toDouble()) take(t)
        }
        return best
    }

    /** Rigid transform: rotate by [yaw] about +Y, then translate (for placing a room relative to the session origin). */
    fun moved(yaw: Double, tx: Float, ty: Float, tz: Float): SimRoom {
        val c = cos(yaw); val s = sin(yaw)
        return SimRoom(corners.map { (x, z) -> (c * x + s * z + tx).toFloat() to (-s * x + c * z + tz).toFloat() }, floorY + ty, ceilingY + ty)
    }
}

/** A camera pose: position (world meters), yaw (0 looks down -Z, positive turns left), pitch (positive up), roll. */
class CamPose(val x: Double, val y: Double, val z: Double, val yaw: Double, val pitch: Double, val roll: Double = 0.0) {
    /** Column-major 4x4 (Pose.toMatrix layout); camera +X right, +Y up, -Z forward. */
    fun matrix(): FloatArray {
        val f = doubleArrayOf(-sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch))
        var r = doubleArrayOf(cos(yaw), 0.0, -sin(yaw))
        var u = cross(doubleArrayOf(-f[0], -f[1], -f[2]), r)
        if (roll != 0.0) {
            val c = cos(roll); val s = sin(roll)
            val r2 = DoubleArray(3) { c * r[it] + s * u[it] }
            val u2 = DoubleArray(3) { -s * r[it] + c * u[it] }
            r = r2; u = u2
        }
        return floatArrayOf(
            r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), 0f,
            u[0].toFloat(), u[1].toFloat(), u[2].toFloat(), 0f,
            (-f[0]).toFloat(), (-f[1]).toFloat(), (-f[2]).toFloat(), 0f,
            x.toFloat(), y.toFloat(), z.toFloat(), 1f,
        )
    }

    companion object {
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        /** Yaw that looks along the horizontal direction (dx, dz). */
        fun yawToward(dx: Double, dz: Double): Double = atan2(-dx, -dz)
    }
}

/**
 * Simulates a hand-held depth scan: per frame, ray-cast the true depth image of [SimRoom] from the camera,
 * corrupt it with [DepthNoiseSim] into a DEPTH16 + confidence image, and push it through the production
 * path ([DepthFrameSampler.process] -> world points with ranges -> [VoxelCloud]). Not just noise on points:
 * the per-frame scale and pose errors move whole frames, as on the device.
 */
class RoomScanSimulator(
    val noise: DepthNoiseSim = DepthNoiseSim(),
    val width: Int = 64,
    val height: Int = 48,
    hfovDeg: Double = 64.0,
) {
    private val fx = (width / 2.0 / tan(Math.toRadians(hfovDeg) / 2)).toFloat()
    private val k = Intrinsics(fx, fx, width / 2f, height / 2f, width, height)
    private val sampler = DepthFrameSampler(step = 1, minConfidence = 0.4f, minDepthM = 0.3f, maxDepthM = 6f)

    var framesUsed = 0
        private set

    fun scan(room: SimRoom, path: List<CamPose>, seed: Int, cloud: VoxelCloud = VoxelCloud()): VoxelCloud {
        val rnd = Random(seed)
        for (pose in path) {
            frame(room, pose, rnd)?.let { s -> cloud.addAll(s.xyz, s.count, s.range) }
        }
        return cloud
    }

    /** One simulated depth frame through the production unprojection; null when nothing was seen. */
    fun frame(room: SimRoom, pose: CamPose, rnd: Random): DepthSample? {
        val m = pose.matrix()
        val depth = ShortArray(width * height)
        val conf = ByteArray(width * height)
        val scale = 1.0 + (rnd.nextDouble() * 2 - 1) * noise.scaleBias
        val r = doubleArrayOf(m[0].toDouble(), m[1].toDouble(), m[2].toDouble())
        val u = doubleArrayOf(m[4].toDouble(), m[5].toDouble(), m[6].toDouble())
        val zc = doubleArrayOf(m[8].toDouble(), m[9].toDouble(), m[10].toDouble())
        var seen = 0
        for (v in 0 until height) for (px in 0 until width) {
            val cx = (px - k.cx) / k.fx.toDouble(); val cy = -(v - k.cy) / k.fy.toDouble()
            val dx = r[0] * cx + u[0] * cy - zc[0]
            val dy = r[1] * cx + u[1] * cy - zc[1]
            val dz = r[2] * cx + u[2] * cy - zc[2]
            val t = room.raycast(pose.x, pose.y, pose.z, dx, dy, dz)   // t = depth along the optical axis (camera z = -1)
            if (t.isNaN()) continue
            var meas = t * scale + gauss(rnd) * noise.model.sigma(t.toFloat())
            var c = 220
            if (noise.speckle > 0f && rnd.nextFloat() < noise.speckle) {
                meas = 0.3 + rnd.nextDouble() * (t - 0.3).coerceAtLeast(0.0)
                c = rnd.nextInt(256)      // mismatches get arbitrary confidence; the sampler drops those under 0.4
            }
            val mm = (meas * 1000).roundToInt()
            if (mm <= 0 || mm > 65535) continue
            depth[v * width + px] = mm.toShort()
            conf[v * width + px] = c.toByte()
            seen++
        }
        if (seen == 0) return null
        framesUsed++
        val reported = if (noise.tiltBiasDeg > 0f) tilted(m, rnd) else m
        return sampler.process(DepthFrameSampler.RawDepth(width, height, depth, conf, k, reported))
    }

    /** The pose the depth is unprojected with: true rotation times a small error about a random axis. */
    private fun tilted(m: FloatArray, rnd: Random): FloatArray {
        val ang = Math.toRadians((rnd.nextDouble() * 2 - 1) * noise.tiltBiasDeg)
        var ax = gauss(rnd); var ay = gauss(rnd); var az = gauss(rnd)
        val l = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-9)
        ax /= l; ay /= l; az /= l
        val c = cos(ang); val s = sin(ang); val t = 1 - c
        val rot = arrayOf(
            doubleArrayOf(t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay),
            doubleArrayOf(t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax),
            doubleArrayOf(t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c),
        )
        val out = m.copyOf()
        for (col in 0 until 3) for (row in 0 until 3) {
            out[col * 4 + row] = (rot[row][0] * m[col * 4] + rot[row][1] * m[col * 4 + 1] + rot[row][2] * m[col * 4 + 2]).toFloat()
        }
        return out
    }

    companion object {
        fun gauss(r: Random): Double {
            val u1 = 1.0 - r.nextDouble(); val u2 = r.nextDouble()
            return sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)
        }

        /** 4 x 5 x 2.5 m box, floor 1.4 m below the session origin, placed so the walk starts at the origin facing -Z. */
        fun box(w: Float = 4f, d: Float = 5f, h: Float = 2.5f): SimRoom =
            SimRoom(listOf(-w / 2 to -d / 2, w / 2 to -d / 2, w / 2 to d / 2, -w / 2 to d / 2), -1.4f, h - 1.4f)

        /** L-shaped room (two 3 m wide arms, 6 m long), floor 1.4 m below the origin. */
        fun lShape(h: Float = 2.5f): SimRoom =
            SimRoom(listOf(-3f to -3f, 3f to -3f, 3f to 0f, 0f to 0f, 0f to 3f, -3f to 3f), -1.4f, h - 1.4f)

        /**
         * A person walking a loop through [stations] (x, z) at hand height 1.4 m above [room]'s floor, pausing
         * at each station (for as long as walking [dwell] m would take) and turning slowly (one full turn every
         * ~[turnFrames] frames) while sweeping yaw +-35 deg and pitch between -55 and +55 deg (floor and
         * ceiling), with hand jitter. [frames] samples, ~10 Hz on the device.
         */
        fun sweep(room: SimRoom, stations: List<Pair<Float, Float>>, frames: Int, seed: Int, turnFrames: Int = 110, dwell: Double = 0.0): List<CamPose> {
            val rnd = Random(seed * 31 + 5)
            val loop = stations + stations.first()
            val seg = DoubleArray(loop.size - 1) { hypot((loop[it + 1].first - loop[it].first).toDouble(), (loop[it + 1].second - loop[it].second).toDouble()) + dwell }
            val total = seg.sum()
            val phase = rnd.nextDouble() * 2 * PI
            return List(frames) { f ->
                var s = total * f / frames
                var i = 0
                while (i < seg.size - 1 && s > seg[i]) { s -= seg[i]; i++ }
                val a = loop[i]; val b = loop[i + 1]
                val t = ((s - dwell) / (seg[i] - dwell)).coerceIn(0.0, 1.0)
                val x = a.first + (b.first - a.first) * t + gauss(rnd) * 0.02
                val z = a.second + (b.second - a.second) * t + gauss(rnd) * 0.02
                val yaw = phase + 2 * PI * f / turnFrames + 0.6 * sin(2 * PI * f / 23.0) + gauss(rnd) * 0.03
                val pitch = 0.96 * sin(2 * PI * f / 37.0 + phase) + gauss(rnd) * 0.03
                CamPose(x, room.floorY + 1.4 + gauss(rnd) * 0.03, z, yaw, pitch, gauss(rnd) * 0.03)
            }
        }

        /** Loop stations [inset] m inside a box room of [w] x [d] centred on the origin. */
        fun boxStations(w: Float = 4f, d: Float = 5f, inset: Float = 1.2f): List<Pair<Float, Float>> {
            val x = w / 2 - inset; val z = d / 2 - inset
            return listOf(-x to -z, x to -z, x to z, -x to z)
        }

        /** Loop stations along the middle of [lShape]'s arms. */
        fun lStations(): List<Pair<Float, Float>> = listOf(-1.5f to 1.8f, -1.5f to -1.5f, 1.8f to -1.5f, -1.2f to -1.2f)

        /** ARCore's planes as the tests feed them: floor / ceiling patches with a small height error. */
        fun arFloorAndCeiling(room: SimRoom, rnd: Random, err: Float = 0.01f, size: Float = 1.6f): List<ArPlaneObservation> {
            fun patch(y: Float, type: ArPlaneType, n: Vec3): ArPlaneObservation {
                val cx = room.corners.map { it.first }.average().toFloat(); val cz = room.corners.map { it.second }.average().toFloat()
                val h = size / 2
                val poly = listOf(Vec3(cx - h, y, cz - h), Vec3(cx + h, y, cz - h), Vec3(cx + h, y, cz + h), Vec3(cx - h, y, cz + h))
                return ArPlaneObservation(type, Vec3(cx, y, cz), n, poly, size, size)
            }
            return listOf(
                patch(room.floorY + (gauss(rnd) * err).toFloat(), ArPlaneType.HORIZONTAL_UP, Vec3(0f, 1f, 0f)),
                patch(room.ceilingY + (gauss(rnd) * err).toFloat(), ArPlaneType.HORIZONTAL_DOWN, Vec3(0f, -1f, 0f)),
            )
        }
    }
}
