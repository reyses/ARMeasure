package com.example.arruler.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import com.example.arruler.depth.ObjectPhase
import com.example.arruler.depth.ObjectUiState
import com.example.arruler.measure.Units
import com.example.arruler.objscan.BoxDim
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.ObjectPlacement
import com.example.arruler.processing.ProcessingUi
import java.util.Locale

const val OBJECT_IDLE_HINT = "Tap the floor or table next to the object (or press the shutter at the crosshair)"

/** '25.0 x 25.0 x 25.0 cm' for the box read-out. */
fun boxText(units: Units, b: ObjectBox): String {
    fun n(m: Float) = String.format(Locale.US, "%.1f", units.fromMeters(m))
    return "${n(b.w)} x ${n(b.d)} x ${n(b.h)} ${units.symbol}"
}

/** The callbacks of the OBJECT mode overlay. */
class ObjectActions(
    val onResize: (BoxDim, Float) -> Unit,
    val onRotate: () -> Unit,
    val onFit: () -> Unit,
    val onStart: () -> Unit,
    val onPause: () -> Unit,
    val onFinish: () -> Unit,
    val onReset: () -> Unit,
    val onCancelJob: () -> Unit,
)

/**
 * OBJECT mode overlay (bottom-centre, same slot as the SCAN controls): placement pills (W / D / H +/- in
 * 1 cm steps, long-press 5 cm, rotate 15 degrees, Fit), then the capture HUD (coverage, hint, counters),
 * then the progress card while the result is computed. The result card is [ResultCard] at the top.
 */
@Composable
fun BoxScope.ObjectControls(state: ObjectUiState, units: Units, processing: ProcessingUi?, actions: ObjectActions) {
    val view = LocalView.current
    val haptic = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); Unit }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 192.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.phase) {
            ObjectPhase.IDLE -> Info(OBJECT_IDLE_HINT)

            ObjectPhase.PLACED -> {
                state.box?.let { Info(boxText(units, it), bold = true) }
                Info(
                    if (state.fitting) "Fitting..." else
                        "Tap the surface again to move the box. Fit snaps it to the depth points near your tap (${state.fitVoxels} so far).",
                    small = true,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (dim in BoxDim.entries) {
                        StepPill("${dim.label} -", { haptic(); actions.onResize(dim, -ObjectPlacement.STEP) }, { haptic(); actions.onResize(dim, -ObjectPlacement.LONG_STEP) })
                        StepPill("${dim.label} +", { haptic(); actions.onResize(dim, ObjectPlacement.STEP) }, { haptic(); actions.onResize(dim, ObjectPlacement.LONG_STEP) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill("Rotate 15°", Color.White) { haptic(); actions.onRotate() }
                    GlassPill(if (state.fitting) "Fitting" else "Fit", Color.White) { haptic(); if (!state.fitting) actions.onFit() }
                    GlassPill("Start", Color(0xFF34C759)) { haptic(); actions.onStart() }
                    GlassPill("Reset", Color.White) { haptic(); actions.onReset() }
                }
            }

            ObjectPhase.CAPTURING, ObjectPhase.PAUSED -> {
                Info("Coverage ${(state.coverage * 100).toInt()} %  |  ${state.voxels} voxels", bold = true)
                Info(if (state.phase == ObjectPhase.PAUSED) "Paused" else state.hint)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.phase == ObjectPhase.PAUSED) GlassPill("Resume", Color(0xFF34C759)) { haptic(); actions.onStart() }
                    else GlassPill("Pause", Color.White) { haptic(); actions.onPause() }
                    GlassPill("Finish", Color(0xFF34C759)) { haptic(); actions.onFinish() }
                    GlassPill("Reset", Color.White) { haptic(); actions.onReset() }
                }
            }

            ObjectPhase.WORKING -> {
                if (processing != null) ProcessingCard(processing, actions.onCancelJob) else Info("Working...")
            }

            ObjectPhase.RESULT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill("New object", Color.White) { haptic(); actions.onReset() }
            }
        }
    }
}

@Composable
private fun Info(text: String, bold: Boolean = false, small: Boolean = false) {
    Text(
        text,
        color = Color.White,
        fontSize = if (small) 12.sp else 15.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** A small +/- pill: tap = 1 cm, long-press = 5 cm. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepPill(label: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

