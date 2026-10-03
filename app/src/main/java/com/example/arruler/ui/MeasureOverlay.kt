package com.example.arruler.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arruler.DistanceFormatter
import com.example.arruler.R
import com.example.arruler.ar.HitInfo
import com.example.arruler.ar.HitQuality
import com.example.arruler.ar.HitRanking
import com.example.arruler.measure.MeasureMode
import com.example.arruler.measure.MeasureState
import com.example.arruler.measure.Phase

/** Crosshair, reticle hint and the top distance read-out. Driven only by state. */
@Composable
fun BoxScope.MeasureOverlay(
    state: MeasureState,
    hasSurface: Boolean,
    hit: HitInfo? = null,
    lowConfidence: Boolean = false,
) {
    val formatter = remember { DistanceFormatter() }
    val distanceText = formatter.format(state.unit.fromMeters(state.lengthMeters), state.unit.symbol)

    val crosshairColor by animateColorAsState(
        hit?.let { qualityColor(it.quality) } ?: if (hasSurface) Color(0xFF34C759) else Color.White,
        label = "crosshairColor"
    )
    val crosshairAlpha by animateFloatAsState(if (hasSurface) 1.0f else 0.5f, label = "crosshairAlpha")
    val crosshairScale by animateFloatAsState(
        if (state.phase == Phase.MEASURING) 0.8f else 1.0f,
        label = "crosshairScale"
    )

    Icon(
        painter = painterResource(id = R.drawable.ic_crosshair),
        contentDescription = "Crosshair",
        tint = crosshairColor,
        modifier = Modifier
            .size(48.dp)
            .align(Alignment.Center)
            .alpha(crosshairAlpha)
            .scale(crosshairScale)
    )

    val reticleText = when {
        state.mode == MeasureMode.AREA -> areaHint(state, hasSurface)
        state.phase == Phase.MEASURING -> distanceText
        state.phase == Phase.FINISHED -> "Tap to Measure Again"
        hasSurface -> "Tap to Start"
        else -> "Find a surface"
    }

    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .offset(y = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        AnimatedContent(targetState = reticleText, label = "reticleText") { text ->
            Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }

    if (hit != null) {
        Text(
            text = HitRanking.label(hit),
            color = qualityColor(hit.quality).copy(alpha = 0.9f),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.Center).offset(y = 92.dp),
        )
    }

    AreaReadout(state)

    if (lowConfidence) {
        Text(
            text = "± lower confidence",
            color = Color(0xFFFFD60A),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (state.mode == MeasureMode.AREA) 150.dp else 88.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }

    if (state.mode == MeasureMode.DISTANCE && state.lengthMeters > 0) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFF2C2C2E).copy(alpha = 0.85f))
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            AnimatedContent(targetState = distanceText, label = "distance") { text ->
                Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 32.sp)
            }
        }
    }
}

/** Crosshair colour by hit quality: plane green, extended plane light green, depth yellow, points orange. */
fun qualityColor(q: HitQuality): Color = when (q) {
    HitQuality.PLANE -> Color(0xFF34C759)
    HitQuality.PLANE_EXTENDED -> Color(0xFFA8E6A1)
    HitQuality.DEPTH -> Color(0xFFFFD60A)
    HitQuality.POINT -> Color(0xFFFF9500)
}
