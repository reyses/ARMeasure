package com.example.arruler.plan

import com.example.arruler.geometry.Vec2
import com.example.arruler.measure.Units
import kotlin.math.min

/**
 * Plan (meters, y north) <-> pixel (y down) mapping: uniform [scale] in px per meter, the plan
 * point [centerM] sits at pixel [centerPx].
 */
data class PlanTransform(val scale: Float, val centerM: Vec2, val centerPx: Vec2) {

    fun toPx(p: Vec2): Vec2 =
        Vec2(centerPx.x + (p.x - centerM.x) * scale, centerPx.y - (p.y - centerM.y) * scale)

    fun toMeters(px: Vec2): Vec2 =
        Vec2(centerM.x + (px.x - centerPx.x) / scale, centerM.y - (px.y - centerPx.y) / scale)

    /** Zoom about the canvas centre by [zoom], then shift by [pan] pixels. */
    fun zoomed(zoom: Float, pan: Vec2): PlanTransform =
        copy(scale = scale * zoom, centerPx = centerPx + pan)
}

data class ScaleBar(val meters: Float, val px: Float, val label: String = "")

object PlanLayout {
    private val NICE_METERS = floatArrayOf(0.5f, 1f, 2f, 5f, 10f)

    /** Fits [plan] into a [widthPx] x [heightPx] area with [marginPx] on every side, centred, north up. */
    fun fit(plan: FloorPlan, widthPx: Float, heightPx: Float, marginPx: Float): PlanTransform {
        val b = plan.bounds()
        val availW = (widthPx - 2f * marginPx).coerceAtLeast(1f)
        val availH = (heightPx - 2f * marginPx).coerceAtLeast(1f)
        val sx = if (b.width > 1e-6f) availW / b.width else Float.MAX_VALUE
        val sy = if (b.height > 1e-6f) availH / b.height else Float.MAX_VALUE
        val s = min(sx, sy).let { if (it == Float.MAX_VALUE) 1f else it }
        return PlanTransform(
            s,
            Vec2((b.minX + b.maxX) / 2f, (b.minY + b.maxY) / 2f),
            Vec2(widthPx / 2f, heightPx / 2f)
        )
    }

    /** Largest of 0.5/1/2/5/10 m whose length fits within [maxPx] (0.5 m if none does). */
    fun niceScaleBar(t: PlanTransform, maxPx: Float): ScaleBar {
        val m = NICE_METERS.lastOrNull { it * t.scale <= maxPx } ?: NICE_METERS.first()
        return ScaleBar(m, m * t.scale)
    }

    private val NICE_FEET = floatArrayOf(0.5f, 1f, 2f, 5f, 10f, 20f, 50f, 100f)
    private const val M_PER_FT = 0.3048f

    /**
     * Scale bar for [units] that fits [maxPx]: metric units pick from 0.5/1/2/5/10 m (labelled cm
     * below 1 m), INCH and FT pick from 6 in/1/2/5/10/20/50/100 ft. [ScaleBar.meters] is the real
     * length, [ScaleBar.label] the text to draw.
     */
    fun niceScaleBar(t: PlanTransform, maxPx: Float, units: Units): ScaleBar {
        if (units.imperial) {
            val ft = NICE_FEET.lastOrNull { it * M_PER_FT * t.scale <= maxPx } ?: NICE_FEET.first()
            val m = ft * M_PER_FT
            val label = if (ft < 1f) "${(ft * 12f).toInt()} in" else "${ft.toInt()} ft"
            return ScaleBar(m, m * t.scale, label)
        }
        val bar = niceScaleBar(t, maxPx)
        val label = if (bar.meters < 1f) "${(bar.meters * 100).toInt()} cm" else "${bar.meters.toInt()} m"
        return bar.copy(label = label)
    }
}

fun fit(plan: FloorPlan, widthPx: Float, heightPx: Float, marginPx: Float): PlanTransform =
    PlanLayout.fit(plan, widthPx, heightPx, marginPx)

fun niceScaleBar(transform: PlanTransform, maxPx: Float): ScaleBar =
    PlanLayout.niceScaleBar(transform, maxPx)
