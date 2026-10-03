package com.example.arruler.objscan

import kotlin.math.atan2

/**
 * Marching cubes case tables: for each of the 256 corner sign patterns, the triangles (as triples of cube-edge
 * indices 0..11) of the isosurface inside the cube.
 *
 * Corner i has offset (CX[i], CY[i], CZ[i]); corner bit i set = value above the iso level (inside). Edge e joins
 * [EDGE_A][e] and [EDGE_B][e].
 *
 * The 256 x (up to 5) triangle table is GENERATED once at class load instead of typed in, from a rule that makes the
 * result crack free by construction: each cube face contributes iso segments that depend only on that face's four
 * corner values (the ambiguous diagonal face case always separates the inside corners), so two cubes sharing a face always
 * agree on it; the segments of a cube form closed loops, and every loop is fan-triangulated and oriented so that
 * the normal points towards the outside (lower values). This avoids the known holes of the original Lorensen/Bourke
 * tables. The unit tests check closure and orientation on a sphere and on random fields.
 */
object McTables {
    val CX = intArrayOf(0, 1, 1, 0, 0, 1, 1, 0)
    val CY = intArrayOf(0, 0, 1, 1, 0, 0, 1, 1)
    val CZ = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1)
    val EDGE_A = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3)
    val EDGE_B = intArrayOf(1, 2, 3, 0, 5, 6, 7, 4, 4, 5, 6, 7)

    /** triTable[case] = flat list of edge indices, 3 per triangle. */
    val triTable: Array<IntArray> = generate()

    private fun edgeOf(a: Int, b: Int): Int {
        for (e in 0 until 12) if ((EDGE_A[e] == a && EDGE_B[e] == b) || (EDGE_A[e] == b && EDGE_B[e] == a)) return e
        error("no edge")
    }

    private fun generate(): Array<IntArray> {
        // faces: corners ordered ccw seen from outside
        val faces = ArrayList<IntArray>()
        for (axis in 0..2) for (side in 0..1) {
            val coord = arrayOf(CX, CY, CZ)
            val cs = (0 until 8).filter { coord[axis][it] == side }
            val a1 = coord[(axis + 1) % 3]; val a2 = coord[(axis + 2) % 3]
            val outward = if (side == 1) 1 else -1
            // u x v = outward * e_axis
            val (u, v) = if (outward > 0) a1 to a2 else a2 to a1
            val sorted = cs.sortedBy { atan2(v[it] - 0.5, u[it] - 0.5) }
            faces.add(sorted.toIntArray())
        }
        val px = DoubleArray(12); val py = DoubleArray(12); val pz = DoubleArray(12)
        for (e in 0 until 12) {
            px[e] = (CX[EDGE_A[e]] + CX[EDGE_B[e]]) / 2.0
            py[e] = (CY[EDGE_A[e]] + CY[EDGE_B[e]]) / 2.0
            pz[e] = (CZ[EDGE_A[e]] + CZ[EDGE_B[e]]) / 2.0
        }
        return Array(256) { mask ->
            val inside = BooleanArray(8) { (mask shr it) and 1 == 1 }
            val next = IntArray(12) { -1 }
            for (f in faces) {
                val exits = ArrayList<Int>(); val enters = ArrayList<Int>()
                for (i in 0..3) {
                    val a = f[i]; val b = f[(i + 1) % 4]
                    if (inside[a] && !inside[b]) exits.add(i)
                    if (!inside[a] && inside[b]) enters.add(i)
                }
                fun edgeAt(i: Int) = edgeOf(f[i], f[(i + 1) % 4])
                if (exits.size == 1) next[edgeAt(exits[0])] = edgeAt(enters[0])
                else if (exits.size == 2) for (j in exits) next[edgeAt(j)] = edgeAt((j + 3) % 4)
            }
            val tris = ArrayList<IntArray>()
            val seen = BooleanArray(12)
            for (s in 0 until 12) {
                if (next[s] < 0 || seen[s]) continue
                val cycle = ArrayList<Int>()
                var e = s
                while (!seen[e]) { seen[e] = true; cycle.add(e); e = next[e] }
                for (i in 1 until cycle.size - 1) tris.add(intArrayOf(cycle[0], cycle[i], cycle[i + 1]))
            }
            // global orientation: normals must point towards the outside corners
            var gx = 0.0; var gy = 0.0; var gz = 0.0
            for (c in 0 until 8) {
                val sgn = if (inside[c]) -1.0 else 1.0
                gx += sgn * (CX[c] - 0.5); gy += sgn * (CY[c] - 0.5); gz += sgn * (CZ[c] - 0.5)
            }
            var score = 0.0
            for (t in tris) {
                val ax = px[t[1]] - px[t[0]]; val ay = py[t[1]] - py[t[0]]; val az = pz[t[1]] - pz[t[0]]
                val bx = px[t[2]] - px[t[0]]; val by = py[t[2]] - py[t[0]]; val bz = pz[t[2]] - pz[t[0]]
                score += (ay * bz - az * by) * gx + (az * bx - ax * bz) * gy + (ax * by - ay * bx) * gz
            }
            val flat = IntArray(tris.size * 3)
            for ((k, t) in tris.withIndex()) {
                if (score < 0) { flat[k * 3] = t[0]; flat[k * 3 + 1] = t[2]; flat[k * 3 + 2] = t[1] }
                else { flat[k * 3] = t[0]; flat[k * 3 + 1] = t[1]; flat[k * 3 + 2] = t[2] }
            }
            flat
        }
    }
}

