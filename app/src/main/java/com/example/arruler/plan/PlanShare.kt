package com.example.arruler.plan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.example.arruler.measure.Units
import com.example.arruler.store.Project
import com.example.arruler.store.ProjectCodec
import com.example.arruler.store.toFloorPlan
import java.io.File

enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    PNG("PNG image", "png", "image/png"),
    SVG("SVG drawing", "svg", "image/svg+xml"),
    DXF("DXF (CAD)", "dxf", "application/dxf"),
    JSON("JSON project", "json", "application/json"),
}

/** Writes a project export under cacheDir/exports and hands it to the share sheet via FileProvider. */
object PlanShare {
    private const val DIR = "exports"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Creates the export file for [project] in [format]; the plan uses [units] and [showAngles]. */
    fun export(context: Context, project: Project, format: ExportFormat, units: Units, showAngles: Boolean): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        pruneOld(dir)
        val file = File(dir, "${safeName(project.name)}.${format.extension}")
        val plan = project.toFloorPlan()
        when (format) {
            ExportFormat.PNG -> {
                val b = plan.bounds()
                val aspect = if (b.width + b.height > 1e-6f) (b.height + 2.4f) / (b.width + 2.4f) else 1f
                val w = 2400
                val h = (w * aspect).toInt().coerceIn(1200, 3200)
                val bmp = toPngBitmap(plan, units, w, h, showAngles)
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
            ExportFormat.SVG -> file.writeText(toSvg(plan, units, showAngles), Charsets.UTF_8)
            ExportFormat.DXF -> file.writeText(toDxf(plan), Charsets.UTF_8)
            ExportFormat.JSON -> file.writeText(ProjectCodec.encode(project), Charsets.UTF_8)
        }
        com.example.arruler.beam.Beam.attach("export", file)
        return file
    }

    /** Opens the system share sheet for [file]. */
    fun share(context: Context, file: File, format: ExportFormat, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun safeName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "plan" }

    private fun pruneOld(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}
