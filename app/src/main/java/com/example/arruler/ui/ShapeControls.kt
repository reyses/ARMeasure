package com.example.arruler.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import com.example.arruler.measure.Units
import com.example.arruler.measure.shapes.ShapeCapture
import com.example.arruler.measure.shapes.ShapeFormat
import com.example.arruler.measure.shapes.ShapeKind

/**
 * SHAPES mode overlay: result card, prompt, Undo / Done pills and a scrollable shape picker.
 * Bottom-centre, above the controls bar (same slot as AreaControls). Picking a kind calls
 * [onPickKind]; the host replaces its ShapeCapture with `ShapeCapture(kind)`.
 */
@Composable
fun BoxScope.ShapeControls(
    capture: ShapeCapture,
    units: Units,
    onPickKind: (ShapeKind) -> Unit,
    onUndo: () -> Unit,
    onCloseBase: () -> Unit,
    onReset: () -> Unit,
) {
    val view = LocalView.current
    val haptic = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); Unit }
    val result = capture.result

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 136.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (result != null) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF2C2C2E).copy(alpha = 0.88f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    capture.kind.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
                for ((name, value) in ShapeFormat.lines(units, result)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(name, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
        Text(
            capture.prompt,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (capture.canCloseBase) ShapePill("Done", Color(0xFF34C759)) { haptic(); onCloseBase() }
            if (capture.taps.isNotEmpty()) {
                ShapePill("Undo", Color.White) { haptic(); onUndo() }
                ShapePill("Reset", Color.White) { haptic(); onReset() }
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (k in ShapeKind.entries) {
                ShapePill(k.label, Color.White, selected = k == capture.kind) { haptic(); onPickKind(k) }
            }
        }
    }
}

@Composable
private fun ShapePill(label: String, tint: Color, selected: Boolean = false, onClick: () -> Unit) {
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
