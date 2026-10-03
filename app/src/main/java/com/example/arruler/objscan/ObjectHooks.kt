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
) {
    companion object {
        const val OBJ_MTL_REF = "mtllib mesh.mtl"
        const val MTL_PNG_REF = "map_Kd texture.png"
    }
}

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
