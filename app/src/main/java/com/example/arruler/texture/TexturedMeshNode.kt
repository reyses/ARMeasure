package com.example.arruler.texture

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.example.arruler.objscan.TriMesh
import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager.PrimitiveType
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.SceneScope
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.texture.ImageTexture

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

private fun texturedGeometry(engine: Engine, m: TriMesh, uvs: FloatArray): Geometry {
    val verts = ArrayList<Geometry.Vertex>(m.vertexCount)
    for (i in 0 until m.vertexCount) {
        verts += Geometry.Vertex(
            position = Float3(m.vertices[i * 3], m.vertices[i * 3 + 1], m.vertices[i * 3 + 2]),
            normal = Float3(m.normals[i * 3], m.normals[i * 3 + 1], m.normals[i * 3 + 2]),
            uvCoordinate = Float2(uvs[i * 2], uvs[i * 2 + 1]),
        )
    }
    val ind = ArrayList<Int>(m.indices.size)
    for (i in m.indices) ind += i
    return Geometry.Builder(PrimitiveType.TRIANGLES).vertices(verts).indices(ind).build(engine)
}

/** Geometry of the triangles in [bucket] with vertices compacted. */
private fun bucketGeometry(engine: Engine, m: TriMesh, bucket: ColourBucket): Geometry {
    val remap = HashMap<Int, Int>()
    val verts = ArrayList<Geometry.Vertex>()
    val ind = ArrayList<Int>(bucket.triangles.size * 3)
    for (t in bucket.triangles) for (i in 0..2) {
        val v = m.indices[t * 3 + i]
        val id = remap.getOrPut(v) {
            verts += Geometry.Vertex(
                position = Float3(m.vertices[v * 3], m.vertices[v * 3 + 1], m.vertices[v * 3 + 2]),
                normal = Float3(m.normals[v * 3], m.normals[v * 3 + 1], m.normals[v * 3 + 2]),
                uvCoordinate = Float2(0f, 0f),
            )
            verts.size - 1
        }
        ind += id
    }
    return Geometry.Builder(PrimitiveType.TRIANGLES).vertices(verts).indices(ind).build(engine)
}

/**
 * Draws a photo-textured [TriMesh] ([uvs] and [atlas] from [TextureBaker.bake]; top-left uv origin) inside a SceneView
 * scene. SceneView 4.52 API used: `ImageTexture.Builder().bitmap(atlas).build(engine)` and
 * `MaterialLoader.createTextureInstance(texture, isOpaque, metallic, roughness, reflectance)` (a lit textured material),
 * with `Geometry.Vertex(uvCoordinate = ...)`. The texture is destroyed when the node leaves the composition.
 */
@Composable
fun SceneScope.TexturedMeshNode(engine: Engine, materialLoader: MaterialLoader, mesh: TriMesh, uvs: FloatArray, atlas: Bitmap) {
    key(mesh, atlas) {
        val texture = remember(atlas) { ImageTexture.Builder().bitmap(atlas).build(engine) }
        // declared before the node so that, disposing in reverse order, the texture outlives the renderable
        DisposableEffect(texture) { onDispose { runCatching { engine.destroyTexture(texture) } } }
        val geo = remember(mesh, uvs) { texturedGeometry(engine, mesh, uvs) }
        val mat = remember(texture) { materialLoader.createTextureInstance(texture, true, 0f, 0.85f, 0.3f) }
        MeshNode(
            primitiveType = geo.primitiveType,
            vertexBuffer = geo.vertexBuffer,
            indexBuffer = geo.indexBuffer,
            boundingBox = geo.boundingBox,
            materialInstance = mat,
        )
    }
}

/**
 * Fallback for meshes with per-vertex colour only (LOW-tier phones, [TextureBaker.bakeVertexColors]): triangles are
 * grouped into at most [maxBuckets] colour buckets, one unlit mesh each (the same trick the point viewer uses).
 */
@Composable
fun SceneScope.VertexColouredMeshNode(engine: Engine, materialLoader: MaterialLoader, mesh: TriMesh, vertexRgb: IntArray, maxBuckets: Int = 64) {
    val buckets = remember(mesh, vertexRgb) { VertexColourBuckets.build(mesh, vertexRgb, maxBuckets) }
    for ((i, b) in buckets.withIndex()) key(mesh, i) {
        val geo = remember(mesh, b) { bucketGeometry(engine, mesh, b) }
        val mat = remember(b.rgb) {
            materialLoader.createUnlitColorInstance(
                Float4(((b.rgb shr 16) and 0xFF) / 255f, ((b.rgb shr 8) and 0xFF) / 255f, (b.rgb and 0xFF) / 255f, 1f)
            )
        }
        MeshNode(
            primitiveType = geo.primitiveType,
            vertexBuffer = geo.vertexBuffer,
            indexBuffer = geo.indexBuffer,
            boundingBox = geo.boundingBox,
            materialInstance = mat,
        )
    }
}
