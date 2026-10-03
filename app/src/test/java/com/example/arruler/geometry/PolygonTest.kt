package com.example.arruler.geometry

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonTest {
    private fun poly(vararg xy: Float) = Polygon2((xy.indices step 2).map { Vec2(xy[it], xy[it + 1]) })

    private val unitSquare = poly(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    private val triangle345 = poly(0f, 0f, 4f, 0f, 0f, 3f)
    // L-shaped room: 4x4 square minus 2x2 corner = 12
    private val ell = poly(0f, 0f, 4f, 0f, 4f, 2f, 2f, 2f, 2f, 4f, 0f, 4f)
    private val bowTie = poly(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f)

    @Test fun areas() {
        Tol.near(1f, unitSquare.area())
        Tol.near(6f, triangle345.area())
        Tol.near(12f, ell.area())
        // concave arrowhead: (0,0),(4,2),(0,4),(1,2) -> area 6 (triangle 8 minus notch 2)
        Tol.near(6f, poly(0f, 0f, 4f, 2f, 0f, 4f, 1f, 2f).area())
    }

    @Test fun signedAreaAndWinding() {
        Tol.near(1f, unitSquare.signedArea())
        assertFalse(unitSquare.isClockwise())
        val cw = Polygon2(unitSquare.points.reversed())
        Tol.near(-1f, cw.signedArea())
        Tol.near(1f, cw.area())
        assertTrue(cw.isClockwise())
    }

    @Test fun perimeters() {
        Tol.near(4f, unitSquare.perimeter())
        Tol.near(12f, triangle345.perimeter())
        Tol.near(16f, ell.perimeter())
    }

    @Test fun simplicity() {
        assertTrue(unitSquare.isSimple())
        assertTrue(ell.isSimple())
        assertTrue(triangle345.isSimple())
        assertFalse(bowTie.isSimple())
        assertFalse(poly(0f, 0f, 1f, 0f).isSimple())
        // vertex touching a non-adjacent edge
        assertFalse(poly(0f, 0f, 2f, 0f, 2f, 2f, 1f, 0f, 0f, 2f).isSimple())
    }

    @Test fun centroids() {
        Tol.near(Vec2(0.5f, 0.5f), unitSquare.centroid())
        Tol.near(Vec2(4f / 3f, 1f), triangle345.centroid())
        // L-shape: by symmetry on the diagonal; (4*4*(2,2) - 2*2*(3,3)) / 12 = (20/12, 20/12)
        Tol.near(Vec2(20f / 12f, 20f / 12f), ell.centroid())
        // zero-area fallback = vertex average
        Tol.near(Vec2(1f, 0f), poly(0f, 0f, 1f, 0f, 2f, 0f).centroid())
    }

    @Test fun boundingBox() {
        val b = ell.boundingBox()
        assertEquals(Box2(0f, 0f, 4f, 4f), b)
        Tol.near(4f, b.width); Tol.near(4f, b.height)
    }

    @Test fun tiltedSquareViaPolygon3() {
        // unit square rotated 30 deg about X, then 20 deg about Y, then translated
        fun xf(p: Vec3): Vec3 {
            val a = Math.toRadians(30.0); val c = Math.toRadians(20.0)
            val y1 = (p.y * cos(a) - p.z * sin(a)).toFloat()
            val z1 = (p.y * sin(a) + p.z * cos(a)).toFloat()
            val x2 = (p.x * cos(c) + z1 * sin(c)).toFloat()
            val z2 = (-p.x * sin(c) + z1 * cos(c)).toFloat()
            return Vec3(x2 + 1.5f, y1 + 0.7f, z2 - 2f)
        }
        val pts = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(1f, 1f, 0f), Vec3(0f, 1f, 0f)).map(::xf)
        val p3 = Polygon3(pts)
        Tol.near(1f, p3.area(), 1e-4f)
        Tol.near(4f, p3.perimeter(), 1e-4f)
        val (basis, flat) = p3.toPlanar()
        Tol.near(1f, flat.area(), 1e-4f)
        assertEquals(4, flat.points.size)
        Tol.near(0f, p3.heightAbove(basis, pts[2]), 1e-4f)
        Tol.near(0.5f, kotlin.math.abs(p3.heightAbove(basis, basis.origin + basis.normal * 0.5f)), 1e-4f)
        val up = p3.heightAbove(basis, basis.origin + basis.normal * 0.5f)
        val down = p3.heightAbove(basis, basis.origin - basis.normal * 0.5f)
        Tol.near(-up, down)
    }

    @Test fun polygon3LShapeOnWall() {
        // L-room (area 12) lying in the x-y plane at z = 2
        val pts = ell.points.map { Vec3(it.x, it.y, 2f) }
        Tol.near(12f, Polygon3(pts).area())
        Tol.near(16f, Polygon3(pts).perimeter())
    }
}
