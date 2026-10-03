package com.example.arruler.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arruler.R
import com.example.arruler.depth.ObjectPhase
import com.example.arruler.depth.ObjectUiState
import com.example.arruler.measure.Units
import com.example.arruler.objscan.BoxDim
import com.example.arruler.objscan.CaptureMode
import com.example.arruler.objscan.CaptureModes
import com.example.arruler.objscan.CaptureOption
import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.ObjectPlacement
import com.example.arruler.objscan.SpinProgress
import com.example.arruler.objscan.SpinText
import com.example.arruler.processing.ProcessingUi
import java.util.Locale

/** The one-line instruction of the first step. */
const val OBJECT_IDLE_HINT = "Tap the object"

/** '25.0 x 25.0 x 25.0 cm' for the box read-out. */
fun boxText(units: Units, b: ObjectBox): String {
    fun n(m: Float) = String.format(Locale.US, "%.1f", units.fromMeters(m))
    return "${n(b.w)} x ${n(b.d)} x ${n(b.h)} ${units.symbol}"
}

/** The callbacks of the OBJECT mode overlay. */
class ObjectActions(
    val onResize: (BoxDim, Float) -> Unit,
    val onRotate: () -> Unit,
    val onScale: (Float) -> Unit,
    val onFit: () -> Unit,
    /** The mode card tapped after 'Looks right'. */
    val onChooseMode: (CaptureMode) -> Unit,
    /** HYBRID: the walk is done, start the spin. */
    val onBeginHybridSpin: () -> Unit,
    /** Spin stage: end the running turn, or start the next one while paused between turns. */
    val onSpinNextTurn: () -> Unit,
    /** Resume from PAUSED. */
    val onResume: () -> Unit,
    val onPause: () -> Unit,
    val onFinish: () -> Unit,
    val onReset: () -> Unit,
    val onCancelJob: () -> Unit,
)

/** What the capture HUD needs besides the controller state: the PC, and the spin hook's flows. */
class ObjectHudInfo(
    val pcPaired: Boolean,
    val spinAvailable: Boolean,
    val phoneMoved: Boolean,
    val spinProgress: SpinProgress?,
    /** Paused between two spin turns: the 'tilt the phone down' prompt shows. */
    val betweenTurns: Boolean = false,
    /** The running spin turn may be ended early. */
    val canEndTurn: Boolean = false,
    /** The ML Kit label of the object under the last tap ('Home good · 82 %'), shown briefly. */
    val mlLabel: String? = null,
)

/**
 * OBJECT mode overlay (bottom-centre, same slot as the SCAN controls). One instruction per step:
 * 1. 'Tap the object' (the box appears, aligned to it; drag it to move it),
 * 2. 'Looks right' (primary) with an 'Adjust' expander (+/- per side, rotate, Bigger / Smaller, Refit),
 * 3. three capture cards (Walk around / Spin / Hybrid) and a 'What's best?' sheet,
 * 4. the capture HUD (walk coverage, or the spin progress with the phone-moved banner),
 * 5. the progress card while the result is computed. The result card is [ResultCard] at the top.
 */
