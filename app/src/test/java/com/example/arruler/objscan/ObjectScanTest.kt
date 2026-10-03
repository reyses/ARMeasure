package com.example.arruler.objscan

import com.example.arruler.geometry.Vec3
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectScanTest {
    private fun dump(tag: String, r: Fixtures.Result) {
        val m = r.m!!
        println(
            "$tag: pts in=${r.iso.stats.input} box=${r.iso.stats.afterBox} sup=${r.iso.stats.afterSupport} " +
                "sor=${r.iso.stats.afterOutliers} cc=${r.iso.stats.afterComponent} | L=${m.footprint.length} W=${m.footprint.width} " +
                "H=${m.maxHeight} hull=${m.hullVolume} occ=${m.occupancyVolume} mesh=${r.mesh?.volume()} rec=${m.volumeRecommended}"
        )
    }

    // ---------- ObjectBox ----------
    @Test fun boxRoundTripAndContains() {
        val b = ObjectBox(Vec3(1f, 0.5f, 2f), 0.7f, 0.5f, 0.3f, 0.4f)
        val p = Vec3(1.1f, 0.9f, 2.05f)
        val back = b.toWorld(b.toLocal(p))
        assertEquals(p.x, back.x, 1e-5f); assertEquals(p.y, back.y, 1e-5f); assertEquals(p.z, back.z, 1e-5f)
        assertTrue(b.contains(b.volumeCentre()))
        assertTrue(!b.contains(Vec3(1f, 0.4f, 2f)))              // below the base
        assertTrue(b.corners().all { b.contains(it, 1e-4f) })
        assertEquals(8, b.corners().size)
        assertEquals(0.06f, b.volume(), 1e-6f)
        assertEquals(0.9f, b.scaled(2f).h * 1.125f, 1e-5f)
    }

    // ---------- isolation + measures on a box ----------
    @Test fun boxQuickIsolatesAndMeasures() {
        val s = Fixtures.boxScene(Random(11), 0.5f, 0.3f, 0.4f, 0f, 0.003f, ObjectQuality.QUICK.voxelSize)
        val r = Fixtures.run(s, Fixtures.userBox(0.5f, 0.3f, 0.4f, 0f, 0.01f), ObjectQuality.QUICK)
        dump("box0", r)
        checkBox(r, 0.5f, 0.3f, 0.4f, 0.01f, 0.05)
    }

    @Test fun boxYawed30() {
        val s = Fixtures.boxScene(Random(12), 0.5f, 0.3f, 0.4f, 30f, 0.003f, ObjectQuality.QUICK.voxelSize)
        val r = Fixtures.run(s, Fixtures.userBox(0.5f, 0.3f, 0.4f, 30f, 0.01f), ObjectQuality.QUICK)
        dump("box30", r)
        checkBox(r, 0.5f, 0.3f, 0.4f, 0.01f, 0.05)
        // box yawed 15 deg off the object: the oriented rectangle still finds 0.5 x 0.3
        val r2 = Fixtures.run(s, Fixtures.userBox(0.5f, 0.3f, 0.4f, 15f, 0.06f), ObjectQuality.QUICK)
        assertEquals(0.5f, r2.m!!.footprint.length, 0.012f)
        assertEquals(0.3f, r2.m.footprint.width, 0.012f)
    }

    private fun checkBox(r: Fixtures.Result, l: Float, w: Float, h: Float, tol: Float, hullTol: Double) {
        val st = r.iso.stats
        // floor and outliers are gone: kept points are close to the object-surface count
        assertTrue("floor and far points cropped", st.removedByBox > st.input / 10)
        assertTrue(st.removedBySupport + st.removedByBox > 0 && st.removedByOutliers > 0)
        val m = r.m!!
        assertEquals(l, m.footprint.length, tol)
        assertEquals(w, m.footprint.width, tol)
        assertEquals(h, m.maxHeight, tol)
        val v = (l * w * h).toDouble()
        assertEquals(v, m.hullVolume.toDouble(), v * hullTol)
        assertTrue(m.volumeLow <= m.volumeRecommended && m.volumeRecommended <= m.volumeHigh)
        assertEquals(v, m.occupancyVolume.toDouble(), v * 0.15)
        assertEquals(v, r.mesh!!.volume(), v * 0.08)
    }

    // ---------- 200 mm cube, 4 mm noise ----------
    private fun cube(q: ObjectQuality, seed: Long) {
        val s = Fixtures.boxScene(Random(seed), 0.2f, 0.2f, 0.2f, 20f, 0.004f, q.voxelSize, perCell = 3f)
        val r = Fixtures.run(s, Fixtures.userBox(0.2f, 0.2f, 0.2f, 20f, 0.01f), q)
        dump("cube200 $q", r)
        val m = r.m!!
        assertEquals(0.2f, m.footprint.length, 0.008f)
        assertEquals(0.2f, m.footprint.width, 0.008f)
        assertEquals(0.2f, m.maxHeight, 0.008f)
        assertEquals(0.008, m.hullVolume.toDouble(), 0.008 * 0.08)
        assertEquals(0.008, r.mesh!!.volume(), 0.008 * 0.12)
    }

    @Test fun cube200Quick() = cube(ObjectQuality.QUICK, 21)
    @Test fun cube200Fine() = cube(ObjectQuality.FINE, 22)

    // ---------- cylinders ----------
    @Test fun cylinderHullQuick() {
        val s = Fixtures.cylinderScene(Random(31), 0.15f, 0.3f, 0.003f, ObjectQuality.QUICK.voxelSize)
        val box = ObjectBox(Fixtures.origin, 0f, 0.32f, 0.32f, 0.32f)
        val r = Fixtures.run(s, box, ObjectQuality.QUICK)
        dump("cyl15", r)
        val v = PI * 0.15 * 0.15 * 0.3
        assertEquals(0.0212, v, 0.0001)
        assertEquals(v, r.m!!.hullVolume.toDouble(), v * 0.05)
    }

    @Test fun cylinderR10FineWithNoise() {
        val s = Fixtures.cylinderScene(Random(32), 0.1f, 0.2f, 0.004f, ObjectQuality.FINE.voxelSize, perCell = 3f)
        val box = ObjectBox(Fixtures.origin, 0f, 0.22f, 0.22f, 0.21f)
        val r = Fixtures.run(s, box, ObjectQuality.FINE)
        dump("cyl10", r)
        val v = PI * 0.1 * 0.1 * 0.2
        assertEquals(v, r.m!!.hullVolume.toDouble(), v * 0.10)
        assertEquals(0.2f, r.m.footprint.length, 0.01f)
        assertEquals(0.2f, r.m.maxHeight, 0.008f)
    }

    // ---------- marching cubes ----------
    private fun sphereMesh(res: Int, radius: Float): TriMesh {
        val half = 1.3f * radius
        val cell = 2 * half / (res - 1)
        val f = FloatArray(res * res * res)
        for (k in 0 until res) for (j in 0 until res) for (i in 0 until res) {
            val x = -half + i * cell; val y = -half + j * cell; val z = -half + k * cell
            f[i + res * (j + res * k)] = radius - sqrt(x * x + y * y + z * z)
        }
        return MarchingCubes.extract(f, res, res, res, 0f, cell, -half, -half, -half)
    }

    @Test fun marchingCubesSphereClosedAndVolume() {
        val r = 0.1f
        val mesh = sphereMesh(41, r)
        assertTrue(mesh.triangleCount > 1000)
        assertEquals("every edge shared by exactly 2 triangles", 0, mesh.badEdgeCount())
        assertEquals("consistent winding", 0, mesh.inconsistentEdgeCount())
        val v = 4.0 / 3 * PI * r * r * r
        assertEquals(v, mesh.volume(), v * 0.05)
        // normals point outward
        var outward = 0
        for (i in 0 until mesh.vertexCount) {
            val d = mesh.vertices[i * 3] * mesh.normals[i * 3] + mesh.vertices[i * 3 + 1] * mesh.normals[i * 3 + 1] + mesh.vertices[i * 3 + 2] * mesh.normals[i * 3 + 2]
            if (d > 0) outward++
        }
        assertEquals(mesh.vertexCount, outward)
    }

    @Test fun marchingCubesRandomFieldHasNoBoundaryCracks() {
        // smooth random blobby field: no open edges except through the grid border, which we pad with zeros
        val rng = Random(5)
        val n = 24
        val f = FloatArray(n * n * n)
        val c = Array(6) { floatArrayOf(4 + rng.nextFloat() * 16, 4 + rng.nextFloat() * 16, 4 + rng.nextFloat() * 16) }
        for (k in 1 until n - 1) for (j in 1 until n - 1) for (i in 1 until n - 1) {
            var s = 0f
            for (q in c) { val dx = i - q[0]; val dy = j - q[1]; val dz = k - q[2]; s += 1f / (1f + 0.15f * (dx * dx + dy * dy + dz * dz)) }
            f[i + n * (j + n * k)] = s
        }
        val mesh = MarchingCubes.extract(f, n, n, n, 0.5f, 1f)
        assertTrue(mesh.triangleCount > 100)
        assertEquals(0, mesh.inconsistentEdgeCount())
        assertEquals(0, mesh.badEdgeCount())
        assertEquals(256, McTables.triTable.size)
    }

    // ---------- mesh export ----------
    @Test fun objAndPlyCounts() {
        val mesh = sphereMesh(15, 0.1f)
        val obj = mesh.toObj()
        assertEquals(mesh.triangleCount, obj.lines().count { it.startsWith("f ") })
        assertEquals(mesh.vertexCount, obj.lines().count { it.startsWith("v ") })
        val ply = mesh.toBinaryPly()
        val header = String(ply, 0, 300, Charsets.US_ASCII).substringBefore("end_header\n") + "end_header\n"
        assertTrue(header.contains("element vertex ${mesh.vertexCount}"))
        assertTrue(header.contains("element face ${mesh.triangleCount}"))
        assertEquals(header.length + mesh.vertexCount * 24 + mesh.triangleCount * 13, ply.size)
    }

    // ---------- coverage dome ----------
    @Test fun domeOrbitCoversBandAndRisesMonotonically() {
        val box = ObjectBox(Fixtures.origin, 0f, 0.5f, 0.4f, 0.4f)     // largest side 0.5 -> window 0.3..2.0
        val dome = CoverageDome(box)
        assertTrue(dome.binCount in 80..160)
        val elev = 30f * PI.toFloat() / 180f
        val c = box.volumeCentre()
        var last = 0f
        for (rev in 0 until 4) for (deg in 0 until 360 step 2) {
            val a = deg * PI.toFloat() / 180f
            val cam = Vec3(c.x + 1.0f * cos(elev) * cos(a), c.y + 1.0f * sin(elev), c.z + 1.0f * cos(elev) * sin(a))
            dome.observe(cam)
            val cov = dome.coverage()
            assertTrue(cov >= last)
            last = cov
        }
        assertTrue("some coverage", last > 0.05f && last < 0.5f)
        val observed = dome.bins().filter { it.observed }
        assertTrue(observed.isNotEmpty())
        // observed bins sit in the 30 deg elevation band (bin spacing ~ 30 deg at 162 vertices)
        for (b in observed) {
            val e = Math.toDegrees(Math.asin(b.direction.y.toDouble()))
            assertTrue("elevation $e", e in 5.0..55.0)
        }
        // hysteresis: a single frame does not count
        val d2 = CoverageDome(box)
        d2.observe(Vec3(c.x, c.y + 1f, c.z + 0.01f))
        assertEquals(0f, d2.coverage(), 0f)
        // ignored distances
        assertEquals(-1, d2.observe(Vec3(c.x + 0.1f, c.y, c.z)))
        assertEquals(-1, d2.observe(Vec3(c.x + 2.5f, c.y, c.z)))
    }

    @Test fun domeWindowDependsOnBoxSize() {
        assertEquals(0.25f to 0.8f, CoverageDome.windowFor(0.2f))
        assertEquals(0.3f to 2.0f, CoverageDome.windowFor(0.5f))
        val small = CoverageDome(ObjectBox(Fixtures.origin, 0f, 0.2f, 0.2f, 0.2f))
        assertEquals(0.8f, small.maxDistance, 0f)
        assertEquals(-1, small.observe(small.centre + Vec3(0f, 1.0f, 0f)))
    }

    // ---------- object-local cloud ----------
    @Test fun objectCloudSurvivesFarFromOriginAtThreeMm() {
        val far = Vec3(40f, 1f, -35f)                        // plain VoxelCloud would reject this (range +-1.5 m at 3 mm)
        val plain = com.example.arruler.depth.VoxelCloud(0.003f)
        assertTrue(!plain.add(far.x, far.y, far.z))
        val c = ObjectVoxelCloud(far, 0.003f)
        assertTrue(c.add(far.x + 1.2f, far.y, far.z - 1.2f))
        assertTrue(!c.add(far.x + 1.7f, far.y, far.z))
        val p = c.points()
        assertEquals(far.x + 1.2f, p[0], 0.002f)
        assertEquals(1, c.hitsFor(p)[0])
    }

    // ---------- benchmark ----------
    @org.junit.Ignore("benchmark; run manually")
    @Test fun benchmark150k() {
        val q = ObjectQuality.FINE
        val s = Fixtures.boxScene(Random(77), 0.3f, 0.3f, 0.3f, 10f, 0.004f, q.voxelSize, perCell = 4f, floorHalf = 0.5f)
        val box = Fixtures.userBox(0.3f, 0.3f, 0.3f, 10f, 0.01f)
        fun ms(f: () -> Unit): Double { val t = System.nanoTime(); f(); return (System.nanoTime() - t) / 1e6 }
        val cloud = ObjectVoxelCloud(box, q.voxelSize, maxVoxels = 1_000_000)
        val frame = 5000   // points per depth frame after sub-sampling
        val frames = s.points.size / 3 / frame
        var perFrame = 0.0
        val tAll = ms {
            for (f in 0 until frames) perFrame += ms {
                for (i in f * frame until (f + 1) * frame) cloud.add(s.points[i * 3], s.points[i * 3 + 1], s.points[i * 3 + 2])
            }
        }
        val pts = cloud.points()
        println("BENCH raw=${s.points.size / 3} pts, $frames frames of $frame: cloud insert ${"%.2f".format(perFrame / frames)} ms/frame (total ${"%.0f".format(tAll)} ms); cloud voxels=${pts.size / 3}")
        repeat(3) { run ->
            var iso: IsolationResult? = null
            val t1 = ms { iso = q.isolation().isolate(pts, null, box, Fixtures.plane) }
            val t2 = ms { ObjectMeasures.measure(iso!!.points, box, Fixtures.plane, q.voxelSize) }
            var mesh: TriMesh? = null
            val t3 = ms { mesh = ObjectMeshBuilder.build(iso!!.points, box, Fixtures.plane, q.voxelSize) }
            println("BENCH run $run: ${pts.size / 3} voxels in -> isolation=${"%.0f".format(t1)} ms (kept ${iso!!.count}), measures=${"%.0f".format(t2)} ms, mesh=${"%.0f".format(t3)} ms (${mesh?.triangleCount} tris) total=${"%.0f".format(t1 + t2 + t3)} ms")
        }
    }
}
