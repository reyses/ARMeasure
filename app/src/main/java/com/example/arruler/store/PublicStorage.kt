package com.example.arruler.store

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.arruler.measure.Units
import com.example.arruler.plan.FloorPlan
import com.example.arruler.plan.toPngBitmap
import java.io.File
import java.io.IOException

/** API 29+: files go into the public Downloads collection through MediaStore (pending while they are written). */
private class MediaStoreSink(private val context: Context) : PublicSink {
    override fun write(relativeDir: String, name: String, mime: String, content: ContentSource): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("cannot create $name")
        try {
            resolver.openOutputStream(uri)?.use { out -> content.open().use { it.copyTo(out) } } ?: throw IOException("cannot open $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri.toString()
    }
}

/** API 24-28: plain files under the public Downloads directory (needs WRITE_EXTERNAL_STORAGE), then a media scan. */
private class LegacySink(private val context: Context) : PublicSink {
    @Suppress("DEPRECATION")
    override fun write(relativeDir: String, name: String, mime: String, content: ContentSource): String? {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, relativeDir.removePrefix("Download/").trimEnd('/'))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val file = File(dir, name)
        file.outputStream().use { out -> content.open().use { it.copyTo(out) } }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        return file.absolutePath
    }
}

/** Android glue of the public export: the sink for this API level, the legacy permission, share and open-folder intents. */
object PublicStorage {
    private const val DOCS_AUTHORITY = "com.android.externalstorage.documents"

    fun sink(context: Context): PublicSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreSink(context) else LegacySink(context)

    /** True on API 24-28 while WRITE_EXTERNAL_STORAGE is not granted (ask for it before the first save). */
    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun exporter(context: Context): PublicExporter = PublicExporter(sink(context), ::planPng)

    /** The plan as PNG bytes (the same renderer as the share sheet export). */
    fun planPng(plan: FloorPlan, units: Units): ByteArray {
        val b = plan.bounds()
        val aspect = if (b.width + b.height > 1e-6f) (b.height + 2.4f) / (b.width + 2.4f) else 1f
        val w = 2400
        val h = (w * aspect).toInt().coerceIn(1200, 3200)
        val bmp = toPngBitmap(plan, units, w, h, false)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun uriOf(context: Context, handle: String): Uri =
        if (handle.startsWith("content:")) Uri.parse(handle)
        else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(handle))

    /**
     * Opens the project's export folder. Order: the folder in the Files app (DocumentsContract view of the external
     * storage provider), then the Downloads list, then the Files app's launcher. False when none could open.
     */
    fun openFolder(context: Context, project: String): Boolean {
        val folder = Intent(Intent.ACTION_VIEW)
            .setDataAndType(DocumentsContract.buildDocumentUri(DOCS_AUTHORITY, ExportNames.folderDocumentId(project)), DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val downloads = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val files = listOf("com.google.android.documentsui", "com.android.documentsui")
            .firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
        for (i in listOfNotNull(folder, downloads, files)) {
            try {
                context.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    /** Opens the share sheet for the exported files. */
    fun share(context: Context, result: ExportResult, title: String = "Share export") {
        val uris = ArrayList<Uri>(result.files.mapNotNull { f -> f.handle?.let { uriOf(context, it) } })
        if (uris.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** The MediaStore Uri of an exported file (API 29+), or null when it is not there. */
    fun findUri(context: Context, project: String, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(fileName, ExportNames.relativePath(project))
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
        }
        return null
    }

    /** Plays a video: [uri] first (the exported copy in Downloads), else the private [fallback] file through the FileProvider. */
    fun playVideo(context: Context, uri: Uri?, fallback: File?): Boolean {
        val target = uri ?: fallback?.takeIf { it.isFile }?.let { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
            ?: return false
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(target, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try { context.startActivity(view); true } catch (_: ActivityNotFoundException) { false }
    }
}
