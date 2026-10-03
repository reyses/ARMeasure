package com.example.arruler.texture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.arruler.depth.CaptureObserver
import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.ResultContext
import com.example.arruler.objscan.SupportPlane
import com.example.arruler.objscan.TextureProvider
import com.example.arruler.objscan.TexturedMeshData
import com.example.arruler.objscan.TexturedObject
import com.example.arruler.objscan.TriMesh
import com.example.arruler.processing.ResultPackage
import com.example.arruler.processing.Tier
import com.google.ar.core.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The walk-around photos of one object capture, as a [CaptureObserver]: starts a [KeyframeCapture] with the capture,
 * feeds it every AR frame, stops it at Finish. [records] (off the main thread) writes `keyframes.json` and waits for
 * the JPEG encoder; the photos live in cacheDir until the next capture or a reset.
 */
class WalkKeyframes(private val root: File) : CaptureObserver {
    private var capture: KeyframeCapture? = null
    private var centre: Vec3? = null
    private var stopped = true
    private var cached: List<KeyframeRecord>? = null

    /** The folder of the current capture's photos (null before the first capture). */
    @Volatile var dir: File? = null
        private set

    override fun onCaptureStart(box: ObjectBox, plane: SupportPlane, resumed: Boolean) {
        centre = box.volumeCentre()
        if (resumed && capture != null && !stopped) return
        discard()
        val d = File(root, "walk_${System.currentTimeMillis()}")
        dir = d
        cached = null
        capture = KeyframeCapture(d)
        stopped = false
    }

    override fun onFrame(frame: Frame) {
        if (stopped) return
        capture?.onFrame(frame, centre ?: return)
    }

    override fun onCaptureFinish() {
        stopped = true
    }

    override fun onCaptureReset() {
        stopped = true
        discard()
    }

    /** Photos kept so far (live counter, 0 when idle). */
    val keptCount: Int get() = capture?.keptCount ?: 0

    /** The finished photo list: waits for pending JPEG encodes and writes keyframes.json. Safe to call twice. */
    suspend fun records(): List<KeyframeRecord> = withContext(Dispatchers.IO) {
        synchronized(this@WalkKeyframes) {
            cached ?: run {
                val c = capture
                val d = dir
                val r = when {
                    c != null -> c.finish()
                    d != null -> KeyframeStore.read(d)
                    else -> emptyList()
                }
                r.also { cached = it }
            }
        }
    }

    private fun discard() {
        val old = dir
        capture = null
        dir = null
        cached = null
        if (old != null) Thread { old.deleteRecursively() }.start()
        // earlier sessions that were never cleaned (a crash): everything but the live folder
        Thread { root.listFiles()?.forEach { if (it != dir && it.name.startsWith("walk_")) it.deleteRecursively() } }.start()
    }

    companion object {
        /** Fewer photos than this cannot be baked or sent. */
        const val MIN_PHOTOS = 6
    }
}

/**
 * Bakes the photo texture of the grey mesh from the walk keyframes: the atlas (OBJ + MTL + PNG) on MID / HIGH phones,
 * one colour per vertex on LOW ones; both also get a vertex-colour PLY. Runs on a background dispatcher; [onProgress]
 * receives a text and a 0..1 fraction (null = unknown) for the progress line. Null when there are too few photos.
 */
