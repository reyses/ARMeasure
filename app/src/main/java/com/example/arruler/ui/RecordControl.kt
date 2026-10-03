package com.example.arruler.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arruler.ar.RecordingState
import com.example.arruler.ar.formatElapsed
import kotlinx.coroutines.delay

/**
 * Top-end record control: red dot (square while recording) with mm:ss, plus a small play
 * entry for dataset playback. Long-press the record button also opens playback.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoxScope.RecordControl(
    state: RecordingState,
    onToggle: () -> Unit,
    onPlayback: () -> Unit,
) {
    val recording = state as? RecordingState.Recording
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording) {
        while (recording != null) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    Row(
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .combinedClickable(onClick = onToggle, onLongClick = onPlayback)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(if (recording != null) RoundedCornerShape(2.dp) else CircleShape)
                    .background(Color(0xFFFF3B30))
            )
            if (recording != null) {
                Text(
                    text = formatElapsed(now - recording.startedAtMs),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            } else if (state is RecordingState.Error) {
                Text(text = "ERR", color = Color(0xFFFF9500), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        if (recording == null) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .combinedClickable(onClick = onPlayback),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "▶", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

/** Badge shown while a dataset is loaded instead of the live camera. */
@Composable
fun BoxScope.PlaybackBadge(finished: Boolean) {
    Text(
        text = if (finished) "PLAYBACK · END" else "PLAYBACK",
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = 20.dp, start = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF007AFF).copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
