package com.example.arruler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arruler.processing.PickerRow
import com.example.arruler.processing.PcStatus
import com.example.arruler.processing.ProcessingUi

/** Short line about the PC for the pickers: paired / reachable / not paired. */
fun pcStatusLine(paired: Boolean, status: PcStatus): String = when {
    !paired -> "No PC paired (Projects, gear icon, Settings)."
    status is PcStatus.Ok -> "PC ${status.info.name} is reachable (${status.roundTripMs} ms)."
    status is PcStatus.Checking -> "Checking the PC..."
    status is PcStatus.Failed -> "PC not reachable: ${status.message}"
    else -> "PC not tested yet."
}

/**
 * Backend / quality picker: [rows] with greyed-out options and their reasons. [confirmLabel] runs the
 * selected row; disabled rows cannot be selected.
 */
@Composable
fun QualityPickerDialog(
    title: String,
    rows: List<PickerRow>,
    initialId: String?,
    pcLine: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initialId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pcLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (r in rows) {
                    val alpha = if (r.enabled) 1f else 0.45f
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = selected == r.id, enabled = r.enabled, role = Role.RadioButton) { selected = r.id }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == r.id, onClick = null, enabled = r.enabled)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(r.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                            Text(
                                r.subtitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                            )
                            r.reason?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Progress card of a running job: stage text, bar, optional warning and Cancel. */
@Composable
fun ProcessingCard(ui: ProcessingUi, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF2C2C2E).copy(alpha = 0.92f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(ui.text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        val f = ui.fraction
        if (f == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator({ f }, Modifier.fillMaxWidth())
        ui.warning?.let { Text(it, color = Color(0xFFFFD60A), fontSize = 12.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel", color = Color(0xFFFF453A)) }
        }
    }
}

/** One button of a [ResultCard]; [menu] makes it a drop-down (e.g. Share: OBJ, PLY). */
class CardAction(
    val label: String,
    val tint: Color = Color.White,
    val onClick: () -> Unit = {},
    val menu: List<Pair<String, () -> Unit>> = emptyList(),
)

/** Result card pinned to the top of the AR view (the bottom is taken by the mode controls). */
@Composable
fun BoxScope.ResultCard(title: String, lines: List<Pair<String, String>>, actions: List<CardAction>) {
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 64.dp, start = 12.dp, end = 12.dp)
            .heightIn(max = 420.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF2C2C2E).copy(alpha = 0.92f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        for ((name, value) in lines) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            for (a in actions) {
                var open by remember(a.label) { mutableStateOf(false) }
                Box {
                    GlassPill(a.label, a.tint) { if (a.menu.isEmpty()) a.onClick() else open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for ((label, run) in a.menu) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; run() })
                    }
                }
            }
        }
    }
}
