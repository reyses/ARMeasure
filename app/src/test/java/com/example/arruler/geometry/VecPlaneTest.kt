package com.example.arruler.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class VecPlaneTest {
    @Test fun vec3Arithmetic() {
        val a = Vec3(1f, 2f, 3f); val b = Vec3(4f, -5f, 6f)
        assertEquals(Vec3(5f, -3f, 9f), a + b)
        assertEquals(Vec3(-3f, 7f, -3f), a - b)
        assertEquals(Vec3(2f, 4f, 6f), a * 2f)
        assertEquals(Vec3(-1f, -2f, -3f), -a)
        Tol.near(12f, a.dot(b))
        assertEquals(Vec3(27f, 6f, -13f), a.cross(b))
        Tol.near(5f, Vec3(3f, 4f, 0f).length())
        Tol.near(1f, Vec3(3f, 4f, 12f).normalized().length())
        Tol.near(5f, Vec3(1f, 1f, 1f).distanceTo(Vec3(4f, 5f, 1f)))
        assertEquals(Vec3.ZERO, Vec3.ZERO.normalized())
    }

    @Test fun vec2Arithmetic() {
        val a = Vec2(3f, 4f)
        assertEquals(Vec2(4f, 6f), a + Vec2(1f, 2f))
        assertEquals(Vec2(2f, 2f), a - Vec2(1f, 2f))
        assertEquals(Vec2(6f, 8f), a * 2f)
        Tol.near(11f, a.dot(Vec2(1f, 2f)))
        Tol.near(2f, a.cross(Vec2(1f, 2f)))
        Tol.near(5f, a.length())
        Tol.near(5f, a.distanceTo(Vec2(0f, 0f)))
    }

    @Test fun basisIsOrthonormalAndRoundTrips() {
        for (n in listOf(Vec3(0f, 1f, 0f), Vec3(1f, 1f, 1f), Vec3(0f, 0f, -2f), Vec3(1f, 0f, 0f))) {
            val b = PlaneBasis(n, Vec3(1f, 2f, 3f))
            Tol.near(1f, b.u.length()); Tol.near(1f, b.v.length()); Tol.near(1f, b.normal.length())
            Tol.near(0f, b.u.dot(b.v)); Tol.near(0f, b.u.dot(b.normal)); Tol.near(0f, b.v.dot(b.normal))
            Tol.near(b.normal, b.u.cross(b.v))
            val q = Vec2(0.7f, -1.3f)
            Tol.near(q, b.project(b.unproject(q)))
            Tol.near(0f, b.signedDistance(b.unproject(q)))
        }
    }

    @Test fun signedDistanceSign() {
        val b = PlaneBasis(Vec3(0f, 1f, 0f), Vec3(0f, 1f, 0f))
        Tol.near(2f, b.signedDistance(Vec3(5f, 3f, 5f)))
        Tol.near(-1f, b.signedDistance(Vec3(0f, 0f, 0f)))
    }

    @Test fun fitFromPointsFloor() {
        val pts = listOf(Vec3(0f, 0.5f, 0f), Vec3(2f, 0.5f, 0f), Vec3(2f, 0.5f, -3f), Vec3(0f, 0.5f, -3f))
        val b = PlaneBasis.fitFromPoints(pts)
        Tol.near(1f, kotlin.math.abs(b.normal.y))
        for (p in pts) Tol.near(0f, b.signedDistance(p))
        Tol.near(Vec3(1f, 0.5f, -1.5f), b.origin)
    }

    @Test fun fitFromCollinearDoesNotThrow() {
        val b = PlaneBasis.fitFromPoints(listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f)))
        Tol.near(1f, b.normal.length())
        Tol.near(0f, b.normal.x)
    }
}
