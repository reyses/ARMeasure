package com.example.arruler.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.arruler.store.ExportResult
import kotlinx.coroutines.delay

/** What the snackbar after a save shows: its [message] and the export it can open or share. */
class SaveNote(val message: String, val result: ExportResult)

/** 'Saved to Download/ARMeasure/<project>' with 'Open folder' and 'Share'; goes away by itself after 8 s. */
@Composable
fun BoxScope.SaveSnackbar(note: SaveNote?, onOpenFolder: (SaveNote) -> Unit, onShare: (SaveNote) -> Unit, onDismiss: () -> Unit) {
    if (note == null) return
    LaunchedEffect(note) {
        delay(8000)
        onDismiss()
    }
    Snackbar(
        actionOnNewLine = true,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
        action = {
            Row {
                TextButton(onClick = { onOpenFolder(note) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open folder") }
                TextButton(onClick = { onShare(note) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share") }
            }
        },
    ) { Text(note.message) }
}
