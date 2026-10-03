package com.example.arruler.ml

import com.example.arruler.measure.Units
import com.example.arruler.objscan.ResultAnnotator
import com.example.arruler.objscan.ResultContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result-card line "Shape": [ShapeRecognizer] on the isolated object points of the phone path ([ResultContext.points]),
 * text from [ShapeRecognition.describe] ('Looks like a cylinder (93 %) — r 9.8 cm, h 20.1 cm, formula volume ...', or
 * 'No simple shape fits this object'). The line is stored with the saved object (extras) and so reaches the detail page
 * and measurements.json / .txt. No line when there are no isolated points (a PC result) or fewer than 30 above the plane.
 */
class ShapeAnnotator : ResultAnnotator {
    override suspend fun annotate(ctx: ResultContext, units: Units): List<Pair<String, String>> {
        if (ctx.points.size < 3 * MIN_POINTS) return emptyList()
        val r = withContext(Dispatchers.Default) { ShapeRecognizer.recognize(ctx.points, ctx.plane) } ?: return emptyList()
        return listOf(LABEL to r.describe())
    }

    companion object {
        const val LABEL = "Shape"
        private const val MIN_POINTS = 30
    }
}
