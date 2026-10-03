package com.example.arruler.gpu

import com.example.arruler.objscan.PointGrid
import com.example.arruler.texture.Intrinsics
import com.example.arruler.texture.Keyframe
import com.example.arruler.texture.sampleBilinear
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/** JVM tests of every pure part of the gpu package (grid, texel map, decision logic, packing, RANSAC draw order). */
class GpuPureTest {
    private fun cloud(n: Int, seed: Long, extent: Float = 0.3f): FloatArray {
        val r = Random(seed)
        return FloatArray(n * 3) { if (it % 3 == 2) (r.nextGaussian() * 0.004).toFloat() else r.nextFloat() * extent }
    }

    // ---- grid ----

    @Test fun gridNeighboursMatchPointGridInOrder() {
        val n = 4000
        val pts = cloud(n, 1)
        val radius = 0.012f
        val mine = DenoiseGrid(pts, n, radius)
        val ref = PointGrid(pts, n, radius)
        val a = IntArray(4000); val b = IntArray(4000)
        for (i in 0 until n step 7) {
            val ma = mine.within(pts, i, radius, a)
            val mb = ref.within(i, radius, b)
            assertEquals("count of point $i", mb, ma)
            for (q in 0 until ma) assertEquals("neighbour $q of point $i", b[q], a[q])
        }
        assertEquals(n, mine.cellStart[mine.cells])
    }

    @Test fun gridHandlesLargeExtentAndSinglePoint() {
        val pts = floatArrayOf(0f, 0f, 0f, 500f, 500f, 500f, 500.001f, 500f, 500f)
        val g = DenoiseGrid(pts, 3, 0.012f)
        val out = IntArray(10)
        assertEquals(1, g.within(pts, 0, 0.012f, out))
        assertEquals(2, g.within(pts, 1, 0.012f, out))
        val one = DenoiseGrid(floatArrayOf(1f, 2f, 3f), 1, 0.01f)
        assertEquals(1, one.within(floatArrayOf(1f, 2f, 3f), 0, 0.01f, out))
    }

    @Test fun gridCapMatchesCpuBufferCap() {
        val n = 300
        val pts = FloatArray(n * 3) // all points at the origin: every ball holds all 300
        val g = DenoiseGrid(pts, n, 0.01f)
        val out = IntArray(4000)
        assertEquals(300, g.within(pts, 0, 0.01f, out))
        assertEquals(10, g.within(pts, 0, 0.01f, out, 10))
    }

    // ---- texel map ----

    private fun twoTrianglePlan(): TexelPlan {
        // one chart, two triangles forming a 20 x 20 px square in keyframe space starting at (10, 10); scale 1; atlas 64; chart at (4, 4)
        val triUV = floatArrayOf(10f, 10f, 30f, 10f, 30f, 30f, 10f, 10f, 30f, 30f, 10f, 30f)
        return TexelPlan(64, 2, 1f, triUV, intArrayOf(0, 0), intArrayOf(0, 0), floatArrayOf(10f), floatArrayOf(10f), intArrayOf(4), intArrayOf(4))
    }

    @Test fun texelMapCoversTheChartAndNothingElse() {
        val plan = twoTrianglePlan()
        val m = TexelMapBuilder.build(plan)
        // chart texel origin = (6, 6); square spans texels 6..25
        assertTrue(m[16 * 64 + 16] >= 0)
        assertEquals(-1, m[0])
        assertEquals(-1, m[40 * 64 + 40])
        assertEquals(-1, m[5 * 64 + 5])
        var covered = 0
        for (v in m) if (v >= 0) covered++
        assertTrue("covered=$covered", covered in 400..470)
    }

    @Test fun texelMapLastTriangleWinsOnTheSharedEdge() {
        val plan = twoTrianglePlan()
        val m = TexelMapBuilder.build(plan)
        // a texel strictly inside the lower-left triangle (t=1) and one inside the upper-right (t=0)
        assertEquals(1, m[22 * 64 + 8])   // x=8 (small), y=22 (large): below the diagonal in image space -> triangle 1
        assertEquals(0, m[8 * 64 + 22])
    }

