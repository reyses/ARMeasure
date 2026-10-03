package com.example.arruler.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.arruler.measure.MeasureState
import com.example.arruler.measure.Phase

/** Unit toggle, main shutter and clear button. Fires haptics, then the callbacks. */
@Composable
fun BoxScope.ControlsBar(
    state: MeasureState,
    onToggleUnit: () -> Unit,
    onMainButton: () -> Unit,
    onClear: () -> Unit,
) {
    val view = LocalView.current
    val haptic = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); Unit }

    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 32.dp)
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .clickable { haptic(); onToggleUnit() },
            color = Color.Black.copy(alpha = 0.4f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(targetState = state.unit.symbol.uppercase(), label = "unit") { text ->
                    Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        val shutterColor by animateColorAsState(
            targetValue = when (state.phase) {
                Phase.MEASURING -> Color(0xFFFF9500)
                Phase.FINISHED -> Color(0xFF007AFF)
                Phase.IDLE -> Color.White
            },
            label = "shutterColor"
        )
        val shutterIconTint by animateColorAsState(
            targetValue = if (state.phase == Phase.IDLE) Color.Black else Color.White,
            label = "shutterIconTint"
        )

        Surface(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .clickable { haptic(); onMainButton() },
            color = shutterColor,
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_crosshair),
                    contentDescription = "Measure",
                    tint = shutterIconTint,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Surface(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .clickable { haptic(); onClear() },
            color = Color.Black.copy(alpha = 0.4f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_clear),
                    contentDescription = "Clear",
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
