package com.example.arruler.scan3d

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Where scans live: filesDir/scans. */
fun scansRoot(context: Context): File = File(context.filesDir, "scans")

enum class ScanExport(val label: String, val ext: String, val mime: String) {
    PLY("Point cloud (PLY)", "ply", "application/octet-stream"),
    OBJ("Surfaces (OBJ)", "obj", "text/plain"),
    JSON("Planes (JSON)", "json", "application/json"),
}

/** Copies a scan export under cacheDir/exports (the FileProvider path PlanShare already declares) and opens the share sheet. */
object ScanShare {
    fun share(context: Context, root: File, id: String, format: ScanExport): Boolean {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safe = ScanFiles.dir(root, id).name
        val out = File(dir, "scan_${safe}.${format.ext}")
        when (format) {
            ScanExport.PLY -> (
                File(ScanFiles.dir(root, id), ScanFiles.MESH_NAME).takeIf { it.exists() }
                    ?: File(ScanFiles.dir(root, id), ScanFiles.PLY_NAME).takeIf { it.exists() }
                )?.copyTo(out, overwrite = true) ?: return false
            ScanExport.JSON -> File(ScanFiles.dir(root, id), ScanFiles.JSON_NAME).takeIf { it.exists() }?.copyTo(out, overwrite = true) ?: return false
            ScanExport.OBJ -> ScanFiles.exportObj(ScanFiles.load(root, id) ?: return false, out)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ARMeasure scan ${format.label}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share scan").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}

/**
 * Saved 3D scans of one project. [scans] is the list from [ScanFiles.list] (the caller refreshes it after
 * [onDelete]); renders nothing when empty.
 */
@Composable
fun ScanListSection(
    scans: List<ScanInfo>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (scans.isEmpty()) return
    val context = LocalContext.current
    val root = remember { scansRoot(context) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("3D scans", style = MaterialTheme.typography.titleMedium)
        for (s in scans) {
            var shareMenu by remember(s.id) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(fmt.format(Date(s.createdAt)), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${s.pointCount} points" + (s.roomAreaM2?.let { "  |  room %.1f m2".format(Locale.US, it) } ?: "") +
                            (if (s.kind == ScanSnapshot.KIND_OBJECT) "  |  object" + (s.objectVolumeM3?.let { " %.4f m3".format(Locale.US, it) } ?: "") else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpen(s.id) }) { Text("Open") }
                        TextButton(onClick = { shareMenu = true }) { Text("Share") }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            for (f in ScanExport.values()) DropdownMenuItem(
                                text = { Text(f.label) },
                                onClick = { shareMenu = false; ScanShare.share(context, root, s.id, f) },
                            )
                        }
                        TextButton(onClick = { onDelete(s.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
