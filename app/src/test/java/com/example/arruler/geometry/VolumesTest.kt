package com.example.arruler.geometry

import kotlin.math.PI
import kotlin.math.sqrt
import org.junit.Assert.assertNull
import org.junit.Test

class VolumesTest {
    private val pi = PI.toFloat()

    @Test fun volumeFormulas() {
        Tol.near(6f, Volumes.prism(2f, 3f))
        Tol.near(24f, Volumes.box(2f, 3f, 4f))
        Tol.near(pi * 4f * 5f, Volumes.cylinder(2f, 5f), 1e-3f)
        Tol.near(pi * 4f * 6f / 3f, Volumes.cone(2f, 6f), 1e-3f)
        Tol.near(4f / 3f * pi * 8f, Volumes.sphere(2f), 1e-3f)
        // frustum with r1 == r2 is a cylinder; r2 == 0 is a cone
        Tol.near(Volumes.cylinder(2f, 5f), Volumes.frustum(2f, 2f, 5f), 1e-3f)
        Tol.near(Volumes.cone(2f, 5f), Volumes.frustum(2f, 0f, 5f), 1e-3f)
        Tol.near(pi * 3f / 3f * (4f + 2f + 1f), Volumes.frustum(2f, 1f, 3f), 1e-3f)
        Tol.near(4f, Volumes.pyramid(4f, 3f))
    }

    @Test fun extrudedPolygonVolume() {
        val ell = listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4f, 2f), Vec2(2f, 2f), Vec2(2f, 4f), Vec2(0f, 4f))
        val p = Polygon3(ell.map { Vec3(it.x, 0f, it.y) })
        Tol.near(12f * 2.5f, Volumes.extrudedPolygon(p, 2.5f), 1e-3f)
    }

    @Test fun shapes() {
        val box = Shape.Box(2f, 3f, 4f)
        Tol.near(24f, box.volume()); Tol.near(52f, box.surfaceArea()!!)

        val cyl = Shape.Cylinder(1f, 2f)
        Tol.near(Volumes.cylinder(1f, 2f), cyl.volume()); Tol.near(2f * pi * 3f, cyl.surfaceArea()!!, 1e-3f)

        val cone = Shape.Cone(3f, 4f) // slant 5
        Tol.near(Volumes.cone(3f, 4f), cone.volume()); Tol.near(pi * 3f * 8f, cone.surfaceArea()!!, 1e-3f)

        val sph = Shape.Sphere(1.5f)
        Tol.near(Volumes.sphere(1.5f), sph.volume()); Tol.near(4f * pi * 2.25f, sph.surfaceArea()!!, 1e-3f)

        val fr = Shape.Frustum(3f, 0f, 4f) // degenerate to cone: slant 5
        Tol.near(Volumes.cone(3f, 4f), fr.volume(), 1e-3f)
        Tol.near(cone.surfaceArea()!!, fr.surfaceArea()!!, 1e-3f)
        val fr2 = Shape.Frustum(2f, 1f, 3f)
        Tol.near(pi * (4f + 1f + 3f * sqrt(10f)), fr2.surfaceArea()!!, 1e-3f)

        val pyr = Shape.Pyramid(4f, 3f)
        Tol.near(4f, pyr.volume()); assertNull(pyr.surfaceArea())

        val sq = Polygon3(listOf(Vec3(0f, 0f, 0f), Vec3(2f, 0f, 0f), Vec3(2f, 0f, 2f), Vec3(0f, 0f, 2f)))
        val ex = Shape.ExtrudedPolygon(sq, 3f)
        Tol.near(12f, ex.volume(), 1e-3f)
        Tol.near(2f * 4f + 8f * 3f, ex.surfaceArea()!!, 1e-3f)
    }
}
