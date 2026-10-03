package com.example.arruler.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import com.example.arruler.R

/** Top-start glass button that opens the Projects screen. */
@Composable
fun BoxScope.ProjectsButton(onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = 12.dp, start = 16.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_projects), contentDescription = "Projects", tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** 'Save room' pill above the AREA controls; shown by the caller once the outline is closed. */
@Composable
fun BoxScope.SaveRoomPill(onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 244.dp),
    ) {
        GlassPill("Save room", Color(0xFF34C759)) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        }
    }
}
