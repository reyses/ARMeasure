package com.example.arruler.gpu

import com.example.arruler.objscan.ObjectDenoise
import kotlin.math.floor

/**
 * Uniform grid for the GPU neighbour search, built on the CPU exactly like objscan's PointGrid (same origin, same cell
 * index, same item order inside a cell = ascending point index). [table] is an open-addressing hash of cells, 4 ints per
 * slot (ix, iy, iz, cell id; id -1 = empty slot), [cellStart]/[items] the counting-sorted point lists. Pure.
 */
internal class DenoiseGrid(points: FloatArray, val n: Int, val cell: Float) {
    val ci = IntArray(n * 3)
    val cellStart: IntArray
    val items: IntArray
    val table: IntArray
    val mask: Int
    val cells: Int

    init {
        var x0 = Float.MAX_VALUE; var y0 = x0; var z0 = x0
        for (i in 0 until n) {
            x0 = minOf(x0, points[i * 3]); y0 = minOf(y0, points[i * 3 + 1]); z0 = minOf(z0, points[i * 3 + 2])
        }
        val minX = x0 - 2 * cell; val minY = y0 - 2 * cell; val minZ = z0 - 2 * cell
        var cap = 16
        while (cap < n * 2) cap = cap shl 1
        mask = cap - 1
        table = IntArray(cap * 4).also { for (s in 0 until cap) it[s * 4 + 3] = -1 }
        val cellOf = IntArray(n)
        val counts = IntArray(n + 1)
        var nc = 0
        val inv = 1f / cell
        for (i in 0 until n) {
            val ix = floor((points[i * 3] - minX) * inv).toInt()
            val iy = floor((points[i * 3 + 1] - minY) * inv).toInt()
            val iz = floor((points[i * 3 + 2] - minZ) * inv).toInt()
            ci[i * 3] = ix; ci[i * 3 + 1] = iy; ci[i * 3 + 2] = iz
            var s = hash(ix, iy, iz) and mask
            var id: Int
            while (true) {
                id = table[s * 4 + 3]
                if (id < 0) { id = nc++; table[s * 4] = ix; table[s * 4 + 1] = iy; table[s * 4 + 2] = iz; table[s * 4 + 3] = id; break }
                if (table[s * 4] == ix && table[s * 4 + 1] == iy && table[s * 4 + 2] == iz) break
                s = (s + 1) and mask
            }
            counts[id]++
            cellOf[i] = id
        }
        cells = nc
        cellStart = IntArray(nc + 1)
        for (c in 0 until nc) cellStart[c + 1] = cellStart[c] + counts[c]
        val fill = IntArray(nc)
        items = IntArray(n)
        for (i in 0 until n) { val c = cellOf[i]; items[cellStart[c] + fill[c]++] = i }
    }

    /** Cell id at (ix, iy, iz) or -1. */
    fun cellId(ix: Int, iy: Int, iz: Int): Int {
        var s = hash(ix, iy, iz) and mask
        while (true) {
            val id = table[s * 4 + 3]
            if (id < 0) return -1
            if (table[s * 4] == ix && table[s * 4 + 1] == iy && table[s * 4 + 2] == iz) return id
            s = (s + 1) and mask
        }
    }

    /** Neighbours of point [i] within [radius] in the SAME order the CPU and GPU walk them (cell dx,dy,dz then ascending index), capped at [cap]. */
    fun within(points: FloatArray, i: Int, radius: Float, out: IntArray, cap: Int = out.size): Int {
        val px = points[i * 3]; val py = points[i * 3 + 1]; val pz = points[i * 3 + 2]
        val r2 = radius * radius
        var c = 0
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val id = cellId(ci[i * 3] + dx, ci[i * 3 + 1] + dy, ci[i * 3 + 2] + dz)
            if (id < 0) continue
            for (s in cellStart[id] until cellStart[id + 1]) {
                val j = items[s]
                val ex = points[j * 3] - px; val ey = points[j * 3 + 1] - py; val ez = points[j * 3 + 2] - pz
                if (ex * ex + ey * ey + ez * ez <= r2 && c < cap) out[c++] = j
            }
        }
        return c
    }

    companion object {
        /** Same bit pattern as the shader's uint arithmetic. */
        fun hash(ix: Int, iy: Int, iz: Int): Int = (ix * 73856093) xor (iy * 19349663) xor (iz * 83492791)
    }
}

/**
 * GPU version of [ObjectDenoise.smooth]: same radius, iterations, neighbour cap and stride rule; the grid is rebuilt on the
 * CPU each iteration (as the CPU path does), the per-point plane fit runs one invocation per point. Equivalence target:
 * max per-point difference < 0.1 mm on the fixtures (the GPU is float32, the CPU Jacobi is double). Falls back to
 * [ObjectDenoise.smooth] on any GL error or when compute is unavailable.
 */
