package com.example.arruler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.arruler.R
import com.example.arruler.measure.Units
import com.example.arruler.scan3d.ObjectShareFormat
import com.example.arruler.scan3d.ObjectSummary
import com.example.arruler.scan3d.Scan3DViewer
import com.example.arruler.scan3d.ScanSnapshot

/**
 * One saved object: the 3D view on top (orbit, pinch), then all the numbers, an editable name and notes, and the
 * actions Share (OBJ / PLY / JSON / TXT), Open in Downloads, Play capture video, Delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectDetailScreen(
    snapshot: ScanSnapshot,
    projectName: String?,
    units: Units,
    hasVideo: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onSaveNotes: (String) -> Unit,
    onShare: (ObjectShareFormat) -> Unit,
    onOpenDownloads: () -> Unit,
    onPlayVideo: () -> Unit,
    onFullScreen: () -> Unit,
    onDelete: () -> Unit,
) {
    val name = snapshot.name ?: "Object"
    val s: ObjectSummary? = snapshot.objectSummary
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var notes by remember(snapshot.id) { mutableStateOf(snapshot.notes.orEmpty()) }
    val notesChanged = notes != snapshot.notes.orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
                },
                actions = { TextButton(onClick = { renaming = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Rename") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                Scan3DViewer(snapshot, onBack = onBack, modifier = Modifier.fillMaxWidth().height(300.dp), compact = true)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(onClick = onFullScreen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open the 3D view full screen") }
                if (s != null) {
                    Fact("Footprint", ObjectFormat.footprint(units, s))
                    Fact("Height", ObjectFormat.height(units, s))
                    Fact("Volume (recommended)", ObjectFormat.volume(units, s.volumeM3))
                    ObjectFormat.volumeRange(units, s)?.let { Fact("Volume range", it) }
                }
                snapshot.method?.let { Fact("Method", it) }
                for (line in snapshot.extras) {
                    val i = line.indexOf(": ")
                    if (i > 0) Fact(line.substring(0, i), line.substring(i + 2)) else Fact("Note", line)
                }
                Fact("Project", projectName ?: "none")
                Fact("Saved", ObjectFormat.date(snapshot.createdAt))

                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                )
                if (notesChanged) {
                    Button(onClick = { onSaveNotes(notes) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save notes") }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(onClick = { shareMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share") }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            for (f in ObjectShareFormat.entries) {
                                DropdownMenuItem(text = { Text(f.label) }, onClick = { shareMenu = false; onShare(f) })
                            }
                        }
                    }
                    OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.ic_folder), null, modifier = Modifier.padding(end = 6.dp))
                        Text("Open in Downloads")
                    }
                }
                if (hasVideo) {
                    OutlinedButton(onClick = onPlayVideo, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.ic_play), null, modifier = Modifier.padding(end = 6.dp))
                        Text("Play capture video")
                    }
                }
                TextButton(onClick = { deleting = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (renaming) TextInputDialog("Rename object", name, "Rename", { renaming = false; onRename(it) }, { renaming = false })
    if (deleting) {
        ConfirmDialog("Delete object?", "\"$name\" will be removed from the project. Copies in Downloads stay.", "Delete",
            { deleting = false; onDelete() }, { deleting = false })
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.55f))
    }
}
