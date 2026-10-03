package com.example.arruler.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.example.arruler.measure.MeasureMode
import com.example.arruler.measure.MeasureState

/** Hint under the crosshair in AREA mode. */
fun areaHint(state: MeasureState, hasSurface: Boolean): String = when {
    state.heightActive -> "Aim at the ceiling, tap the shutter"
    state.closed -> if (state.heightPoint == null) "Tap Height for volume" else "Tap to Measure Again"
    state.points.isEmpty() -> if (hasSurface) "Tap to add corner" else "Find a surface"
    state.points.size < 3 -> "Tap to add corner"
    else -> "Tap to add corner, Close when done"
}

private fun fmt2(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

/** Area read-out text (value + unit). */
fun areaText(state: MeasureState): String =
    state.areaMeasurement?.let { fmt2(state.unit.areaFromSquareMeters(it.area())) + " " + state.unit.areaSymbol } ?: ""

fun perimeterText(state: MeasureState): String =
    state.areaMeasurement?.let {
        fmt2(state.unit.fromMeters(it.perimeter())) + " " + state.unit.symbol + " perimeter"
    } ?: ""

fun volumeText(state: MeasureState): String =
    state.volumeMeasurement?.let {
        fmt2(state.unit.volumeFromCubicMeters(it.volume())) + " " + state.unit.volumeSymbol
    } ?: ""

/** Top read-out for AREA mode: area (big), perimeter and volume (smaller) beneath. */
@Composable
fun BoxScope.AreaReadout(state: MeasureState) {
    if (state.mode != MeasureMode.AREA || state.areaMeasurement == null) return
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 16.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF2C2C2E).copy(alpha = 0.85f))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(areaText(state), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 32.sp)
        Text(perimeterText(state), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
        if (state.volumeMeasurement != null) {
            Text(volumeText(state), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

/**
 * Mode switch (DISTANCE | AREA) and, in AREA mode, the Close / Undo / Height pills. Sits above the
 * controls bar. Fires haptics, then the callbacks.
 */
@Composable
fun BoxScope.AreaControls(
    state: MeasureState,
    onSetMode: (MeasureMode) -> Unit,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onHeight: () -> Unit,
) {
    val view = LocalView.current
    val haptic = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); Unit }
    val area = state.mode == MeasureMode.AREA

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 136.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (area) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.closed) {
                    if (!state.heightActive && state.heightPoint == null) {
                        GlassPill("Height", Color.White) { haptic(); onHeight() }
                    }
                } else if (state.points.size >= 3) {
                    GlassPill("Close", Color(0xFF34C759)) { haptic(); onClose() }
                }
                if (state.points.isNotEmpty()) {
                    GlassPill("Undo", Color.White) { haptic(); onUndo() }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill("DISTANCE", Color.White, selected = !area) { haptic(); onSetMode(MeasureMode.DISTANCE) }
            GlassPill("AREA", Color.White, selected = area) { haptic(); onSetMode(MeasureMode.AREA) }
        }
    }
}

@Composable
private fun GlassPill(label: String, tint: Color, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) Color(0xFF007AFF).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