    @Test fun texelParamsAndKeyframePacking() {
        val plan = twoTrianglePlan()
        val (p, v) = TexelMapBuilder.triangleParams(plan)
        assertArrayEquals(floatArrayOf(10f, 10f, 6f, 6f, 10f, 10f, 6f, 6f), p, 0f)
        assertArrayEquals(intArrayOf(0, 0), v)
        val k1 = Keyframe(FloatArray(16), Intrinsics(1f, 1f, 1f, 1f, 2, 2), IntArray(4) { it })
        val k2 = Keyframe(FloatArray(16), Intrinsics(1f, 1f, 1f, 1f, 3, 1), IntArray(3) { 100 + it })
        val packed = TexelMapBuilder.packKeyframes(listOf(k1, k2))
        assertArrayEquals(intArrayOf(0, 2, 2, 4, 3, 1), packed.meta)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 100, 101, 102), packed.pixels)
    }

    @Test fun cpuSampleReproducesTheKeyframeOnAnIdentityChart() {
        val w = 40; val h = 40
        val argb = IntArray(w * h) { (0xFF shl 24) or ((it % w * 6) shl 16) or ((it / w * 6) shl 8) or 77 }
        val kf = Keyframe(FloatArray(16), Intrinsics(1f, 1f, 1f, 1f, w, h), argb)
        val plan = twoTrianglePlan()
        val atlas = GpuTextureBake.cpuSample(plan, listOf(kf))
        // texel (16,16): keyframe coordinate = 10 + (16.5 - 6) = 20.5
        assertEquals(sampleBilinear(kf, 20.5f, 20.5f), atlas[16 * 64 + 16])
        assertEquals(0, atlas[0])
    }

    // ---- decision logic ----

    private fun profile() = GpuProfile(true, 5.0, mapOf(GpuKernel.DENOISE to KernelCost(cpuNsPerUnit = 10_000.0, gpuNsPerUnit = 1_000.0, gpuFixedMs = 4.0)))

    @Test fun profileUsesGpuOnlyAboveTheBreakEven() {
        val p = profile()
        val be = p.breakEvenUnits(GpuKernel.DENOISE)!!
        // 4 ms = n x (0.8 x 10000 - 1000) ns -> n = 571.4
        assertEquals(572L, be)
        assertFalse(p.useGpu(GpuKernel.DENOISE, 100))
        assertFalse(p.useGpu(GpuKernel.DENOISE, 571))
        assertTrue(p.useGpu(GpuKernel.DENOISE, 573))
        assertTrue(p.useGpu(GpuKernel.DENOISE, 60_000))
    }

    @Test fun profileNeverUsesGpuWhenItIsNotFasterPerUnit() {
        val slow = GpuProfile(true, 1.0, mapOf(GpuKernel.DENOISE to KernelCost(1_000.0, 900.0, 1.0)))
        assertNull(slow.breakEvenUnits(GpuKernel.DENOISE))
        assertFalse(slow.useGpu(GpuKernel.DENOISE, 10_000_000))
        assertFalse(GpuProfile.NONE.useGpu(GpuKernel.DENOISE, 10_000_000))
        assertFalse(profile().useGpu(GpuKernel.RANSAC, 10_000_000)) // kernel not in the table
    }

    @Test fun profileFromBenchScalesKernelsAndRejectsFailedBench() {
        val ok = GpuBenchResult(true, null, 60_000, gpuMs = 30.0, cpuMsEstimated = 600.0, fixedMs = 3.0, compileMs = 50.0, totalMs = 900.0)
        assertEquals(2.0, ok.gpuScore, 1e-9)
        assertEquals(20.0, ok.speedup, 1e-9)
        assertEquals(10_000.0, ok.cpuNsPerPoint, 1e-6)
        assertEquals(450.0, ok.gpuNsPerPoint, 1e-6)
        val p = GpuProfile.fromBench(ok)
        assertTrue(p.available)
        assertNotNull(p.breakEvenUnits(GpuKernel.TEXTURE_BAKE))
        assertTrue(p.breakEvenUnits(GpuKernel.TEXTURE_BAKE)!! > p.breakEvenUnits(GpuKernel.DENOISE)!!) // cheaper units need more of them
        assertFalse(GpuProfile.fromBench(GpuBenchResult(false, "x", 60_000, 0.0, 0.0, 0.0, 0.0, 0.0)).available)
        val tuned = p.withMeasured(GpuKernel.RANSAC, KernelCost(100.0, 1.0, 1.0))
        assertEquals(tuned.costs[GpuKernel.RANSAC]!!.cpuNsPerUnit, 100.0, 0.0)
    }

    // ---- packing / info ----

    @Test fun bufferPackingRoundTrips() {
        val f = floatArrayOf(1.5f, -2f, 3.25f)
        assertArrayEquals(f, GpuBuffers.toFloats(GpuBuffers.floats(f), 3), 0f)
        val i = intArrayOf(7, -8, 9)
        assertArrayEquals(i, GpuBuffers.toInts(GpuBuffers.ints(i), 3))
        val b = GpuBuffers.bytesPadded(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(8, b.capacity())
        assertEquals(5, b.get(4).toInt())
        assertEquals(0, b.get(7).toInt())
        assertEquals(12, GpuBuffers.floats(FloatArray(10), 3).capacity())
    }

    @Test fun esVersionAndVulkanTextParse() {
        assertEquals(3 to 1, GpuInfo.parseEsVersion("OpenGL ES 3.1 (ANGLE 2.1.0)"))
        assertEquals(3 to 2, GpuInfo.parseEsVersion("OpenGL ES 3.2 v1.r44p0"))
        assertEquals(0 to 0, GpuInfo.parseEsVersion("garbage"))
        assertEquals("none", GpuInfo.vulkanVersionText(-1, 0))
        assertEquals("level 1, 1.3.0", GpuInfo.vulkanVersionText(1, (1 shl 22) or (3 shl 12)))
    }

    // ---- RANSAC draw order ----

    /** Verbatim copy of PlaneExtractor.ransac's loop, the thing the GPU path must reproduce. */
    private fun originalRansac(p: FloatArray, idx: IntArray, n: Int, iterations: Int, ransacSample: Int, thr: Float, minInliers: Int, random: kotlin.random.Random): DoubleArray? {
        val sub: IntArray = if (n <= ransacSample) IntArray(n) { idx[it] } else IntArray(ransacSample) { idx[random.nextInt(n)] }
        var bestCount = 0
        var best: DoubleArray? = null
        repeat(iterations) {
            val a = sub[random.nextInt(sub.size)] * 3
            val b = sub[random.nextInt(sub.size)] * 3
            val c = sub[random.nextInt(sub.size)] * 3
            val ux = (p[b] - p[a]).toDouble(); val uy = (p[b + 1] - p[a + 1]).toDouble(); val uz = (p[b + 2] - p[a + 2]).toDouble()
            val vx = (p[c] - p[a]).toDouble(); val vy = (p[c + 1] - p[a + 1]).toDouble(); val vz = (p[c + 2] - p[a + 2]).toDouble()
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-9) return@repeat
            nx /= len; ny /= len; nz /= len
            val dd = nx * p[a] + ny * p[a + 1] + nz * p[a + 2]
            var cnt = 0
            for (i in sub) {
                val o = i * 3
                if (abs(nx * p[o] + ny * p[o + 1] + nz * p[o + 2] - dd) <= thr) cnt++
            }
            if (cnt > bestCount) { bestCount = cnt; best = doubleArrayOf(nx, ny, nz, dd) }
        }
        val scaled = bestCount.toLong() * n / sub.size
        return if (scaled < minInliers) null else best
    }

    private fun roomLike(seed: Long): FloatArray {
        val r = Random(seed)
        val n = 9000
        val p = FloatArray(n * 3)
        for (i in 0 until n) {
            if (i < 5000) { p[i * 3] = r.nextFloat() * 4f; p[i * 3 + 1] = (r.nextGaussian() * 0.004).toFloat(); p[i * 3 + 2] = r.nextFloat() * 4f } // floor
            else { p[i * 3] = r.nextFloat() * 4f; p[i * 3 + 1] = r.nextFloat() * 2.5f; p[i * 3 + 2] = (r.nextGaussian() * 0.004).toFloat() } // wall
        }
        return p
    }

    @Test fun cpuBestPlaneEqualsTheOriginalLoopForSeveralSeeds() {
        val p = roomLike(5)
        val idx = IntArray(p.size / 3) { it }
        for (seed in 1..6) {
            for (sample in intArrayOf(4000, 20000)) {
                val a = originalRansac(p, idx, idx.size, 300, sample, 0.02f, 300, kotlin.random.Random(seed))
                val b = GpuRansac.cpuBestPlane(p, idx, idx.size, 300, sample, 0.02f, 300, kotlin.random.Random(seed))
                assertNotNull(a); assertNotNull(b)
                assertArrayEquals("seed $seed sample $sample", a!!, b!!, 0.0)
            }
        }
    }

    @Test fun cpuBestPlaneRejectsWhenTooFewInliers() {
        val p = cloud(2000, 3, 5f).also { for (i in 0 until 2000) p3(it, i) }
        val idx = IntArray(2000) { it }
        assertNull(GpuRansac.cpuBestPlane(p, idx, 2000, 100, 4000, 0.001f, 1900, kotlin.random.Random(1)))
    }

    private fun p3(a: FloatArray, i: Int) { a[i * 3 + 2] = (i * 0.37f) % 5f } // scatter z so no plane dominates

    // ---- image ops (CPU twins) ----

    @Test fun nv21GreyAndPrimaries() {
        val w = 2; val h = 2
        val grey = ByteArray(6) { 128.toByte() }
        for (px in GpuImageOps.nv21ToArgbCpu(grey, w, h)) assertEquals(0xFF808080.toInt(), px)
        val red = ByteArray(6).also { for (i in 0 until 4) it[i] = 81; it[4] = 240.toByte(); it[5] = 90 } // Y=81 V=240 U=90 -> red
        val px = GpuImageOps.nv21ToArgbCpu(red, w, h)[0]
        assertTrue("R ${(px shr 16) and 0xFF}", ((px shr 16) and 0xFF) > 230 && ((px shr 8) and 0xFF) < 20 && (px and 0xFF) < 20)
    }
}
