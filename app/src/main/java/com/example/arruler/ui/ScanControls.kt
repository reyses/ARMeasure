package com.example.arruler.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arruler.depth.ScanAnalysis
import com.example.arruler.depth.ScanStats
import com.example.arruler.measure.Units
import com.example.arruler.measure.shapes.ShapeFormat

const val SCAN_HINT = "Move slowly, sweep the walls, floor and ceiling"

/** Result-card rows (name to value) of a scanned room, in [units]. */
fun scanRoomLines(units: Units, r: ScanAnalysis.Room): List<Pair<String, String>> = listOf(
    "Area" to ShapeFormat.area(units, r.areaM2),
    "Perimeter" to ShapeFormat.length(units, r.perimeterM),
    "Height" to ShapeFormat.length(units, r.heightM),
    "Volume" to ShapeFormat.volume(units, r.volumeM3),
    "Walls" to r.model.wallCount.toString(),
)

/** SCAN mode overlay: counter, hint, Start/Pause/Reset/Analyze pills and the result card. */
@Composable
fun BoxScope.ScanControls(
    scanning: Boolean,
    analyzing: Boolean,
    stats: ScanStats,
    analysis: ScanAnalysis?,
    savable: Boolean,
    units: Units,
    onStartPause: () -> Unit,
    onReset: () -> Unit,
    onAnalyze: () -> Unit,
    onView3D: () -> Unit,
    onSave: () -> Unit,
) {
    val view = LocalView.current
    val haptic = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); Unit }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 192.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (analysis) {
            is ScanAnalysis.Room -> Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF2C2C2E).copy(alpha = 0.88f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text("Scanned room", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                for ((name, value) in scanRoomLines(units, analysis)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(name, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            is ScanAnalysis.Incomplete -> Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF2C2C2E).copy(alpha = 0.88f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text("Room not complete yet", color = Color(0xFFFF9500), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                for (m in analysis.missing) Text("Missing: $m", color = Color.White, fontSize = 14.sp)
            }
            null -> {}
        }
        Text(
            "${stats.voxels} voxels, ${stats.points} points",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
        Text(
            if (analyzing) "Analyzing..." else SCAN_HINT,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(if (scanning) "Pause" else "Start", Color(0xFF34C759)) { haptic(); onStartPause() }
            GlassPill("Reset", Color.White) { haptic(); onReset() }
            if (!analyzing && stats.voxels > 0) GlassPill("Analyze", Color.White) { haptic(); onAnalyze() }
            if (!analyzing && stats.voxels > 0) GlassPill("View 3D", Color.White) { haptic(); onView3D() }
            if (savable) GlassPill("Save room", Color(0xFF34C759)) { haptic(); onSave() }
        }
    }
}
