package com.example.arruler.scan3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.example.arruler.objscan.TriMesh
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One projected, shaded triangle in pixel coordinates (y down); [depth] grows towards the viewer. */
class ProjectedTri(
    val x0: Float, val y0: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val shade: Float, val depth: Float,
)

/**
 * Orthographic projection of a mesh for the gallery thumbnail (pure maths, JVM-tested; drawing is [ObjectThumbnail]).
 * The camera looks from the front, [yawDeg] around the vertical and [pitchDeg] above the horizon, and the mesh is fitted
 * and centred in a [size] x [size] square with a [margin] fraction left empty. Triangles come back far to near
 * (painter's order); shade is 0.3..1 from a fixed light, two-sided so meshes with mixed winding still look right.
 * Meshes above [maxTris] triangles are thinned evenly.
 */
object MeshProjection {
    fun project(
        mesh: TriMesh, size: Int, margin: Float = 0.08f, yawDeg: Float = 35f, pitchDeg: Float = 25f, maxTris: Int = 30_000,
    ): List<ProjectedTri> {
        val nv = mesh.vertexCount
        val nt = mesh.triangleCount
        if (nv == 0 || nt == 0 || size <= 0) return emptyList()
        val yaw = yawDeg * PI.toFloat() / 180f
        val pitch = pitchDeg * PI.toFloat() / 180f
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val u = FloatArray(nv); val v = FloatArray(nv); val w = FloatArray(nv)
        var uMin = Float.MAX_VALUE; var uMax = -Float.MAX_VALUE
        var vMin = Float.MAX_VALUE; var vMax = -Float.MAX_VALUE
        for (i in 0 until nv) {
            val x = mesh.vertices[i * 3]; val y = mesh.vertices[i * 3 + 1]; val z = mesh.vertices[i * 3 + 2]
            val x1 = x * cy + z * sy
            val z1 = -x * sy + z * cy
            u[i] = x1
            v[i] = y * cp - z1 * sp
            w[i] = y * sp + z1 * cp
            uMin = minOf(uMin, u[i]); uMax = maxOf(uMax, u[i])
            vMin = minOf(vMin, v[i]); vMax = maxOf(vMax, v[i])
        }
        val span = maxOf(uMax - uMin, vMax - vMin, 1e-6f)
        val scale = size * (1f - 2f * margin) / span
        val uc = (uMin + uMax) / 2f; val vc = (vMin + vMax) / 2f
        val half = size / 2f
        val step = if (nt > maxTris) nt.toFloat() / maxTris else 1f
        val out = ArrayList<ProjectedTri>(minOf(nt, maxTris))
        var f = 0f
        while (f < nt) {
            val t = f.toInt()
            f += step
            val a = mesh.indices[t * 3]; val b = mesh.indices[t * 3 + 1]; val c = mesh.indices[t * 3 + 2]
            val e1u = u[b] - u[a]; val e1v = v[b] - v[a]; val e1w = w[b] - w[a]
            val e2u = u[c] - u[a]; val e2v = v[c] - v[a]; val e2w = w[c] - w[a]
            var nu = e1v * e2w - e1w * e2v
            var nvv = e1w * e2u - e1u * e2w
            var nw = e1u * e2v - e1v * e2u
            val len = sqrt(nu * nu + nvv * nvv + nw * nw)
            if (len < 1e-12f) continue
            nu /= len; nvv /= len; nw /= len
            if (nw < 0f) { nu = -nu; nvv = -nvv; nw = -nw }
            val lambert = maxOf(0f, nu * LIGHT_U + nvv * LIGHT_V + nw * LIGHT_W)
            out += ProjectedTri(
                half + (u[a] - uc) * scale, half - (v[a] - vc) * scale,
                half + (u[b] - uc) * scale, half - (v[b] - vc) * scale,
                half + (u[c] - uc) * scale, half - (v[c] - vc) * scale,
                0.3f + 0.7f * lambert, (w[a] + w[b] + w[c]) / 3f,
            )
        }
        out.sortBy { it.depth }
        return out
    }

    private val LIGHT_U: Float
    private val LIGHT_V: Float
    private val LIGHT_W: Float

    init {
        val lu = -0.4f; val lv = 0.6f; val lw = 0.7f
        val l = sqrt(lu * lu + lv * lv + lw * lw)
        LIGHT_U = lu / l; LIGHT_V = lv / l; LIGHT_W = lw / l
    }

    /** Largest absolute pixel coordinate offset from the centre (test helper for the fit). */
    internal fun extent(tris: List<ProjectedTri>, size: Int): Float {
        var m = 0f
        for (t in tris) for (p in floatArrayOf(t.x0, t.y0, t.x1, t.y1, t.x2, t.y2)) m = maxOf(m, abs(p - size / 2f))
        return m
    }
}

/** Draws the gallery thumbnail of an object mesh (and saves it next to the scan); the drawing itself is device-only. */
object ObjectThumbnail {
    const val SIZE = 512

    /** [mesh] as a shaded [size] px square on a dark background. */
    fun render(mesh: TriMesh, size: Int = SIZE): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0x1B, 0x1D, 0x22))
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 0.6f }
        val path = Path()
        for (t in MeshProjection.project(mesh, size)) {
            val k = t.shade
            fill.color = Color.rgb((0x8F * k).toInt().coerceIn(0, 255), (0xA8 * k).toInt().coerceIn(0, 255), (0xC4 * k).toInt().coerceIn(0, 255))
            path.reset()
            path.moveTo(t.x0, t.y0); path.lineTo(t.x1, t.y1); path.lineTo(t.x2, t.y2); path.close()
            c.drawPath(path, fill)
        }
        return bmp
    }

    fun png(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /**
     * Saves the thumbnail of scan [id]: [thumbnailOverride] (the texture drone's best keyframe photo) when given,
     * else the mesh render. Returns the PNG file, or null when there is neither.
     */
    fun save(root: File, id: String, mesh: TriMesh?, thumbnailOverride: Bitmap? = null): File? {
        val bmp = thumbnailOverride ?: mesh?.let { render(it) } ?: return null
        ScanFiles.saveThumbnail(root, id, png(bmp))
        if (thumbnailOverride == null) bmp.recycle()
        return ScanFiles.thumbFile(root, id).takeIf { it.isFile }
    }

    /** The saved thumbnail, rendering it from the stored mesh first when it is missing (old saves). Call off the main thread. */
    fun ensure(root: File, id: String): File? {
        val f = ScanFiles.thumbFile(root, id)
        if (f.isFile) return f
        val snap = ScanFiles.load(root, id) ?: return null
        return save(root, id, snap.mesh)
    }
}