object GpuDenoise {
    const val GROUP = 64
    private const val CAP = 4000 // PointGrid neighbour buffer of the CPU path

    internal val SHADER = """#version 310 es
layout(local_size_x = $GROUP) in;
precision highp float;
precision highp int;
layout(std430, binding = 0) readonly buffer Pts { vec4 pts[]; };
layout(std430, binding = 1) writeonly buffer Outp { vec4 outp[]; };
layout(std430, binding = 2) readonly buffer Cells { ivec4 ci[]; };
layout(std430, binding = 3) readonly buffer Table { ivec4 table[]; };
layout(std430, binding = 4) readonly buffer Start { int cellStart[]; };
layout(std430, binding = 5) readonly buffer Items { int items[]; };
uniform int uN;
uniform int uBase;
uniform int uMask;
uniform float uRadius;
uniform int uMinN;
uniform int uMaxN;

float A[9];
float V[9];

int cellId(int ix, int iy, int iz) {
    uint h = (uint(ix) * 73856093u) ^ (uint(iy) * 19349663u) ^ (uint(iz) * 83492791u);
    int s = int(h & uint(uMask));
    for (int k = 0; k <= uMask; k++) {
        ivec4 e = table[s];
        if (e.w < 0) return -1;
        if (e.x == ix && e.y == iy && e.z == iz) return e.w;
        s = (s + 1) & uMask;
    }
    return -1;
}

void rot(int p, int q) {
    float apq = A[p * 3 + q];
    if (abs(apq) < 1e-18) return;
    float theta = (A[q * 3 + q] - A[p * 3 + p]) / (2.0 * apq);
    float t = (theta >= 0.0 ? 1.0 : -1.0) / (abs(theta) + sqrt(theta * theta + 1.0));
    float c = 1.0 / sqrt(t * t + 1.0);
    float s = t * c;
    for (int k = 0; k < 3; k++) {
        float akp = A[k * 3 + p]; float akq = A[k * 3 + q];
        A[k * 3 + p] = c * akp - s * akq; A[k * 3 + q] = s * akp + c * akq;
    }
    for (int k = 0; k < 3; k++) {
        float apk = A[p * 3 + k]; float aqk = A[q * 3 + k];
        A[p * 3 + k] = c * apk - s * aqk; A[q * 3 + k] = s * apk + c * aqk;
    }
    for (int k = 0; k < 3; k++) {
        float vkp = V[k * 3 + p]; float vkq = V[k * 3 + q];
        V[k * 3 + p] = c * vkp - s * vkq; V[k * 3 + q] = s * vkp + c * vkq;
    }
}

void main() {
    int i = int(gl_WorkGroupID.x) + uBase;
    i = i * $GROUP + int(gl_LocalInvocationID.x);
    if (i >= uN) return;
    vec3 p = pts[i].xyz;
    outp[i] = vec4(p, 0.0);
    ivec3 c0 = ci[i].xyz;
    float r2 = uRadius * uRadius;

    int m = 0;
    for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
        int id = cellId(c0.x + dx, c0.y + dy, c0.z + dz);
        if (id < 0) continue;
        for (int s = cellStart[id]; s < cellStart[id + 1]; s++) {
            vec3 e = pts[items[s]].xyz - p;
            if (e.x * e.x + e.y * e.y + e.z * e.z <= r2 && m < $CAP) m++;
        }
    }
    if (m < uMinN) return;
    int stride = m > uMaxN ? m / uMaxN : 1;
    int cnt = (m + stride - 1) / stride;

    int q = 0;
    vec3 s1 = vec3(0.0);
    float xx = 0.0, xy = 0.0, xz = 0.0, yy = 0.0, yz = 0.0, zz = 0.0;
    for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
        int id = cellId(c0.x + dx, c0.y + dy, c0.z + dz);
        if (id < 0) continue;
        for (int s = cellStart[id]; s < cellStart[id + 1]; s++) {
            vec3 e = pts[items[s]].xyz - p;
            if (e.x * e.x + e.y * e.y + e.z * e.z <= r2 && q < m) {
                if (q % stride == 0) {
                    s1 += e;
                    xx += e.x * e.x; xy += e.x * e.y; xz += e.x * e.z; yy += e.y * e.y; yz += e.y * e.z; zz += e.z * e.z;
                }
                q++;
            }
        }
    }
    float fc = float(cnt);
    vec3 mean = s1 / fc;
    A[0] = xx - fc * mean.x * mean.x; A[1] = xy - fc * mean.x * mean.y; A[2] = xz - fc * mean.x * mean.z;
    A[4] = yy - fc * mean.y * mean.y; A[5] = yz - fc * mean.y * mean.z; A[8] = zz - fc * mean.z * mean.z;
    A[3] = A[1]; A[6] = A[2]; A[7] = A[5];
    V[0] = 1.0; V[1] = 0.0; V[2] = 0.0; V[3] = 0.0; V[4] = 1.0; V[5] = 0.0; V[6] = 0.0; V[7] = 0.0; V[8] = 1.0;
    for (int sweep = 0; sweep < 12; sweep++) { rot(0, 1); rot(0, 2); rot(1, 2); }
    int best = 0;
    if (A[4] < A[best * 3 + best]) best = 1;
    if (A[8] < A[best * 3 + best]) best = 2;
    vec3 ev = vec3(V[best], V[3 + best], V[6 + best]);
    float dist = dot(-mean, ev);
    outp[i] = vec4(p - dist * ev, 0.0);
}
"""

