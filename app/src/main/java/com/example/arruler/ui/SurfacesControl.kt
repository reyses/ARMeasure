package com.example.arruler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arruler.R
import com.example.arruler.ar.ArSessionController

/** Which debugging layers are drawn; both are off by default. */
data class OverlayToggles(val planes: Boolean = false, val depthConfidence: Boolean = false)

/**
 * Top-end eye pill below the record control. Tapping it opens a small popup with the two layers:
 * 'Planes' (detected ARCore planes in the AR scene) and 'Depth confidence' (a heatmap over the
 * camera view). The state survives rotation through rememberSaveable.
 */
@Composable
fun BoxScope.SurfacesControl(
    planes: Boolean,
    depthConfidence: Boolean,
    onPlanes: (Boolean) -> Unit,
    onDepthConfidence: (Boolean) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val active = planes || depthConfidence
    Column(
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(if (active) Color(0xFF007AFF).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.4f))
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_eye),
                contentDescription = "Surfaces",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        if (open) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2C2C2E).copy(alpha = 0.92f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                ToggleRow("Planes", planes, onPlanes)
                ToggleRow("Depth confidence", depthConfidence, onDepthConfidence)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = label, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * The depth confidence heatmap over the whole AR view: the bitmap's four corners are drawn at the
 * view positions ARCore maps its image corners to (works for any aspect ratio or rotation).
 */
@Composable
fun DepthConfidenceOverlay(heat: ArSessionController.DepthHeat?) {
    if (heat == null) return
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val bw = heat.bitmap.width.toFloat()
            val bh = heat.bitmap.height.toFloat()
            val m = android.graphics.Matrix()
            m.setPolyToPoly(floatArrayOf(0f, 0f, bw, 0f, 0f, bh, bw, bh), 0, heat.corners, 0, 4)
            val paint = android.graphics.Paint().apply { isFilterBitmap = true }
            canvas.nativeCanvas.drawBitmap(heat.bitmap, m, paint)
        }
    }
}
