package com.example.arruler.geometry

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimplifyMeasure2DTest {
    @Test fun rdpNoisyLineCollapsesToTwoPoints() {
        val rnd = java.util.Random(42)
        val pts = (0..100).map { Vec2(it * 0.05f, (rnd.nextFloat() - 0.5f) * 0.01f) } // +-5 mm noise
        val out = Simplify.rdp(pts, 0.02f)
        assertEquals(2, out.size)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }

    @Test fun rdpKeepsCorner() {
        val pts = (0..10).map { Vec2(it * 0.1f, 0f) } + (1..10).map { Vec2(1f, it * 0.1f) }
        val out = Simplify.rdp(pts, 0.01f)
        assertEquals(listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(1f, 1f)).size, out.size)
        Tol.near(Vec2(1f, 0f), out[1])
    }

    @Test fun rdpSmallInputsAndZeroTolerance() {
        assertEquals(2, Simplify.rdp(listOf(Vec2(0f, 0f), Vec2(1f, 1f)), 1f).size)
        assertEquals(0, Simplify.rdp(emptyList(), 1f).size)
        val zig = listOf(Vec2(0f, 0f), Vec2(1f, 0.1f), Vec2(2f, 0f))
        assertEquals(3, Simplify.rdp(zig, 0f).size)
        assertEquals(2, Simplify.rdp(zig, 0.2f).size)
    }

    private val rect = Polygon2(listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4f, 3f), Vec2(0f, 3f)))
    private val ell = Polygon2(
        listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4f, 2f), Vec2(2f, 2f), Vec2(2f, 4f), Vec2(0f, 4f))
    )

    @Test fun wallLengths() {
        val l = Measure2D.wallLengths(rect)
        assertEquals(4, l.size)
        listOf(4f, 3f, 4f, 3f).forEachIndexed { i, e -> Tol.near(e, l[i]) }
        val le = Measure2D.wallLengths(ell)
        listOf(4f, 2f, 2f, 2f, 2f, 4f).forEachIndexed { i, e -> Tol.near(e, le[i]) }
        assertTrue(Measure2D.wallLengths(Polygon2(listOf(Vec2(0f, 0f)))).isEmpty())
    }

    @Test fun interiorAngles() {
        Measure2D.interiorAngles(rect).forEach { Tol.near(90f, it, 1e-3f) }
        // concave L: one reflex 270 corner at (2,2), five 90s
        val a = Measure2D.interiorAngles(ell)
        assertEquals(1, a.count { abs(it - 270f) < 1e-3f })
        assertEquals(5, a.count { abs(it - 90f) < 1e-3f })
        Tol.near(270f, a[3], 1e-3f)
        // same results for clockwise winding
        val b = Measure2D.interiorAngles(Polygon2(ell.points.reversed()))
        assertEquals(1, b.count { abs(it - 270f) < 1e-3f })
        // equilateral triangle
        val h = (kotlin.math.sqrt(3.0) / 2).toFloat()
        Measure2D.interiorAngles(Polygon2(listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(0.5f, h))))
            .forEach { Tol.near(60f, it, 1e-3f) }
        // sum of interior angles = (n-2)*180
        Tol.near(720f, Measure2D.interiorAngles(ell).sum(), 1e-2f)
    }

    @Test fun snapRightAnglesParallelogram89_91() {
        val a = Math.toRadians(89.0)
        val p0 = Vec2(0f, 0f); val p1 = Vec2(4f, 0f)
        val p2 = Vec2(4f + 2f * cos(a).toFloat(), 2f * sin(a).toFloat())
        val p3 = Vec2(p2.x - 4f, p2.y)
        val quad = Polygon2(listOf(p0, p1, p2, p3))
        val angles = Measure2D.interiorAngles(quad)
        Tol.near(89f, angles[0], 1e-2f); Tol.near(91f, angles[1], 1e-2f)

        val snapped = Measure2D.snapRightAngles(quad, 5f)
        Measure2D.interiorAngles(snapped).forEach { Tol.near(90f, it, 1e-3f) }
        // first edge fixed
        Tol.near(p0, snapped.points[0]); Tol.near(p1, snapped.points[1])
        // wall lengths stay close to the originals
        val l = Measure2D.wallLengths(snapped)
        Tol.near(4f, l[0], 1e-4f)
        Tol.near(2f, l[1], 0.05f)
    }

    @Test fun snapRightAnglesClockwiseAndConcave() {
        val a = Math.toRadians(88.0)
        val pts = listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4f + 3f * cos(a).toFloat(), 3f * sin(a).toFloat()))
        val quad = Polygon2(listOf(pts[0], pts[1], pts[2], Vec2(pts[2].x - 4f, pts[2].y)).reversed())
        Measure2D.interiorAngles(Measure2D.snapRightAngles(quad)).forEach { Tol.near(90f, it, 1e-3f) }

        // slightly skewed L-room: reflex corner snaps to 270, area stays close to 12
        val skew = Polygon2(
            listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4.05f, 2f), Vec2(2f, 2.03f), Vec2(2.02f, 4f), Vec2(0f, 4f))
        )
        val s = Measure2D.snapRightAngles(skew, 5f)
        Measure2D.interiorAngles(s).forEach {
            assertTrue("angle $it", abs(it - 90f) < 1e-2f || abs(it - 270f) < 1e-2f)
        }
        Tol.near(12f, s.area(), 0.3f)
    }

    @Test fun snapLeavesNonRightAnglesAndTinyPolygons() {
        val tri = Polygon2(listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(0f, 3f)))
        val out = Measure2D.snapRightAngles(tri, 5f) // 90 corner at (0,0) is vertex 0; 37/53 untouched
        val a = Measure2D.interiorAngles(out)
        Tol.near(90f, a[0], 1e-3f)
        val two = Polygon2(listOf(Vec2(0f, 0f), Vec2(1f, 0f)))
        assertEquals(two.points, Measure2D.snapRightAngles(two).points)
        // outside tolerance: 80 degree corner is not snapped
        val a80 = Math.toRadians(80.0)
        val q = Polygon2(listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4f + 2f * cos(a80).toFloat(), 2f * sin(a80).toFloat()),
            Vec2(2f * cos(a80).toFloat(), 2f * sin(a80).toFloat())))
        val r = Measure2D.interiorAngles(Measure2D.snapRightAngles(q, 5f))
        Tol.near(80f, r[0], 1e-2f)
    }
}
