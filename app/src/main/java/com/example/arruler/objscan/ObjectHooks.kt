package com.example.arruler.objscan

/**
 * A textured object: OBJ text referencing `mtllib mesh.mtl`, MTL text with `map_Kd texture.png`, and the PNG.
 * The exporter renames those two references to the human-readable file names it writes.
 */
class TexturedObject(
    val obj: String,
    val mtl: String,
    val png: ByteArray,
    /** The best keyframe photo; when set it replaces the grey mesh render as the gallery thumbnail. */
    val bestPhoto: android.graphics.Bitmap? = null,
    /** Vertex-colour PLY of the mesh (binary, float xyz + normals, uchar rgb); LOW-tier phones have only this. */
    val colourPly: ByteArray? = null,
    /** What the viewer draws; filled by the baker, or parsed from the stored files by `TexturedObjParser` when null. */
    var viewData: TexturedMeshData? = null,
) {
    /** True when [obj] / [mtl] / [png] hold a UV-mapped textured mesh (false for a vertex-colour-only result). */
    val hasAtlas: Boolean get() = obj.isNotEmpty() && png.isNotEmpty()

    companion object {
        const val OBJ_MTL_REF = "mtllib mesh.mtl"
        const val MTL_PNG_REF = "map_Kd texture.png"
    }
}

/**
 * A coloured mesh as the viewer draws it: [uvs] (two floats per vertex, origin top-left) with the [atlas] bitmap, or
 * one 0xFFRRGGBB per vertex in [vertexRgb]. Both null means grey.
 */
class TexturedMeshData(
    val mesh: TriMesh,
    val uvs: FloatArray? = null,
    val atlas: android.graphics.Bitmap? = null,
    val vertexRgb: IntArray? = null,
)

/** What a [ResultAnnotator] / [TextureProvider] gets to look at once the object result exists. */
class ResultContext(
    val mesh: TriMesh?,
    /** The captured (isolated) points as packed world xyz, may be empty on a PC result. */
    val points: FloatArray,
    val box: ObjectBox,
    val plane: SupportPlane,
    val summary: com.example.arruler.scan3d.ObjectSummary,
)

/**
 * Adds extra lines to the object result card and the exported measurements, e.g. the primitive fit:
 * 'Looks like a cylinder' to 'r 9.8 cm, h 20.1 cm, formula volume ...'. Registered in MainActivity.
 */
fun interface ResultAnnotator {
    suspend fun annotate(ctx: ResultContext, units: com.example.arruler.measure.Units): List<Pair<String, String>>
}

/** Bakes a textured mesh from the keyframes of the capture; null when there are none. Registered in MainActivity. */
fun interface TextureProvider {
    suspend fun bake(ctx: ResultContext): TexturedObject?
}