@Composable
fun BoxScope.ObjectControls(
    state: ObjectUiState,
    units: Units,
    processing: ProcessingUi?,
    hud: ObjectHudInfo,
    actions: ObjectActions,
) {
    val view = LocalView.current
    val haptic = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); Unit }
    var adjustOpen by remember(state.phase) { mutableStateOf(false) }
    var choosing by remember(state.phase) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 192.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.phase) {
            ObjectPhase.IDLE -> {
                Info(OBJECT_IDLE_HINT, bold = true, icon = R.drawable.ic_touch)
                Info("Tap the object you want to measure. The box fits it by itself.", small = true)
            }

            ObjectPhase.PLACED -> if (choosing) {
                Info("How do you want to scan it?", bold = true)
                for (o in CaptureModes.options(hud.pcPaired, hud.spinAvailable)) {
                    CaptureCard(o) { haptic(); actions.onChooseMode(o.mode) }
                }
                var help by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill("What's best?", R.drawable.ic_info) { haptic(); help = true }
                    ActionPill("Back", null) { haptic(); choosing = false }
                }
                if (help) CaptureHelpSheet { help = false }
            } else {
                hud.mlLabel?.let { Info(it, icon = R.drawable.ic_info) }
                state.box?.let { Info(boxText(units, it), bold = true) }
                Info(
                    if (state.fitting) "Looking for the object..." else "Check the box. Drag on the screen to move it.",
                    small = true,
                )
                BigButton("Looks right", R.drawable.ic_check, Color(0xFF34C759), enabled = !state.fitting) {
                    haptic(); choosing = true
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(if (adjustOpen) "Hide adjust" else "Adjust", R.drawable.ic_tune) { haptic(); adjustOpen = !adjustOpen }
                    ActionPill("Reset", null) { haptic(); actions.onReset() }
                }
                if (adjustOpen) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (dim in BoxDim.entries) {
                            StepPill("${dim.label} -", R.drawable.ic_minus, { haptic(); actions.onResize(dim, -ObjectPlacement.STEP) }, { haptic(); actions.onResize(dim, -ObjectPlacement.LONG_STEP) })
                            StepPill("${dim.label} +", R.drawable.ic_plus, { haptic(); actions.onResize(dim, ObjectPlacement.STEP) }, { haptic(); actions.onResize(dim, ObjectPlacement.LONG_STEP) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill("Bigger", R.drawable.ic_plus) { haptic(); actions.onScale(ObjectPlacement.SCALE_STEP) }
                        ActionPill("Smaller", R.drawable.ic_minus) { haptic(); actions.onScale(1f / ObjectPlacement.SCALE_STEP) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill("Rotate 15°", R.drawable.ic_rotate) { haptic(); actions.onRotate() }
                        ActionPill(if (state.fitting) "Fitting" else "Refit", R.drawable.ic_touch) { haptic(); if (!state.fitting) actions.onFit() }
                    }
                }
            }

            ObjectPhase.CAPTURING, ObjectPhase.PAUSED -> if (state.spinning) {
                if (hud.phoneMoved) Banner(SpinText.PHONE_MOVED)
                Info(hud.spinProgress?.let(SpinText::progress) ?: "Starting the spin...", bold = true)
                if (hud.betweenTurns) {
                    Info(SpinText.NEXT_TURN, bold = true)
                    BigButton(
                        SpinText.startTurnLabel((hud.spinProgress?.turn ?: 1) + 1), R.drawable.ic_rotate, Color(0xFF34C759),
                    ) { haptic(); actions.onSpinNextTurn() }
                } else {
                    Info(SpinText.INSTRUCTION)
                    if (hud.canEndTurn) ActionPill("Next turn", R.drawable.ic_rotate) { haptic(); actions.onSpinNextTurn() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill("Done", R.drawable.ic_check) { haptic(); actions.onFinish() }
                    ActionPill("Reset", null) { haptic(); actions.onReset() }
                }
            } else {
                Info("Coverage ${(state.coverage * 100).toInt()} %  |  ${state.voxels} voxels", bold = true)
                Info(if (state.phase == ObjectPhase.PAUSED) "Paused" else state.hint)
                if (state.captureMode == CaptureMode.HYBRID) {
                    BigButton("Walk done: start the spin", R.drawable.ic_rotate, Color(0xFF34C759)) { haptic(); actions.onBeginHybridSpin() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.phase == ObjectPhase.PAUSED) ActionPill("Resume", R.drawable.ic_play) { haptic(); actions.onResume() }
                    else ActionPill("Pause", null) { haptic(); actions.onPause() }
                    if (state.captureMode != CaptureMode.HYBRID) ActionPill("Finish", R.drawable.ic_check) { haptic(); actions.onFinish() }
                    ActionPill("Reset", null) { haptic(); actions.onReset() }
                }
            }

            ObjectPhase.WORKING -> {
                if (processing != null) ProcessingCard(processing, actions.onCancelJob) else Info("Working...")
            }

            ObjectPhase.RESULT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill("New object", R.drawable.ic_touch) { haptic(); actions.onReset() }
            }
        }
    }
}

@Composable
private fun Info(text: String, bold: Boolean = false, small: Boolean = false, icon: Int? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(
            text,
            color = Color.White,
            fontSize = if (small) 12.sp else 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFD70015).copy(alpha = 0.92f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** The primary action: full width, 56 dp tall. */
@Composable
private fun BigButton(label: String, icon: Int, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) tint.copy(alpha = 0.92f) else Color.Gray.copy(alpha = 0.6f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** A secondary pill, at least 48 dp tall. */
@Composable
private fun ActionPill(label: String, icon: Int?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** A +/- pill: tap = 1 cm, long-press = 5 cm. At least 48 x 48 dp. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepPill(label: String, icon: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** One of the three large capture cards; a disabled one shows why ('needs your PC'). */
@Composable
private fun CaptureCard(o: CaptureOption, onClick: () -> Unit) {
    val alpha = if (o.enabled) 1f else 0.45f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF2C2C2E).copy(alpha = 0.92f))
            .clickable(enabled = o.enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(o.title, color = Color.White.copy(alpha = alpha), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            o.reason?.let { Text(it, color = Color(0xFFFF9500), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        Text(o.subtitle, color = Color.White.copy(alpha = 0.75f * alpha), fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(CaptureModes.HELP_TITLE, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            for (line in CaptureModes.HELP_LINES) Text(line, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        }
    }
}