class KeyframeTextureProvider(
    private val walk: WalkKeyframes,
    private val tier: () -> Tier,
    private val photoRotationDeg: () -> Int,
    private val onProgress: (String, Float?) -> Unit,
) : TextureProvider {

    override suspend fun bake(ctx: ResultContext): TexturedObject? = withContext(Dispatchers.Default) {
        val mesh = ctx.mesh ?: return@withContext null
        val dir = walk.dir ?: return@withContext null
        val recs = walk.records()
        if (recs.size < WalkKeyframes.MIN_PHOTOS) return@withContext null
        val t = tier()
        onProgress("Loading ${recs.size} photos", null)
        val photo = KeyframeLoader.bestPhoto(dir, recs, rotateDeg = photoRotationDeg())
        val kfs = KeyframeLoader.load(dir, recs, KeyframeBudget.totalPixels(t), cancelled = { !isActive })
        ensureActive()
        if (kfs.size < WalkKeyframes.MIN_PHOTOS) return@withContext null
        val cancelled = { !isActive }
        if (t == Tier.LOW) {
            onProgress("Colouring the mesh", null)
            val rgb = TextureBaker.bakeVertexColors(mesh, kfs, cancelled = cancelled)
            val ply = TexturedMeshText.plyColoured(mesh, rgb)
            TexturedObject("", "", ByteArray(0), photo, ply, TexturedMeshData(mesh, null, null, rgb))
        } else {
            val baked = TextureBaker.bake(mesh, kfs, progress = { f -> onProgress("Texturing the mesh, ${(f * 100).toInt()} %", f) }, cancelled = cancelled)
            onProgress("Writing the texture", null)
            val atlas = TexturedMeshWriter.atlasBitmap(baked)
            val png = ByteArrayOutputStream().also { atlas.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val rgb = TextureBaker.bakeVertexColors(mesh, kfs, cancelled = cancelled)
            TexturedObject(
                TexturedMeshText.obj(baked.mesh, baked.uvs, "mesh.mtl"), TexturedMeshText.mtl("texture.png"), png, photo,
                TexturedMeshText.plyColoured(mesh, rgb), TexturedMeshData(baked.mesh, baked.uvs, atlas),
            )
        }
    }
}

/** The viewer data of a stored textured object: parsed from its OBJ + PNG (or its colour PLY) once, then cached on it. Call off the main thread. */
fun TexturedObject.meshData(): TexturedMeshData? {
    viewData?.let { return it }
    val d: TexturedMeshData? = if (hasAtlas) {
        val parsed = TexturedObjParser.parseObj(obj)
        val bmp = decodeBounded(png, MAX_ATLAS_PX)
        if (parsed != null && bmp != null) TexturedMeshData(parsed.mesh, parsed.uvs, bmp) else null
    } else {
        colourPly?.let { TexturedObjParser.readColouredPly(it) }?.let { (m, rgb) -> TexturedMeshData(m, null, null, rgb) }
    }
    viewData = d
    return d
}

private const val MAX_ATLAS_PX = 4096

/** PNG / JPEG bytes as a bitmap no larger than [maxSide] on either side (power-of-two decode). */
fun decodeBounded(bytes: ByteArray, maxSide: Int): Bitmap? {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
    if (b.outWidth <= 0) return null
    var sample = 1
    while (maxOf(b.outWidth, b.outHeight) / sample > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** The PC photogrammetry result: textured OBJ (UVs, no mtllib) + texture.png, cropped to the object. */
class PcTextured(val mesh: TriMesh, val textured: TexturedObject)

object PcTexturedResult {
    /** Triangles within this height of the support plane are the floor under the object and are dropped. */
    const val FLOOR_MARGIN_M = 0.012f
    const val CROP_MARGIN_M = 0.04f
    private const val MIN_TRIANGLES = 50

    /**
     * Reads mesh.obj + texture.png from the result ZIP. The server's mesh holds the whole scene, so triangles whose
     * centre is outside [box] (grown by [CROP_MARGIN_M]) or on the [plane] are dropped; when that leaves almost nothing
     * the whole mesh is kept. The OBJ is rewritten with `mtllib mesh.mtl` so the exporter can rename the references.
     */
    fun read(zip: File, box: ObjectBox?, plane: SupportPlane?, photo: Bitmap?): PcTextured? {
        val objText = ResultPackage.readEntry(zip, "mesh.obj")?.toString(Charsets.UTF_8) ?: return null
        val png = ResultPackage.readEntry(zip, "texture.png") ?: return null
        val crop: ((Float, Float, Float) -> Boolean)? = if (box == null) null else { x, y, z ->
            box.contains(x, y, z, CROP_MARGIN_M) && (plane == null || plane.signedDistance(x, y, z) > FLOOR_MARGIN_M)
        }
        val parsed = crop?.let { TexturedObjParser.parseObj(objText, it) }?.takeIf { it.mesh.triangleCount >= MIN_TRIANGLES }
            ?: TexturedObjParser.parseObj(objText) ?: return null
        val bmp = decodeBounded(png, 4096) ?: return null
        val obj = TexturedMeshText.obj(parsed.mesh, parsed.uvs, "mesh.mtl")
        val t = TexturedObject(obj, TexturedMeshText.mtl("texture.png"), png, photo, null, TexturedMeshData(parsed.mesh, parsed.uvs, bmp))
        return PcTextured(parsed.mesh, t)
    }
}
