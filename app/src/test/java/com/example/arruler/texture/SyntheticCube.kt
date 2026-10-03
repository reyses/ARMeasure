package com.example.arruler.texture

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.TriMesh
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A textured cube (half-size [half], centre [centre], turned by yaw about the vertical axis through the centre) rendered
 * by ray casting. Every face has a distinct base colour and an n x n checkerboard of base / 0.45 x base (odd n: the
 * centre cell is the base colour). Faces in the cube frame: 0 +x, 1 -x, 2 +y, 3 -y, 4 +z, 5 -z.
 */
class SyntheticCube(val half: Float = 0.1f, val cells: Int = 3, val centre: Vec3 = Vec3(0f, 0.1f, 0f)) {
    val faceColours = intArrayOf(0xE03030, 0x30E030, 0x3030E0, 0xE0E030, 0x30E0E0, 0xE030E0)

    fun faceCentre(f: Int): Vec3 {
        val s = if (f % 2 == 0) half else -half
        return when (f / 2) {
            0 -> Vec3(centre.x + s, centre.y, centre.z)
            1 -> Vec3(centre.x, centre.y + s, centre.z)
            else -> Vec3(centre.x, centre.y, centre.z + s)
        }
    }

    private fun colour(face: Int, a: Float, b: Float): Int {
        val i = ((a + half) / (2 * half) * cells).toInt().coerceIn(0, cells - 1)
        val j = ((b + half) / (2 * half) * cells).toInt().coerceIn(0, cells - 1)
        val base = faceColours[face]
        val f = if ((i + j) % 2 == 0) 1.0 else 0.45
        val r = (((base shr 16) and 0xFF) * f).toInt(); val g = (((base shr 8) and 0xFF) * f).toInt(); val bl = ((base and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** ARGB image of the cube (background mid-grey 0xFF303030) seen from [pose] with [k], cube turned by [yaw] radians. */
    fun render(pose: CameraPose, k: Intrinsics, yaw: Float = 0f): IntArray {
        val out = IntArray(k.width * k.height) { 0xFF303030.toInt() }
        val m = pose.m
        val c = cos(yaw); val s = sin(yaw)
        val ox = m[12] - centre.x; val oy = m[13] - centre.y; val oz = m[14] - centre.z
        // world -> cube frame: rotate by -yaw about +Y
        val lox = ox * c - oz * s; val loy = oy; val loz = ox * s + oz * c
        for (py in 0 until k.height) for (px in 0 until k.width) {
            val xc = (px + 0.5f - k.cx) / k.fx; val yc = -(py + 0.5f - k.cy) / k.fy; val zc = -1f
            val dx = m[0] * xc + m[4] * yc + m[8] * zc
            val dy = m[1] * xc + m[5] * yc + m[9] * zc
            val dz = m[2] * xc + m[6] * yc + m[10] * zc
            val ldx = dx * c - dz * s; val ldy = dy; val ldz = dx * s + dz * c
            var t0 = -1e9f; var t1 = 1e9f
            var ok = true
            val o = floatArrayOf(lox, loy, loz); val d = floatArrayOf(ldx, ldy, ldz)
            for (a in 0..2) {
                if (abs(d[a]) < 1e-9f) { if (abs(o[a]) > half) { ok = false; break } else continue }
                val ta = (-half - o[a]) / d[a]; val tb = (half - o[a]) / d[a]
                t0 = maxOf(t0, minOf(ta, tb)); t1 = minOf(t1, maxOf(ta, tb))
            }
            if (!ok || t0 > t1 || t1 < 0f || t0 < 0f) continue
            val hx = o[0] + d[0] * t0; val hy = o[1] + d[1] * t0; val hz = o[2] + d[2] * t0
            val ax = abs(hx); val ay = abs(hy); val az = abs(hz)
            out[py * k.width + px] = when {
                ax >= ay && ax >= az -> colour(if (hx > 0) 0 else 1, hy, hz)
                ay >= az -> colour(if (hy > 0) 2 else 3, hx, hz)
                else -> colour(if (hz > 0) 4 else 5, hx, hy)
            }
        }
        return out
    }

    /** The cube as an 8-vertex, 12-triangle outward-CCW mesh (yaw 0). Triangles 2f and 2f+1 belong to face f. */
    fun mesh(): TriMesh {
        val v = FloatArray(24)
        for (i in 0 until 8) {
            v[i * 3] = centre.x + if (i and 1 != 0) half else -half
            v[i * 3 + 1] = centre.y + if (i and 2 != 0) half else -half
            v[i * 3 + 2] = centre.z + if (i and 4 != 0) half else -half
        }
        val quads = arrayOf(intArrayOf(1, 3, 7, 5), intArrayOf(0, 4, 6, 2), intArrayOf(2, 6, 7, 3), intArrayOf(0, 1, 5, 4), intArrayOf(4, 5, 7, 6), intArrayOf(0, 2, 3, 1))
        val idx = ArrayList<Int>()
        for ((f, q) in quads.withIndex()) {
            var tris = listOf(q[0], q[1], q[2], q[0], q[2], q[3])
            val n = normalOf(v, tris[0], tris[1], tris[2])
            val fc = faceCentre(f)
            if (n.dot(Vec3(fc.x - centre.x, fc.y - centre.y, fc.z - centre.z)) < 0f) tris = listOf(q[0], q[2], q[1], q[0], q[3], q[2])
            idx.addAll(tris)
        }
        val nrm = FloatArray(24)
        for (i in 0 until 8) {
            val d = Vec3(v[i * 3] - centre.x, v[i * 3 + 1] - centre.y, v[i * 3 + 2] - centre.z).normalized()
            nrm[i * 3] = d.x; nrm[i * 3 + 1] = d.y; nrm[i * 3 + 2] = d.z
        }
        return TriMesh(v, nrm, idx.toIntArray())
    }

    private fun normalOf(v: FloatArray, a: Int, b: Int, c: Int): Vec3 {
        val u = Vec3(v[b * 3] - v[a * 3], v[b * 3 + 1] - v[a * 3 + 1], v[b * 3 + 2] - v[a * 3 + 2])
        val w = Vec3(v[c * 3] - v[a * 3], v[c * 3 + 1] - v[a * 3 + 1], v[c * 3 + 2] - v[a * 3 + 2])
        return u.cross(w)
    }

    companion object {
        /** Luma plane (bytes) of an ARGB image. */
        fun luma(argb: IntArray): ByteArray = ByteArray(argb.size) {
            val p = argb[it]
            (0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)).toInt().toByte()
        }

        /** Cameras on rings around [target]: [elevationsDeg] x [perRing] azimuths at [distance] meters. */
        fun orbit(target: Vec3, distance: Float, elevationsDeg: List<Double>, perRing: Int): List<CameraPose> {
            val out = ArrayList<CameraPose>()
            for ((r, el) in elevationsDeg.withIndex()) for (a in 0 until perRing) {
                val az = Math.toRadians(360.0 * a / perRing + r * 17.0); val e = Math.toRadians(el)
                val eye = Vec3(
                    target.x + distance * (cos(e) * cos(az)).toFloat(),
                    target.y + distance * sin(e).toFloat(),
                    target.z + distance * (cos(e) * sin(az)).toFloat()
                )
                out.add(CameraPose.lookAt(eye, target))
            }
            return out
        }

        @Suppress("unused")
        fun norm(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
    }
}
