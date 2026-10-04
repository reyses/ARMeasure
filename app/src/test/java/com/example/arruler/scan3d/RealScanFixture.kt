package com.example.arruler.scan3d

import com.example.arruler.depth.PlaneKind
import com.example.arruler.geometry.PlaneBasis
import com.example.arruler.geometry.Vec3
import kotlin.random.Random

/**
 * The owner's surfaces OBJ exported just before the View 3D crash (scan 2026-10-03 19:17, 113 vertices, 8 groups:
 * wall_1..wall_5, other_1..other_3, all near-vertical; NO floor and NO ceiling, so the scan had no room).
 * Each group is one plane outline in order (the export fans it from the first vertex).
 */
object RealScanFixture {
    const val PATH = "fixtures/real_scan_2026-10-03_surfaces.obj"

    class Group(val name: String, val outline: FloatArray)

    fun groups(): List<Group> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(PATH)) { "missing $PATH" }
            .bufferedReader().use { it.readText() }
        val out = ArrayList<Group>()
        var name: String? = null
        val cur = ArrayList<Float>()
        fun flush() { name?.let { out += Group(it, cur.toFloatArray()) }; cur.clear() }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("g ") -> { flush(); name = line.substring(2).trim() }
                line.startsWith("v ") -> line.substring(2).trim().split(Regex("\\s+")).take(3).forEach { cur += it.toFloat() }
            }
        }
        flush()
        return out
    }

    fun kindOf(name: String): PlaneKind = when (name.substringBefore('_').lowercase()) {
        "floor" -> PlaneKind.FLOOR
        "ceiling" -> PlaneKind.CEILING
        "wall" -> PlaneKind.WALL
        else -> PlaneKind.OTHER
    }

    /** The groups as snapshot planes (Newell normal, d through the outline centroid). */
    fun planes(): List<SnapshotPlane> = groups().map { g ->
        val pts = List(g.outline.size / 3) { Vec3(g.outline[it * 3], g.outline[it * 3 + 1], g.outline[it * 3 + 2]) }
        val basis = PlaneBasis.fitFromPoints(pts)
        val n = basis.normal
        SnapshotPlane(kindOf(g.name), n.x, n.y, n.z, n.dot(basis.origin), g.outline, 1000)
    }

    /** [perPlane] points sampled uniformly on every outline's fan triangles (seeded), packed xyz. */
    fun samplePoints(perPlane: Int, seed: Int = 7): FloatArray {
        val r = Random(seed)
        val out = ArrayList<Float>()
        for (g in groups()) {
            val o = g.outline
            val n = o.size / 3
            for (k in 0 until perPlane) {
                val t = 1 + r.nextInt(n - 2)
                var a = r.nextFloat(); var b = r.nextFloat()
                if (a + b > 1f) { a = 1f - a; b = 1f - b }
                for (c in 0..2) out += o[c] + a * (o[t * 3 + c] - o[c]) + b * (o[(t + 1) * 3 + c] - o[c])
            }
        }
        return out.toFloatArray()
    }
}
