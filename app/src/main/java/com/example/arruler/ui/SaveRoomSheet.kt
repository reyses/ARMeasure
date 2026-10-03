package com.example.arruler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.arruler.measure.Units
import com.example.arruler.store.CapturedOutline
import com.example.arruler.store.Project

/** A closed outline (and optional height) waiting to be saved as a room. */
class SaveRoomDraft(val captured: CapturedOutline, val heightM: Float?)

/** Where a saved room goes. */
sealed interface ProjectChoice {
    data class Existing(val id: String) : ProjectChoice
    data class New(val name: String) : ProjectChoice
}

/**
 * Bottom sheet asking for the room name, the target project and whether to snap near-right
 * corners to 90 degrees (area shown before and after).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveRoomSheet(
    draft: SaveRoomDraft,
    projects: List<Project>,
    lastUsedId: String?,
    units: Units,
    onSave: (name: String, choice: ProjectChoice, snap: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember {
        mutableStateOf<ProjectChoice>(
            projects.firstOrNull { it.id == lastUsedId }?.let { ProjectChoice.Existing(it.id) }
                ?: ProjectChoice.New("My place")
        )
    }
    var nameEdited by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var snap by remember { mutableStateOf(true) }

    val roomCount = (choice as? ProjectChoice.Existing)?.let { c -> projects.firstOrNull { it.id == c.id }?.rooms?.size } ?: 0
    val effectiveName = if (nameEdited) name else "Room ${roomCount + 1}"
    val before = draft.captured.areaM2(false)
    val after = draft.captured.areaM2(true)
    val canSave = effectiveName.isNotBlank() && (choice as? ProjectChoice.New)?.name?.isNotBlank() != false

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Save room", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = effectiveName,
                onValueChange = { name = it; nameEdited = true },
                label = { Text("Room name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Project", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                projects.forEach { p ->
                    FilterChip(
                        selected = (choice as? ProjectChoice.Existing)?.id == p.id,
                        onClick = { choice = ProjectChoice.Existing(p.id) },
                        label = { Text(p.name) },
                    )
                }
                FilterChip(
                    selected = choice is ProjectChoice.New,
                    onClick = { if (choice !is ProjectChoice.New) choice = ProjectChoice.New("My place") },
                    label = { Text("+ New project") },
                )
            }
            (choice as? ProjectChoice.New)?.let { c ->
                OutlinedTextField(
                    value = c.name,
                    onValueChange = { choice = ProjectChoice.New(it) },
                    label = { Text("New project name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Snap 90°", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Area " + areaLabel(before, units) + "  →  " + areaLabel(after, units),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = snap, onCheckedChange = { snap = it })
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(effectiveName.trim(), choice, snap) }, enabled = canSave) { Text("Save") }
            }
        }
    }
}
