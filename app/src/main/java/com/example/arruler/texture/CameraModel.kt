package com.example.arruler.texture

import com.example.arruler.geometry.Vec3

/**
 * Pinhole intrinsics in pixels of an image [width] x [height] (origin top-left, v down), as ARCore's
 * `Camera.getImageIntrinsics()` reports them (focal length, principal point) for the CPU image.
 */
data class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val width: Int, val height: Int) {
    /** The same camera seen at another image size (ARCore reports intrinsics for its own `imageDimensions`). */
    fun scaledTo(w: Int, h: Int): Intrinsics {
        val sx = w.toFloat() / width
        val sy = h.toFloat() / height
        return Intrinsics(fx * sx, fy * sy, cx * sx, cy * sy, w, h)
    }
}

/**
 * Camera pose as ARCore `Pose.toMatrix`: 16 floats, column-major, camera to world. Camera axes are OpenGL:
 * +X right, +Y up, looking along -Z. Pixel projection: u = cx + fx * xc / depth, v = cy - fy * yc / depth, depth = -zc.
 */
class CameraPose(val m: FloatArray) {
    init { require(m.size == 16) { "pose must have 16 floats" } }

    val position: Vec3 get() = Vec3(m[12], m[13], m[14])

    /** Unit vector the camera looks along, in world coordinates (= -Z axis column). */
    val forward: Vec3 get() = Vec3(-m[8], -m[9], -m[10])

    /** World point to camera coordinates (x right, y up, z back). */
    fun toCamera(p: Vec3): Vec3 {
        val dx = p.x - m[12]; val dy = p.y - m[13]; val dz = p.z - m[14]
        return Vec3(
            m[0] * dx + m[1] * dy + m[2] * dz,
            m[4] * dx + m[5] * dy + m[6] * dz,
            m[8] * dx + m[9] * dy + m[10] * dz
        )
    }

    /** Pixel (u, v) and depth in meters of a world point, or null if depth <= [near] meters. */
    fun project(p: Vec3, k: Intrinsics, near: Float = 1e-3f): FloatArray? {
        val c = toCamera(p)
        val d = -c.z
        if (d <= near) return null
        return floatArrayOf(k.cx + k.fx * c.x / d, k.cy - k.fy * c.y / d, d)
    }

    companion object {
        /** Camera at [eye] looking at [target] with world up +Y (OpenGL camera convention). */
        fun lookAt(eye: Vec3, target: Vec3, up: Vec3 = Vec3(0f, 1f, 0f)): CameraPose {
            val f = (target - eye).normalized()
            var r = f.cross(up)
            if (r.length() < 1e-6f) r = f.cross(Vec3(0f, 0f, 1f))
            r = r.normalized()
            val u = r.cross(f)
            val b = -f
            return CameraPose(floatArrayOf(r.x, r.y, r.z, 0f, u.x, u.y, u.z, 0f, b.x, b.y, b.z, 0f, eye.x, eye.y, eye.z, 1f))
        }

        /** Angle in degrees between the viewing directions of two poses. */
        fun angleDeg(a: CameraPose, b: CameraPose): Double {
            val c = a.forward.dot(b.forward).toDouble().coerceIn(-1.0, 1.0)
            return Math.toDegrees(kotlin.math.acos(c))
        }
    }
}
