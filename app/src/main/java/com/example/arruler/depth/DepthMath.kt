package com.example.arruler.depth

import kotlin.math.cos
import kotlin.math.sin

/**
 * Pinhole intrinsics in pixels of an image of [width] x [height].
 * Pure Kotlin so it is unit-testable without ARCore.
 */
data class Intrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int
)

/**
 * Depth-pixel to camera/world math.
 *
 * Camera convention is ARCore's (OpenGL): +X right, +Y up, -Z forward (the scene lies at negative z).
 * Image convention: u grows right, v grows DOWN, v = 0 is the top row.
 *
 * Sign check against Google's Depth Lab (Unity, left-handed, +Z forward), file
 * Assets/ARRealismDemos/Common/Scripts/DepthSource.cs `ComputeVertex`:
 *   x = (px - cx) * z / fx;  y = (py - cy) * z / fy;  vertex = (x, -y, z)
 * i.e. image-down becomes camera-up (Y negated). Unity keeps +Z forward; ARCore's right-handed
 * camera has forward = -Z, so z is negated here too. The same Y flip is in
 * Collider/Shaders/DepthProcessingCompute.compute (`pos = float4(x, -y, depth, 1)`).
 */
object DepthMath {

    /**
     * Scales intrinsics measured on the CPU image ([from] = Camera.getImageIntrinsics()) to a depth
     * image of [depthWidth] x [depthHeight] (same field of view, lower resolution).
     */
    fun scaleIntrinsics(from: Intrinsics, depthWidth: Int, depthHeight: Int): Intrinsics {
        val sx = depthWidth.toFloat() / from.width
        val sy = depthHeight.toFloat() / from.height
        return Intrinsics(from.fx * sx, from.fy * sy, from.cx * sx, from.cy * sy, depthWidth, depthHeight)
    }

    /**
     * Camera-space point of depth pixel ([u], [v]) with depth [depthMm] (millimeters measured along
     * the optical axis, which is what ARCore's DEPTH16 holds). Writes x, y, z (meters) to
     * [out] at [offset].
     */
    fun unproject(u: Float, v: Float, depthMm: Int, k: Intrinsics, out: FloatArray, offset: Int = 0) {
        val z = depthMm * 0.001f
        out[offset] = (u - k.cx) / k.fx * z
        out[offset + 1] = -(v - k.cy) / k.fy * z
        out[offset + 2] = -z
    }

    /**
     * Transforms (x, y, z) by the column-major 4x4 [m] (Pose.toMatrix layout: translation in
     * indices 12..14) and writes the result to [out] at [offset]. [out] may alias the input array.
     */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, offset: Int = 0) {
        val wx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val wy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val wz = m[2] * x + m[6] * y + m[10] * z + m[14]
        out[offset] = wx
        out[offset + 1] = wy
        out[offset + 2] = wz
    }

    /** Column-major rigid matrix for a rotation of [yawRad] about +Y followed by a translation. */
    fun yawMatrix(yawRad: Float, tx: Float = 0f, ty: Float = 0f, tz: Float = 0f): FloatArray {
        val c = cos(yawRad); val s = sin(yawRad)
        return floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            tx, ty, tz, 1f
        )
    }
}
