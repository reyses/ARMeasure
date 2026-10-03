package com.example.arruler.depth

import com.example.arruler.geometry.Tol
import org.junit.Assert.assertEquals
import org.junit.Test

class DepthMathTest {

    // 640x480 CPU image, f = 400 px, centre (320, 240)  ->  depth 160x120 scales by 0.25
    private val cpu = Intrinsics(400f, 400f, 320f, 240f, 640, 480)
    private val k = DepthMath.scaleIntrinsics(cpu, 160, 120)

    @Test fun intrinsicsScaleWithImageSize() {
        assertEquals(100f, k.fx, 1e-4f); assertEquals(100f, k.fy, 1e-4f)
        assertEquals(80f, k.cx, 1e-4f); assertEquals(60f, k.cy, 1e-4f)
        assertEquals(160, k.width); assertEquals(120, k.height)
        // non-uniform scale (different aspect) scales each axis on its own
        val k2 = DepthMath.scaleIntrinsics(Intrinsics(500f, 600f, 250f, 300f, 500, 600), 100, 300)
        assertEquals(100f, k2.fx, 1e-4f); assertEquals(300f, k2.fy, 1e-4f)
        assertEquals(50f, k2.cx, 1e-4f); assertEquals(150f, k2.cy, 1e-4f)
    }

    @Test fun flatWallTwoMetersReconstructsToZMinusTwoAtEveryPixel() {
        val p = FloatArray(3)
        for (v in 0 until 120) for (u in 0 until 160) {
            DepthMath.unproject(u.toFloat(), v.toFloat(), 2000, k, p)
            assertEquals("z at ($u,$v)", -2f, p[2], 1e-4f)
        }
    }

    @Test fun principalPointIsOnTheOpticalAxis() {
        val p = FloatArray(3)
        DepthMath.unproject(k.cx, k.cy, 3500, k, p)
        assertEquals(0f, p[0], 1e-6f); assertEquals(0f, p[1], 1e-6f); assertEquals(-3.5f, p[2], 1e-5f)
    }

    @Test fun rightIsPlusXAndImageDownIsMinusY() {
        val p = FloatArray(3)
        DepthMath.unproject(k.cx + 50f, k.cy + 25f, 2000, k, p)
        assertEquals(1f, p[0], 1e-5f)      // 50 px at f=100 and 2 m -> 1 m to the right
        assertEquals(-0.5f, p[1], 1e-5f)   // 25 px below centre -> 0.5 m BELOW the axis
    }

    @Test fun identityPoseKeepsPoints() {
        val m = DepthMath.yawMatrix(0f)
        val o = FloatArray(3)
        DepthMath.transformPoint(m, 1f, 2f, -3f, o)
        Tol.near(com.example.arruler.geometry.Vec3(1f, 2f, -3f), com.example.arruler.geometry.Vec3(o[0], o[1], o[2]))
    }

    @Test fun yawNinetyDegreesRotatesPointsAndAppliesTranslation() {
        val m = DepthMath.yawMatrix((Math.PI / 2).toFloat(), tx = 10f, ty = 1f, tz = -5f)
        val o = FloatArray(3)
        // straight ahead (-Z) turns to world -X
        DepthMath.transformPoint(m, 0f, 0f, -2f, o)
        assertEquals(10f - 2f, o[0], 1e-5f); assertEquals(1f, o[1], 1e-5f); assertEquals(-5f, o[2], 1e-5f)
        // camera +X (right) turns to world -Z
        DepthMath.transformPoint(m, 1f, 0f, 0f, o)
        assertEquals(10f, o[0], 1e-5f); assertEquals(1f, o[1], 1e-5f); assertEquals(-6f, o[2], 1e-5f)
        // up stays up
        DepthMath.transformPoint(m, 0f, 1f, 0f, o)
        assertEquals(2f, o[1], 1e-5f)
    }

    @Test fun outMayAliasInput() {
        val m = DepthMath.yawMatrix((Math.PI / 2).toFloat())
        val a = floatArrayOf(0f, 0f, -2f)
        DepthMath.transformPoint(m, a[0], a[1], a[2], a)
        assertEquals(-2f, a[0], 1e-5f); assertEquals(0f, a[2], 1e-5f)
    }
}
