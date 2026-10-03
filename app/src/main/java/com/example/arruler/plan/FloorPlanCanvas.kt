package com.example.arruler.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arruler.geometry.Polygon2
import com.example.arruler.geometry.Vec2
import com.example.arruler.measure.Units

@Composable
fun FloorPlanCanvas(
    plan: FloorPlan,
    units: Units,
    modifier: Modifier = Modifier,
    showAngles: Boolean = false,
    selected: Int? = null
) {
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { _, zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(0.4f, 12f)
        pan += panChange
    }
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val wallColor = cs.onSurface
    val tint = cs.primary.copy(alpha = 0.12f)
    val selTint = cs.primary.copy(alpha = 0.28f)
    val selColor = cs.primary
    val labelColor = cs.onSurfaceVariant
    val angleColor = cs.tertiary

    Canvas(modifier.transformable(state)) {
        val w = size.width
        val h = size.height
        val base = PlanLayout.fit(plan, w, h, 48.dp.toPx())
        val t = base.zoomed(zoom, Vec2(pan.x, pan.y))
        val wallStroke = 2.dp.toPx()

        plan.rooms.forEachIndexed { i, room ->
            if (room.outline.points.size < 3) return@forEachIndexed
            val path = outlinePath(room.outline, t)
            val sel = i == selected
            drawPath(path, if (sel) selTint else tint, style = Fill)
            drawPath(path, if (sel) selColor else wallColor, style = Stroke(if (sel) wallStroke * 1.8f else wallStroke))
        }

        val small = TextStyle(fontSize = 12.sp)
        val offsetM = 14.dp.toPx() / t.scale
        val gapM = 16.dp.toPx() / t.scale
        PlanLabels.compute(plan, units, offsetM, gapM, showAngles).forEach { l ->
            val style = when (l.kind) {
                LabelKind.WALL -> small.copy(color = labelColor)
                LabelKind.ANGLE -> TextStyle(fontSize = 11.sp, color = angleColor)
                LabelKind.ROOM_NAME -> TextStyle(fontSize = 14.sp, color = wallColor)
                LabelKind.ROOM_AREA -> small.copy(color = labelColor)
            }
            drawCentered(measurer, l.text, t.toPx(l.pos), -l.angleDeg, style)
        }

        // scale bar, bottom-left
        val bar = PlanLayout.niceScaleBar(t, w * 0.3f)
        val bx = 16.dp.toPx()
        val by = h - 20.dp.toPx()
        val tick = 4.dp.toPx()
        drawLine(wallColor, Offset(bx, by), Offset(bx + bar.px, by), strokeWidth = wallStroke)
        drawLine(wallColor, Offset(bx, by - tick), Offset(bx, by + tick), strokeWidth = wallStroke)
        drawLine(wallColor, Offset(bx + bar.px, by - tick), Offset(bx + bar.px, by + tick), strokeWidth = wallStroke)
        val barLabel = if (bar.meters < 1f) "${(bar.meters * 100).toInt()} cm" else "${bar.meters.toInt()} m"
        drawCentered(measurer, barLabel, Vec2(bx + bar.px / 2f, by - 12.dp.toPx()), 0f, small.copy(color = labelColor))

        // north arrow, top-right (north is always up)
        val nx = w - 28.dp.toPx()
        val ny = 60.dp.toPx()
        val len = 24.dp.toPx()
        drawLine(wallColor, Offset(nx, ny), Offset(nx, ny - len), strokeWidth = wallStroke)
        val head = Path().apply {
            moveTo(nx, ny - len - 4.dp.toPx())
            lineTo(nx - 5.dp.toPx(), ny - len + 5.dp.toPx())
            lineTo(nx + 5.dp.toPx(), ny - len + 5.dp.toPx())
            close()
        }
        drawPath(head, wallColor, style = Fill)
        drawCentered(measurer, "N", Vec2(nx, ny - len - 14.dp.toPx()), 0f, small.copy(color = labelColor))
    }
}

private fun outlinePath(poly: Polygon2, t: PlanTransform): Path = Path().apply {
    poly.points.forEachIndexed { i, p ->
        val q = t.toPx(p)
        if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
    }
    close()
}

private fun DrawScope.drawCentered(m: TextMeasurer, text: String, at: Vec2, rotationDeg: Float, style: TextStyle) {
    val r = m.measure(text, style)
    val topLeft = Offset(at.x - r.size.width / 2f, at.y - r.size.height / 2f)
    if (rotationDeg == 0f) drawText(r, topLeft = topLeft)
    else rotate(rotationDeg, pivot = Offset(at.x, at.y)) { drawText(r, topLeft = topLeft) }
}

internal val previewLRoom = FloorPlan(
    listOf(
        Room(
            "Living",
            Polygon2(
                listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4f, 2f), Vec2(2f, 2f), Vec2(2f, 4f), Vec2(0f, 4f))
            )
        )
    )
)

@Preview(showBackground = true, widthDp = 360, heightDp = 360)
@Composable
private fun FloorPlanCanvasPreview() {
    MaterialTheme {
        FloorPlanCanvas(
            previewLRoom, Units.M,
            Modifier.fillMaxWidth().height(360.dp).padding(4.dp),
            showAngles = true, selected = 0
        )
    }
}
