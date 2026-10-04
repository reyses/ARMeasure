package com.example.arruler.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arruler.R
import com.example.arruler.diag.DeviceReportCollector
import com.example.arruler.diag.DeviceReportData
import com.example.arruler.diag.GpuSelfTest
import com.example.arruler.diag.GpuSelfTestReport
import com.example.arruler.diag.LensGeometry
import com.example.arruler.diag.LensRole
import com.example.arruler.diag.ReportFormat
import com.example.arruler.gpu.GpuGate
import com.example.arruler.store.ContentSource
import com.example.arruler.store.ExportNames
import com.example.arruler.store.PublicStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of the diagnostics screen: the self-test, the device survey, and the plain-text report built from both. */
class DiagnosticsModel(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var running by mutableStateOf(false); private set
    var progressText by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var selfTest by mutableStateOf<GpuSelfTestReport?>(null); private set
    var data by mutableStateOf<DeviceReportData?>(null); private set
    var collecting by mutableStateOf(false); private set

    fun runSelfTest() {
        if (running) return
        running = true; progress = 0f; progressText = "Starting"
        scope.launch {
            val report = try {
                GpuSelfTest.run(app) { text, p -> progressText = text; progress = p }
            } catch (e: Throwable) {
                null
            }
            if (report != null) {
                try { GpuGate.record(report.toVerification(GpuGate.keyFor(report.renderer, report.glVersion))) } catch (e: Throwable) { /* keep the screen alive */ }
            }
            withContext(Dispatchers.Main) { selfTest = report; running = false; progressText = "" }
        }
    }

    fun collect() {
        if (collecting) return
        collecting = true
        scope.launch {
            val d = try { DeviceReportCollector.collectAll(app) } catch (e: Throwable) { null }
            withContext(Dispatchers.Main) { data = d; collecting = false }
        }
    }

    fun gpuLines(): List<String> {
        val st = selfTest
        return listOf(GpuGate.statusLine()) + (st?.lines() ?: emptyList())
    }

    fun reportText(): String =
        ReportFormat.format(data ?: DeviceReportData(null, null, null, null), gpuLines(), System.currentTimeMillis())

    fun close() { scope.cancel() }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ARMeasure diagnostics", text))
    Toast.makeText(context, "Report copied (${text.length / 1024 + 1} KB)", Toast.LENGTH_SHORT).show()
}

private fun shareReport(context: Context, text: String, scope: CoroutineScope) {
    scope.launch {
        val saved = withContext(Dispatchers.IO) {
            try {
                if (PublicStorage.needsLegacyPermission(context)) null
                else PublicStorage.sink(context).write(ExportNames.relativePath("diagnostics"), ReportFormat.fileName(Build.MODEL, System.currentTimeMillis()), "text/plain", ContentSource.of(text))
            } catch (e: Exception) { null }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ARMeasure diagnostics ${Build.MODEL}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try { context.startActivity(Intent.createChooser(send, "Share report")) } catch (e: Exception) { copyToClipboard(context, text) }
        Toast.makeText(context, if (saved != null) "Also saved to Download/ARMeasure/diagnostics" else "Could not save a copy to Downloads", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Settings -> Diagnostics. The AR view is held paused while this screen is open (MainActivity), so the short-lived ARCore session
 * used for the survey never competes for the camera. Sections: GPU self-test, Cameras, AR, Device, then Share / Copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val model = remember { DiagnosticsModel(context.applicationContext) }
    val uiScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    DisposableEffect(Unit) { onDispose { model.close(); uiScope.cancel() } }
    // give the paused AR view a moment to release the camera before the survey opens its own session
    LaunchedEffect(Unit) { delay(700); model.collect() }
    val gate by GpuGate.verification.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DiagSection("GPU self-test") {
                Text(GpuGate.statusLine(rec = gate), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Runs every GPU kernel against its CPU version on this phone (about 10-30 s). A kernel is used only if it matches the CPU result and is faster.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = model::runSelfTest, enabled = !model.running) { Text(if (model.running) "Running..." else "Run GPU self-test") }
                if (model.running) {
                    LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                    Text(model.progressText, style = MaterialTheme.typography.bodySmall)
                }
                val st = model.selfTest
                if (st != null) {
                    Text("${st.passed.size}/${st.results.size} kernels PASS, ${"%.1f".format(st.totalMs / 1000.0)} s on ${st.renderer}", style = MaterialTheme.typography.bodyMedium)
                    for (r in st.results) {
                        Text(
                            r.line(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = if (r.pass) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                        )
                    }
                    st.bench?.let { Text("Bench: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }

            val d = model.data
            DiagSection("Cameras") {
                if (d?.cameras == null) Text(if (model.collecting) "Reading cameras..." else "No data yet", style = MaterialTheme.typography.bodyMedium)
                else {
                    val c = d.cameras
                    Text(ReportFormat.depthSensorLine(c.cameras), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(ReportFormat.concurrentLine(c), style = MaterialTheme.typography.bodyMedium)
                    Text(ReportFormat.baselineLine(c.cameras, LensRole.MAIN, LensRole.ULTRAWIDE, "main↔ultrawide"), style = MaterialTheme.typography.bodyMedium)
                    c.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val roles = LensGeometry.roles(c.cameras)
                    for (cam in c.cameras) Text(ReportFormat.cameraLine(cam, roles).trim(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }

            DiagSection("AR") {
                val a = d?.ar
                if (a == null) Text(if (model.collecting) "Reading ARCore..." else "No data yet", style = MaterialTheme.typography.bodyMedium)
                else {
                    Text("ARCore ${a.availability}, apk ${a.apkVersion ?: "n/a"}", style = MaterialTheme.typography.bodyMedium)
                    a.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (a.depthModes.isNotEmpty()) Text("Depth modes: " + a.depthModes.joinToString(", ") { "${it.first}=${if (it.second) "yes" else "no"}" }, style = MaterialTheme.typography.bodyMedium)
                    a.chosen?.let { Text("Chosen: ${it.text()}", style = MaterialTheme.typography.bodyMedium) }
                    for (r in a.configs) Text(r.text(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }

            DiagSection("Device") {
                val b = d?.device
                if (b == null) Text(if (model.collecting) "Reading device..." else "No data yet", style = MaterialTheme.typography.bodyMedium)
                else {
                    Text("${b.manufacturer} ${b.model} (${b.device}), Android ${b.release} (API ${b.sdk})", style = MaterialTheme.typography.bodyMedium)
                    Text("SoC ${listOf(b.socManufacturer, b.socModel).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "n/a" }}; tier ${b.tier}; thermal ${b.thermal}; ${b.cores} cores; ${"%.1f".format(b.ramGb)} GB RAM", style = MaterialTheme.typography.bodyMedium)
                }
                d?.gl?.let { Text("GL ${it.renderer} | ${it.version}\nVulkan: ${it.vulkan}", style = MaterialTheme.typography.bodySmall) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shareReport(context, model.reportText(), uiScope) }, enabled = !model.running) { Text("Share report") }
                OutlinedButton(onClick = { copyToClipboard(context, model.reportText()) }, enabled = !model.running) { Text("Copy to clipboard") }
            }
            OutlinedButton(onClick = model::collect, enabled = !model.collecting) { Text(if (model.collecting) "Reading..." else "Refresh device info") }
        }
    }
}

@Composable
private fun DiagSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
