package com.example.arruler.texture

import com.example.arruler.objscan.TriMesh

/** One colour bucket: [rgb] (0xFFRRGGBB) and the triangle ids drawn in it. */
class ColourBucket(val rgb: Int, val triangles: IntArray)

/**
 * Pure fallback grouping: triangles (colour = mean of their three vertex colours) split by median cut into at most
 * [maxBuckets] colour groups, each drawn as one unlit-colour mesh (SceneView colour materials take one colour each).
 */
object VertexColourBuckets {
    fun build(mesh: TriMesh, vertexRgb: IntArray, maxBuckets: Int = 64): List<ColourBucket> {
        val nT = mesh.triangleCount
        if (nT == 0) return emptyList()
        val tc = Array(3) { FloatArray(nT) }
        for (t in 0 until nT) for (ch in 0..2) {
            val shift = 16 - 8 * ch
            var s = 0
            for (i in 0..2) s += (vertexRgb[mesh.indices[t * 3 + i]] shr shift) and 0xFF
            tc[ch][t] = s / 3f
        }
        val boxes = ArrayList<IntArray>()
        boxes.add(IntArray(nT) { it })
        while (boxes.size < maxBuckets) {
            var bi = -1; var bestRange = 0.5f; var bestCh = 0
            for ((i, b) in boxes.withIndex()) {
                if (b.size < 2) continue
                for (ch in 0..2) {
                    var lo = 255f; var hi = 0f
                    for (t in b) { val v = tc[ch][t]; if (v < lo) lo = v; if (v > hi) hi = v }
                    if (hi - lo > bestRange) { bestRange = hi - lo; bi = i; bestCh = ch }
                }
            }
            if (bi < 0) break
            val b = boxes[bi].sortedBy { tc[bestCh][it] }.toIntArray()
            boxes[bi] = b.copyOfRange(0, b.size / 2)
            boxes.add(b.copyOfRange(b.size / 2, b.size))
        }
        return boxes.map { b ->
            var r = 0.0; var g = 0.0; var bl = 0.0
            for (t in b) { r += tc[0][t]; g += tc[1][t]; bl += tc[2][t] }
            val n = b.size
            ColourBucket((0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt(), b)
        }
    }
}

// The Filament nodes that drew these buckets and the photo atlas now live in scan3d/Scan3DViewer.kt (one guarded upload
// path for every part, see scan3d/ViewerScene.kt).
