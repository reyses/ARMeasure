package com.example.arruler.scan3d

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.arruler.objscan.TriMesh
import java.io.File

enum class MeshExport(val label: String, val ext: String, val mime: String) {
    OBJ("Mesh (OBJ)", "obj", "text/plain"),
    PLY("Mesh (PLY)", "ply", "application/octet-stream"),
}

/** Writes an object mesh under cacheDir/exports (the FileProvider path PlanShare declares) and opens the share sheet. */
object MeshShare {
    fun share(context: Context, mesh: TriMesh, format: MeshExport, stamp: Long = System.currentTimeMillis()): Boolean {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "object_$stamp.${format.ext}")
        when (format) {
            MeshExport.OBJ -> out.writeText(mesh.toObj("object"), Charsets.UTF_8)
            MeshExport.PLY -> out.writeBytes(mesh.toBinaryPly())
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ARMeasure object ${format.label}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share object").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}
