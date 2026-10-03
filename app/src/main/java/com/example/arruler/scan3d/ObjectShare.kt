package com.example.arruler.scan3d

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.arruler.measure.Units
import com.example.arruler.objscan.TexturedObject
import com.example.arruler.objscan.TriMesh
import com.example.arruler.store.ExportNames
import com.example.arruler.store.MeasurementsText
import com.example.arruler.store.ObjectExportInfo
import java.io.File

enum class ObjectShareFormat(val label: String, val ext: String, val mime: String, val part: String) {
    OBJ("Mesh (OBJ)", "obj", "text/plain", "mesh"),
    PLY("Mesh (PLY)", "ply", "application/octet-stream", "mesh"),
    JSON("Measurements (JSON)", "json", "application/json", "measurements"),
    TXT("Measurements (TXT)", "txt", "text/plain", "measurements"),
}

/** Shares a saved object through the system share sheet (files under cacheDir/exports, human-readable names). */
object ObjectShare {
    fun share(
        context: Context, info: ObjectExportInfo, mesh: TriMesh, textured: TexturedObject?, units: Units, format: ObjectShareFormat,
    ): Boolean {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val files = ArrayList<File>()
        fun out(part: String, ext: String) = File(dir, ExportNames.plain(info.name, part, ext)).also { files += it }
        when (format) {
            ObjectShareFormat.OBJ -> if (textured != null) {
                val mtl = ExportNames.plain(info.name, "mesh", "mtl")
                val png = ExportNames.plain(info.name, "texture", "png")
                out("mesh", "obj").writeText(textured.obj.replace(TexturedObject.OBJ_MTL_REF, "mtllib $mtl"), Charsets.UTF_8)
                out("mesh", "mtl").writeText(textured.mtl.replace(TexturedObject.MTL_PNG_REF, "map_Kd $png"), Charsets.UTF_8)
                out("texture", "png").writeBytes(textured.png)
            } else {
                out("mesh", "obj").writeText(mesh.toObj(ExportNames.sanitize(info.name, "object")), Charsets.UTF_8)
            }
            ObjectShareFormat.PLY -> out("mesh", "ply").writeBytes(mesh.toBinaryPly())
            ObjectShareFormat.JSON -> out("measurements", "json").writeText(MeasurementsText.json(info), Charsets.UTF_8)
            ObjectShareFormat.TXT -> out("measurements", "txt").writeText(MeasurementsText.txt(info, units), Charsets.UTF_8)
        }
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (files.size == 1) format.mime else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "ARMeasure object ${info.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share object").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}
