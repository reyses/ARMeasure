package com.example.arruler.measure.shapes

import com.example.arruler.measure.MeasurePoint

/** Converts a capture's wireframe to what `ArRenderer.renderExtra` accepts. */
object ShapePreview {
    fun segments(capture: ShapeCapture): List<Pair<MeasurePoint, MeasurePoint>> =
        capture.previewSegments.map { (a, b) ->
            MeasurePoint(a.x, a.y, a.z) to MeasurePoint(b.x, b.y, b.z)
        }
}