object MarchingCubes {
    /**
     * Isosurface of [field] (node (i,j,k) at index i + nx*(j + ny*k), world position origin + (i,j,k)*[cell])
     * at level [iso]; values above [iso] are inside. Returns a mesh with per-vertex normals (outward) in the grid frame
     * offset by ([ox],[oy],[oz]). O(nx*ny*nz).
     */
    fun extract(
        field: FloatArray, nx: Int, ny: Int, nz: Int, iso: Float, cell: Float,
        ox: Float = 0f, oy: Float = 0f, oz: Float = 0f
    ): TriMesh {
        require(field.size == nx * ny * nz)
        val cache = LongIntMap(1024)
        var vert = FloatArray(3 * 1024); var nv = 0
        var idx = IntArray(3 * 1024); var ni = 0
        val cv = FloatArray(8)

        fun vertexFor(i: Int, j: Int, k: Int, e: Int): Int {
            val a = McTables.EDGE_A[e]; val b = McTables.EDGE_B[e]
            val lowCorner = if (McTables.CX[a] + McTables.CY[a] + McTables.CZ[a] <
                McTables.CX[b] + McTables.CY[b] + McTables.CZ[b]) a else b
            val axis = if (McTables.CX[a] != McTables.CX[b]) 0 else if (McTables.CY[a] != McTables.CY[b]) 1 else 2
            val li = i + McTables.CX[lowCorner]; val lj = j + McTables.CY[lowCorner]; val lk = k + McTables.CZ[lowCorner]
            val key = ((li + nx.toLong() * (lj + ny.toLong() * lk)) * 3) + axis
            val got = cache[key]
            if (got >= 0) return got
            val va = cv[a]; val vb = cv[b]
            var t = if (vb == va) 0.5f else (iso - va) / (vb - va)
            t = t.coerceIn(0f, 1f)
            val x = i + McTables.CX[a] + t * (McTables.CX[b] - McTables.CX[a])
            val y = j + McTables.CY[a] + t * (McTables.CY[b] - McTables.CY[a])
            val z = k + McTables.CZ[a] + t * (McTables.CZ[b] - McTables.CZ[a])
            if ((nv + 1) * 3 > vert.size) vert = vert.copyOf(vert.size * 2)
            vert[nv * 3] = ox + x * cell; vert[nv * 3 + 1] = oy + y * cell; vert[nv * 3 + 2] = oz + z * cell
            cache[key] = nv
            return nv++
        }

        for (k in 0 until nz - 1) for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
            var mask = 0
            for (c in 0 until 8) {
                val v = field[(i + McTables.CX[c]) + nx * ((j + McTables.CY[c]) + ny * (k + McTables.CZ[c]))]
                cv[c] = v
                if (v > iso) mask = mask or (1 shl c)
            }
            if (mask == 0 || mask == 255) continue
            val tri = McTables.triTable[mask]
            var q = 0
            while (q < tri.size) {
                val a = vertexFor(i, j, k, tri[q]); val b = vertexFor(i, j, k, tri[q + 1]); val c = vertexFor(i, j, k, tri[q + 2])
                q += 3
                if (a == b || b == c || a == c) continue
                if (ni + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
                idx[ni++] = a; idx[ni++] = b; idx[ni++] = c
            }
        }
        return TriMesh.withNormals(vert.copyOf(nv * 3), idx.copyOf(ni))
    }
}
