package com.example.arruler.texture

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.TriMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TextureBakerTest {
    private val k = Intrinsics(700f, 700f, 320f, 240f, 640, 480)

    private fun keyframes(cube: SyntheticCube, poses: List<CameraPose>) = poses.map { Keyframe(it.m, k, cube.render(it, k)) }

    private fun maxChannelDiff(a: Int, b: Int): Int =
        maxOf(abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)), abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF)))

    /** Atlas colour at the world point [p] on triangle [t] of the baked mesh (barycentric uv interpolation, nearest texel). */
    private fun sampleAtlas(baked: BakedMesh, t: Int, p: Vec3): Int? {
        val m = baked.mesh
        val ia = m.indices[t * 3]; val ib = m.indices[t * 3 + 1]; val ic = m.indices[t * 3 + 2]
        fun v(i: Int) = Vec3(m.vertices[i * 3], m.vertices[i * 3 + 1], m.vertices[i * 3 + 2])
        val a = v(ia); val b = v(ib); val c = v(ic)
        val v0 = b - a; val v1 = c - a; val v2 = p - a
        val d00 = v0.dot(v0); val d01 = v0.dot(v1); val d11 = v1.dot(v1); val d20 = v2.dot(v0); val d21 = v2.dot(v1)
        val den = d00 * d11 - d01 * d01
        val wb = (d11 * d20 - d01 * d21) / den; val wc = (d00 * d21 - d01 * d20) / den; val wa = 1f - wb - wc
        if (wa < -1e-3f || wb < -1e-3f || wc < -1e-3f) return null
        val u = wa * baked.uvs[ia * 2] + wb * baked.uvs[ib * 2] + wc * baked.uvs[ic * 2]
        val vv = wa * baked.uvs[ia * 2 + 1] + wb * baked.uvs[ib * 2 + 1] + wc * baked.uvs[ic * 2 + 1]
        val x = (u * baked.atlasSize).toInt().coerceIn(0, baked.atlasSize - 1)
        val y = (vv * baked.atlasSize).toInt().coerceIn(0, baked.atlasSize - 1)
        return baked.atlas[y * baked.atlasSize + x]
    }

    @Test fun cubeFromTwentyFourViewsReproducesEveryFaceCentre() {
        val cube = SyntheticCube(cells = 3)
        val poses = SyntheticCube.orbit(Vec3(0f, 0.1f, 0f), 0.7f, listOf(-40.0, 10.0, 40.0, 70.0), 6)
        assertEquals(24, poses.size)
        val baked = TextureBaker.bake(cube.mesh(), keyframes(cube, poses))
        assertEquals(12, baked.stats.seenTriangles)
        assertEquals(0, baked.stats.unseenTriangles)
        assertTrue(baked.atlasSize <= 2048)
        assertEquals(12, baked.mesh.triangleCount)
        assertTrue("seam vertices must be duplicated", baked.mesh.vertexCount > 8)
        for (u in baked.uvs) assertTrue("uv $u outside [0,1]", u in 0f..1f)
        for (f in 0 until 6) {
            val fc = cube.faceCentre(f)
            val col = (0 until 2).firstNotNullOfOrNull { sampleAtlas(baked, 2 * f + it, fc) } ?: error("face $f centre not on its triangles")
            val want = (0xFF shl 24) or cube.faceColours[f]
            assertTrue("face $f: got ${Integer.toHexString(col)} want ${Integer.toHexString(want)}", maxChannelDiff(col, want) <= 30)
        }
    }

    @Test fun vertexColoursOfASolidCubeAreOneOfTheAdjacentFaceColours() {
        val cube = SyntheticCube(cells = 1)
        val poses = SyntheticCube.orbit(Vec3(0f, 0.1f, 0f), 0.7f, listOf(-40.0, 10.0, 40.0, 70.0), 6)
        val mesh = cube.mesh()
        val rgb = TextureBaker.bakeVertexColors(mesh, keyframes(cube, poses))
        assertEquals(8, rgb.size)
        for (i in 0 until 8) {
            val adjacent = (0 until 6).filter { f ->
                val fc = cube.faceCentre(f)
                val axis = f / 2
                val vc = floatArrayOf(mesh.vertices[i * 3], mesh.vertices[i * 3 + 1], mesh.vertices[i * 3 + 2])
                val fv = floatArrayOf(fc.x, fc.y, fc.z)
                abs(vc[axis] - fv[axis]) < 1e-5f
            }
            assertEquals(3, adjacent.size)
            // a corner vertex sits on the silhouette, so its bilinear sample blends the face with the grey background
            val ok = adjacent.any { maxChannelDiff(rgb[i], (0xFF shl 24) or cube.faceColours[it]) <= 60 }
            assertTrue("vertex $i colour ${Integer.toHexString(rgb[i])}", ok)
        }
    }

    @Test fun unseenFacesTakeTheNeighbourColour() {
        val cube = SyntheticCube(cells = 1)
        val pose = CameraPose.lookAt(Vec3(0.8f, 0.1f, 0f), Vec3(0f, 0.1f, 0f)) // straight at the +x face
        val baked = TextureBaker.bake(cube.mesh(), keyframes(cube, listOf(pose)))
        assertEquals(2, baked.stats.seenTriangles)
        assertEquals(10, baked.stats.unseenTriangles)
        val want = (0xFF shl 24) or cube.faceColours[0]
        for (f in 1 until 6) {
            val col = (0 until 2).firstNotNullOfOrNull { sampleAtlas(baked, 2 * f + it, cube.faceCentre(f)) } ?: error("no tri")
            assertTrue("face $f ${Integer.toHexString(col)}", maxChannelDiff(col, want) <= 20)
        }
    }

    @Test fun occludedTrianglesAreNotTexturedFromTheOccludedView() {
        // 10 x 10 back wall at z = 0 (+z normal) and a small front plate at z = 0.2; one camera in front.
        val n = 10
        val v = ArrayList<Float>(); val idx = ArrayList<Int>()
        for (j in 0..n) for (i in 0..n) { v.add(-0.5f + i / n.toFloat()); v.add(-0.5f + j / n.toFloat()); v.add(0f) }
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * (n + 1) + i; val b = a + 1; val c = a + n + 1; val d = c + 1
            idx.addAll(listOf(a, b, d, a, d, c))
        }
        val base = v.size / 3
        for ((x, y) in listOf(-0.15f to -0.15f, 0.15f to -0.15f, 0.15f to 0.15f, -0.15f to 0.15f)) { v.add(x); v.add(y); v.add(0.2f) }
        idx.addAll(listOf(base, base + 1, base + 2, base, base + 2, base + 3))
        val nrm = FloatArray(v.size) { if (it % 3 == 2) 1f else 0f }
        val mesh = TriMesh(v.toFloatArray(), nrm, idx.toIntArray())
        val eye = Vec3(0f, 0f, 1.5f)
        val pose = CameraPose.lookAt(eye, Vec3(0f, 0f, 0f))
        val img = IntArray(640 * 480) { 0xFF808080.toInt() }
        val baked = TextureBaker.bake(mesh, listOf(Keyframe(pose.m, k, img)))
        // centre cell of the wall (cell 5,5 -> triangles 2*(5*10+5) and +1) lies behind the plate; corner cell (0,0) is clear
        val centre = 2 * (5 * n + 5)
        assertEquals(-1, baked.triangleView[centre]); assertEquals(-1, baked.triangleView[centre + 1])
        assertEquals(0, baked.triangleView[0]); assertEquals(0, baked.triangleView[1])
        assertEquals(0, baked.triangleView[mesh.triangleCount - 1]) // the plate itself
    }

    @Test fun bakeTimeForLargeMeshIsReported() {
        // UV sphere with 125,000 triangles, 60 keyframes of 640 x 480 (procedural texture), cameras on rings
        val seg = 250; val rings = 250
        val r = 0.15f
        val verts = FloatArray((rings + 1) * (seg + 1) * 3)
        var p = 0
        for (i in 0..rings) {
            val th = Math.PI * i / rings
            for (j in 0..seg) {
                val ph = 2 * Math.PI * j / seg
                verts[p++] = (r * sin(th) * cos(ph)).toFloat(); verts[p++] = (r * cos(th)).toFloat() + 0.15f; verts[p++] = (r * sin(th) * sin(ph)).toFloat()
            }
        }
        val nrm = FloatArray(verts.size)
        for (i in 0 until verts.size / 3) { val x = verts[i * 3]; val y = verts[i * 3 + 1] - 0.15f; val z = verts[i * 3 + 2]; nrm[i * 3] = x / r; nrm[i * 3 + 1] = y / r; nrm[i * 3 + 2] = z / r }
        val idx = IntArray(rings * seg * 6)
        var q = 0
        for (i in 0 until rings) for (j in 0 until seg) {
            val a = i * (seg + 1) + j; val b = a + 1; val c = a + seg + 1; val d = c + 1
            // outward counter-clockwise (theta grows downward, phi counter-clockwise seen from +y)
            idx[q++] = a; idx[q++] = b; idx[q++] = c
            idx[q++] = b; idx[q++] = d; idx[q++] = c
        }
        val mesh = TriMesh(verts, nrm, idx)
        assertEquals(125_000, mesh.triangleCount)
        val img = IntArray(640 * 480) { val x = it % 640; val y = it / 640; (0xFF shl 24) or (((x * 255 / 640) and 0xFF) shl 16) or (((y * 255 / 480) and 0xFF) shl 8) or (((x / 8 + y / 8) % 2) * 200) }
        val poses = SyntheticCube.orbit(Vec3(0f, 0.15f, 0f), 0.6f, listOf(-50.0, -20.0, 10.0, 30.0, 50.0, 70.0), 10)
        assertEquals(60, poses.size)
        val kfs = poses.map { Keyframe(it.m, k, img) }
        val t0 = System.nanoTime()
        val baked = TextureBaker.bake(mesh, kfs)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("BAKE_TIME_125k_60kf_ms=$ms atlas=${baked.atlasSize} seen=${baked.stats.seenTriangles} unseen=${baked.stats.unseenTriangles} charts=${baked.stats.charts} scale=${baked.stats.scale} verts=${baked.mesh.vertexCount}")
        val t1 = System.nanoTime()
        TextureBaker.bakeVertexColors(mesh, kfs)
        println("VERTEXCOLOUR_TIME_125k_60kf_ms=${(System.nanoTime() - t1) / 1_000_000}")
        assertTrue(baked.stats.seenTriangles > mesh.triangleCount / 3)
        assertTrue("took ${ms} ms", ms < 120_000)
    }
}
