package com.example.arruler.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.arruler.store.ExportNames
import com.example.arruler.store.Project

/** The project choice of a save sheet: one chip per project, '+ New project' and its name field. */
@Composable
fun ProjectChips(projects: List<Project>, choice: ProjectChoice, onChoice: (ProjectChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Project", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            projects.forEach { p ->
                FilterChip(
                    selected = (choice as? ProjectChoice.Existing)?.id == p.id,
                    onClick = { onChoice(ProjectChoice.Existing(p.id)) },
                    label = { Text(p.name) },
                )
            }
            FilterChip(
                selected = choice is ProjectChoice.New,
                onClick = { if (choice !is ProjectChoice.New) onChoice(ProjectChoice.New(ExportNames.DEFAULT_PROJECT)) },
                label = { Text("+ New project") },
            )
        }
        (choice as? ProjectChoice.New)?.let { c ->
            OutlinedTextField(
                value = c.name,
                onValueChange = { onChoice(ProjectChoice.New(it)) },
                label = { Text("New project name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
