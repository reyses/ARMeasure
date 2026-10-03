package com.example.arruler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.arruler.store.ExportNames
import com.example.arruler.store.Project

/**
 * Save sheet after Finish: a name prefilled with 'Object N' (N follows the objects already in the chosen project),
 * the project chips, and Save. [summary] is the one-line result ('25.0 x 20.0 x 10.0 cm, 0.0050 m³').
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectSaveSheet(
    summary: String,
    projects: List<Project>,
    lastUsedId: String?,
    existingNames: (ProjectChoice) -> List<String>,
    onSave: (name: String, choice: ProjectChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember {
        mutableStateOf<ProjectChoice>(
            projects.firstOrNull { it.id == lastUsedId }?.let { ProjectChoice.Existing(it.id) }
                ?: ProjectChoice.New(ExportNames.DEFAULT_PROJECT)
        )
    }
    var edited by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val effective = if (edited) name else NameDefaults.next("Object", existingNames(choice))
    val canSave = effective.isNotBlank() && (choice as? ProjectChoice.New)?.name?.isNotBlank() != false

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Save object", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = effective,
                onValueChange = { name = it; edited = true },
                label = { Text("Object name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ProjectChips(projects, choice) { choice = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = { onSave(effective.trim(), choice) }, enabled = canSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
            }
        }
    }
}