    /** [ctx] null or compute-less -> CPU. [policy] (optional) can veto the GPU for small inputs. */
    fun smooth(
        ctx: GpuContext?, points: FloatArray, radius: Float, iterations: Int = 2, minNeighbours: Int = 8, maxNeighbours: Int = 48,
        policy: GpuProfile? = null,
    ): GpuRun<FloatArray> {
        val t0 = System.nanoTime()
        val n = points.size / 3
        fun cpu(reason: String): GpuRun<FloatArray> {
            val r = ObjectDenoise.smooth(points, radius, iterations, minNeighbours, maxNeighbours)
            return GpuRun(r, false, (System.nanoTime() - t0) / 1e6, reason)
        }
        if (n == 0 || radius <= 0f) return GpuRun(points, false, 0.0, "nothing to do")
        if (ctx == null || !ctx.computeSupported) return cpu("no compute")
        if (policy != null && !policy.useGpu(GpuKernel.DENOISE, n)) return cpu("below GPU threshold ($n points)")
        return try {
            var cur = points
            repeat(iterations) { cur = oneIteration(ctx, cur, n, radius, minNeighbours, maxNeighbours) }
            GpuRun(cur, true, (System.nanoTime() - t0) / 1e6, null)
        } catch (e: GpuException) {
            cpu("GPU error: ${e.message}")
        }
    }

    private fun oneIteration(ctx: GpuContext, cur: FloatArray, n: Int, radius: Float, minN: Int, maxN: Int): FloatArray {
        val grid = DenoiseGrid(cur, n, radius)
        val v4 = FloatArray(n * 4)
        for (i in 0 until n) { v4[i * 4] = cur[i * 3]; v4[i * 4 + 1] = cur[i * 3 + 1]; v4[i * 4 + 2] = cur[i * 3 + 2] }
        val civ = IntArray(n * 4)
        for (i in 0 until n) { civ[i * 4] = grid.ci[i * 3]; civ[i * 4 + 1] = grid.ci[i * 3 + 1]; civ[i * 4 + 2] = grid.ci[i * 3 + 2] }
        return ctx.call {
            val prog = ctx.program("denoise", SHADER)
            val bufs = IntArray(6)
            try {
                bufs[0] = ctx.ssbo(n * 16, GpuBuffers.floats(v4))
                bufs[1] = ctx.ssbo(n * 16)
                bufs[2] = ctx.ssbo(n * 16, GpuBuffers.ints(civ))
                bufs[3] = ctx.ssbo(grid.table.size * 4, GpuBuffers.ints(grid.table))
                bufs[4] = ctx.ssbo(grid.cellStart.size * 4, GpuBuffers.ints(grid.cellStart))
                bufs[5] = ctx.ssbo(n * 4, GpuBuffers.ints(grid.items))
                for (b in 0..5) ctx.bind(b, bufs[b])
                ctx.uniform1i(prog, "uN", n); ctx.uniform1i(prog, "uMask", grid.mask)
                ctx.uniform1f(prog, "uRadius", radius); ctx.uniform1i(prog, "uMinN", minN); ctx.uniform1i(prog, "uMaxN", maxN)
                ctx.dispatchChunked(prog, (n + GROUP - 1) / GROUP)
                val back = GpuBuffers.toFloats(ctx.download(bufs[1], n * 16), n * 4)
                val out = FloatArray(n * 3)
                for (i in 0 until n) { out[i * 3] = back[i * 4]; out[i * 3 + 1] = back[i * 4 + 1]; out[i * 3 + 2] = back[i * 4 + 2] }
                out
            } finally {
                ctx.deleteBuffers(*bufs.filter { it != 0 }.toIntArray())
            }
        }
    }
}
