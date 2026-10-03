package com.example.arruler.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.arruler.plan.ExportFormat
import com.example.arruler.plan.FloorPlanCanvas
import com.example.arruler.store.Project
import com.example.arruler.store.toFloorPlan

/**
 * Full-screen 2D plan of [project]. Tap a room to select it (card with its numbers, rename and
 * delete); the share menu exports the plan. [project] null means it was deleted: goes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    project: Project?,
    units: Units,
    onBack: () -> Unit,
    onRenameRoom: (roomId: String, name: String) -> Unit,
    onDeleteRoom: (roomId: String) -> Unit,
    onExport: (ExportFormat, showAngles: Boolean) -> Unit,
) {
    if (project == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var showAngles by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var shareMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val plan = remember(project) { project.toFloorPlan() }
    val selectedIndex = project.rooms.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
    val selectedRoom = selectedIndex?.let { project.rooms[it] }

    BackHandler(enabled = selectedRoom != null) { selectedId = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(project.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                    }
                },
                actions = {
                    FilterChip(
                        selected = showAngles,
                        onClick = { showAngles = !showAngles },
                        label = { Text("Angles") },
                    )
                    Box {
                        IconButton(onClick = { shareMenu = true }) {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = "Share")
                        }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            ExportFormat.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f.label) },
                                    onClick = { shareMenu = false; onExport(f, showAngles) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            FloorPlanCanvas(
                plan = plan,
                units = units,
                modifier = Modifier.fillMaxSize(),
                showAngles = showAngles,
                selected = selectedIndex,
                onRoomTap = { i -> selectedId = i?.let { project.rooms.getOrNull(it)?.id } },
            )
            if (project.rooms.isEmpty()) {
                Text(
                    "No rooms yet. Save a room from the AR screen.",
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectedRoom != null) {
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(selectedRoom.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        roomDetails(selectedRoom, units).forEach {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { renaming = true }) { Text("Rename") }
                            TextButton(onClick = { deleting = true }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (renaming && selectedRoom != null) {
        TextInputDialog(
            "Rename room", selectedRoom.name, "Rename",
            { renaming = false; onRenameRoom(selectedRoom.id, it) },
            { renaming = false },
        )
    }
    if (deleting && selectedRoom != null) {
        ConfirmDialog(
            "Delete room?", "\"${selectedRoom.name}\" will be removed from the plan.", "Delete",
            { deleting = false; selectedId = null; onDeleteRoom(selectedRoom.id) },
            { deleting = false },
        )
    }
}
