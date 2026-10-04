package com.example.arruler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.arruler.R
import com.example.arruler.processing.DeviceProfile
import com.example.arruler.processing.PairingInfo
import com.example.arruler.processing.PcStatus
import com.example.arruler.processing.UserPref
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** One-line summary of the device and its tier. */
fun deviceLine(p: DeviceProfile): String {
    val s = p.signals
    val ram = String.format(Locale.US, "%.1f GB RAM", s.totalMemBytes / (1024.0 * 1024 * 1024))
    val bench = s.benchMs?.let { "benchmark $it ms" } ?: "benchmark not run yet"
    return listOf("${p.tier.name} tier", ram, "${s.cores} cores", s.socModel.ifEmpty { null }, bench).filterNotNull().joinToString(", ")
}

/** Last ping result as one line. */
fun pingLine(status: PcStatus): String = when (status) {
    PcStatus.Unknown -> "Not tested yet"
    PcStatus.Checking -> "Testing..."
    is PcStatus.Ok -> {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.atMs))
        val gpu = status.info.gpu.ifEmpty { null }
        listOfNotNull("OK", "server ${status.info.version}", gpu, "${status.roundTripMs} ms", "at $time").joinToString(", ")
    }
    is PcStatus.Failed -> status.message
}

/**
 * Settings: Processing = Auto / Phone / PC, the device tier with a speed test, and the PC link (pair by QR or by
 * pasting the JSON, name + URL, last ping, test, unpair).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    pref: UserPref,
    onPref: (UserPref) -> Unit,
    profile: DeviceProfile,
    speedTesting: Boolean,
    onSpeedTest: () -> Unit,
    pairing: PairingInfo?,
    status: PcStatus,
    onScanQr: () -> Unit,
    /** Returns an error message, or null when pairing worked. */
    onPairText: (String) -> String?,
    onUnpair: () -> Unit,
    onTest: () -> Unit,
    copyToDownloads: Boolean,
    onCopyToDownloads: (Boolean) -> Unit,
    recordVideo: Boolean,
    onRecordVideo: (Boolean) -> Unit,
    useGpu: Boolean,
    onUseGpu: (Boolean) -> Unit,
    /** One line: GPU: verified n/6 kernels on <renderer>, or not verified. */
    gpuStatus: String,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Saving") {
                SwitchRow(
                    "Copy saves to Downloads/ARMeasure",
                    "Every save also writes plans, meshes and measurements to a folder you can open from any app.",
                    copyToDownloads, onCopyToDownloads,
                )
                SwitchRow(
                    "Record capture video",
                    "Records each object scan as a video. It takes about 30-60 MB per minute.",
                    recordVideo, onRecordVideo,
                )
            }

            Section("Processing") {
                Text(
                    "Where heavy work (object meshes, big room scans) runs. Automatic uses your PC for big jobs when it is reachable.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in UserPref.entries) {
                        FilterChip(
                            selected = pref == p, onClick = { onPref(p) },
                            label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }

            Section("This phone") {
                Text(deviceLine(profile), style = MaterialTheme.typography.bodyMedium)
                SwitchRow("Use GPU when verified", "Only kernels that matched the CPU result on this phone, driver and app version are used, and only for jobs where they are faster.", useGpu, onUseGpu)
                Text(gpuStatus, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onDiagnostics) { Text("Diagnostics") }
                OutlinedButton(onClick = onSpeedTest, enabled = !speedTesting) {
                    Text(if (speedTesting) "Measuring (about 2 s)..." else "Run speed test")
                }
            }

            if (com.example.arruler.devlink.DevEntries.entry.enabled) {
                Section("Dev (debug build)") { com.example.arruler.devlink.DevEntries.entry.SettingsSection(pairing) }
            }

            Section("PC") {
                if (pairing == null) {
                    Text("No PC paired. Start the PC server, then scan the QR code it shows.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onScanQr) { Text("Pair: scan QR") }
                    PasteField(onPairText)
                } else {
                    Text(pairing.name.ifEmpty { "Paired PC" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(pairing.url, style = MaterialTheme.typography.bodyMedium)
                    Text(pingLine(status), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onTest, enabled = status != PcStatus.Checking) { Text("Test connection") }
                        OutlinedButton(onClick = onUnpair) { Text("Unpair") }
                    }
                    OutlinedButton(onClick = onScanQr) { Text("Pair another PC") }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun PasteField(onPairText: (String) -> String?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(
        value = text, onValueChange = { text = it; error = null },
        label = { Text("or paste the pairing JSON") },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
        isError = error != null, supportingText = error?.let { { Text(it) } },
    )
    OutlinedButton(onClick = { error = onPairText(text); if (error == null) text = "" }, enabled = text.isNotBlank()) { Text("Pair") }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
