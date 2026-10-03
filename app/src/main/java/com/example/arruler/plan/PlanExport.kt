package com.example.arruler.plan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.example.arruler.measure.Units
import java.util.Locale

private fun f1(v: Float): String = String.format(Locale.US, "%.1f", v)
private fun f4(v: Float): String = String.format(Locale.US, "%.4f", v)

private fun xmlEscape(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * SVG with 1 user unit = 1 mm. Plan y (north) is flipped so north is up. Wall labels carry
 * class="wall", room name/area class="room", angles class="angle".
 */
fun toSvg(plan: FloorPlan, units: Units, showAngles: Boolean = false): String {
    val b = plan.bounds()
    val pad = 1200f // mm of room for outside labels
    val x0 = b.minX * 1000f - pad
    val y0 = -b.maxY * 1000f - pad
    val w = b.width * 1000f + 2 * pad
    val h = b.height * 1000f + 2 * pad
    val sb = StringBuilder()
    sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"${f1(x0)} ${f1(y0)} ${f1(w)} ${f1(h)}\" ")
        .append("width=\"${f1(w)}mm\" height=\"${f1(h)}mm\">\n")
    plan.rooms.forEach { room ->
        if (room.outline.points.size < 3) return@forEach
        val pts = room.outline.points.joinToString(" ") { "${f1(it.x * 1000f)},${f1(-it.y * 1000f)}" }
        sb.append("<polygon points=\"$pts\" fill=\"#4a90d9\" fill-opacity=\"0.12\" stroke=\"#222222\" ")
            .append("stroke-width=\"40\" stroke-linejoin=\"round\"/>\n")
    }
    PlanLabels.compute(plan, units, wallOffsetM = 0.3f, lineGapM = 0.35f, showAngles = showAngles).forEach { l ->
        val x = f1(l.pos.x * 1000f); val y = f1(-l.pos.y * 1000f)
        val cls = when (l.kind) {
            LabelKind.WALL -> "wall"
            LabelKind.ANGLE -> "angle"
            else -> "room"
        }
        val rot = if (l.angleDeg != 0f) " transform=\"rotate(${f1(-l.angleDeg)} $x $y)\"" else ""
        sb.append("<text class=\"$cls\" x=\"$x\" y=\"$y\" font-size=\"180\" font-family=\"sans-serif\" ")
            .append("text-anchor=\"middle\" dominant-baseline=\"central\" fill=\"#222222\"$rot>")
            .append(xmlEscape(l.text)).append("</text>\n")
    }
    sb.append("</svg>\n")
    return sb.toString()
}

/** Renders the plan on a white bitmap using the same label geometry as [toSvg]. */
fun toPngBitmap(plan: FloorPlan, units: Units, widthPx: Int, heightPx: Int, showAngles: Boolean = false): Bitmap {
    val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.WHITE)
    val margin = minOf(widthPx, heightPx) * 0.12f
    val t = PlanLayout.fit(plan, widthPx.toFloat(), heightPx.toFloat(), margin)
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(2f, widthPx / 250f)
        color = Color.parseColor("#222222")
        strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(30, 74, 144, 217) }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        textAlign = Paint.Align.CENTER
        textSize = maxOf(12f, widthPx / 45f)
    }
    plan.rooms.forEach { room ->
        if (room.outline.points.size < 3) return@forEach
        val path = Path()
        room.outline.points.forEachIndexed { i, p ->
            val q = t.toPx(p)
            if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
        }
        path.close()
        c.drawPath(path, fill)
        c.drawPath(path, line)
    }
    val offsetM = text.textSize * 0.9f / t.scale
    PlanLabels.compute(plan, units, offsetM, text.textSize * 1.2f / t.scale, showAngles).forEach { l ->
        val q = t.toPx(l.pos)
        c.save()
        c.rotate(-l.angleDeg, q.x, q.y)
        c.drawText(l.text, q.x, q.y - (text.ascent() + text.descent()) / 2f, text)
        c.restore()
    }
    return bmp
}

/** Minimal DXF R12: one LINE per wall on layer WALLS, units meters. */
fun toDxf(plan: FloorPlan): String {
    val sb = StringBuilder()
    fun g(code: Int, v: String) { sb.append(code).append('\n').append(v).append('\n') }
    g(0, "SECTION"); g(2, "HEADER"); g(9, "\$INSUNITS"); g(70, "6"); g(0, "ENDSEC")
    g(0, "SECTION"); g(2, "ENTITIES")
    plan.rooms.forEach { room ->
        val p = room.outline.points
        if (p.size < 2) return@forEach
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            g(0, "LINE"); g(8, "WALLS")
            g(10, f4(a.x)); g(20, f4(a.y)); g(30, "0.0")
            g(11, f4(b.x)); g(21, f4(b.y)); g(31, "0.0")
        }
    }
    g(0, "ENDSEC"); g(0, "EOF")
    return sb.toString()
}
