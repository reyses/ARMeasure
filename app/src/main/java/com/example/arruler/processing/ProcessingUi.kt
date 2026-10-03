package com.example.arruler.processing

import com.example.arruler.measure.Units
import com.example.arruler.measure.shapes.ShapeFormat
import java.util.Locale

/** What the progress card shows for one [ProcessingState]: stage text, optional fraction (null = indeterminate). */
data class ProcessingUi(
    val text: String,
    val fraction: Float? = null,
    val warning: String? = null,
    /** True while the job runs on the PC (the card then offers Cancel that also deletes the remote job). */
    val onPc: Boolean = false,
)

/** Pure mapping from service states to progress text; Done / Failed / NeedsConfirmation are handled by the caller. */
object ProcessingUiText {
    /** Server stage ids (see the pc-server processing modules) to words. */
    fun stageLabel(stage: String): String = when (stage) {
        "unpack" -> "Unpacking"
        "load_cloud" -> "Loading the point cloud"
        "crop_box" -> "Cropping to the box"
        "outliers" -> "Removing outliers"
        "cluster" -> "Finding the object"
        "normals" -> "Estimating normals"
        "poisson" -> "Building the mesh"
        "ball_pivoting" -> "Building the mesh (fallback)"
        "measure" -> "Measuring"
        "write_results" -> "Writing results"
        "" -> "Processing"
        else -> stage.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun percent(f: Float): String = "${(f.coerceIn(0f, 1f) * 100).toInt()} %"

    /** Null for terminal / confirmation states. */
    fun of(state: ProcessingState, previous: ProcessingUi? = null): ProcessingUi? = when (state) {
        is ProcessingState.Routed -> ProcessingUi(state.decision.reason, null, onPc = state.decision.backend == Backend.PC)
        is ProcessingState.Warning -> (previous ?: ProcessingUi("Working")).copy(warning = state.message)
        ProcessingState.Packaging -> (previous ?: ProcessingUi("")).copy(text = "Packing the scan", fraction = null)
        is ProcessingState.Uploading -> (previous ?: ProcessingUi("")).copy(text = "Uploading to the PC, ${percent(state.fraction)}", fraction = state.fraction)
        is ProcessingState.Queued -> (previous ?: ProcessingUi("")).copy(
            text = if (state.position > 0) "Waiting in the PC queue (place ${state.position})" else "Waiting for the PC", fraction = null,
        )
        is ProcessingState.Running -> (previous ?: ProcessingUi("")).copy(
            text = stageLabel(state.stage) + if (previous?.onPc == true) ", ${percent(state.progress)}" else "",
            fraction = if (previous?.onPc == true) state.progress else null,
        )
        is ProcessingState.Downloading -> (previous ?: ProcessingUi("")).copy(text = "Downloading the result, ${percent(state.fraction)}", fraction = state.fraction)
        is ProcessingState.Done, is ProcessingState.Failed, is ProcessingState.NeedsConfirmation -> null
    }
}

/** Result of an object job as the card needs it (protocol numbers, meters and m^3). */
data class ObjectOutcome(
    val lengthM: Double,
    val widthM: Double,
    val heightM: Double,
    val volume: Estimate,
    val variants: Map<String, Double>,
    val backend: String,
    val durationMs: Long,
    val notes: List<String>,
) {
    /** Convex-ish when hull and occupancy volumes agree within 10 % (docs/OBJECT_SCAN.md). Null when unknown. */
    val hullToOccupancy: Double?
        get() {
            val h = variants["convex_hull"] ?: return null
            val o = variants["occupancy"] ?: return null
            return if (o > 0) h / o else null
        }

    companion object {
        /** From a protocol result; null when it carries no object dimensions or volume. */
        fun from(r: ResultJson): ObjectOutcome? {
            val d = r.measures.objectDims ?: return null
            val v = r.measures.volumeM3 ?: return null
            return ObjectOutcome(
                d.lengthM, d.widthM, r.measures.heightM?.recommended ?: d.heightM, v,
                r.measures.volumeVariantsM3, r.stats.backend, r.stats.durationMs, r.stats.notes,
            )
        }
    }
}

/** Text of the object result card, in the user's units. */
object ObjectCardText {
    private fun num(v: Float, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)

    /** 0.0080 / 0.314 / 12.35: more decimals for small volumes. */
    fun volumeNumber(v: Float): String = when {
        v < 0.1f -> num(v, 4)
        v < 10f -> num(v, 3)
        else -> num(v, 2)
    }

    /** '0.0080 m³ (0.0078-0.0083)' in [units]; the range is dropped when it is empty. */
    fun volume(units: Units, e: Estimate): String {
        val rec = units.volumeFromCubicMeters(e.recommended.toFloat())
        val lo = units.volumeFromCubicMeters(e.low.toFloat())
        val hi = units.volumeFromCubicMeters(e.high.toFloat())
        val main = volumeNumber(rec) + " " + units.volumeSymbol
        return if (volumeNumber(lo) == volumeNumber(hi)) main else "$main (${volumeNumber(lo)}-${volumeNumber(hi)})"
    }

    fun lines(units: Units, o: ObjectOutcome): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "Footprint" to num(units.fromMeters(o.lengthM.toFloat()), 1) + " x " + num(units.fromMeters(o.widthM.toFloat()), 1) + " " + units.symbol
        out += "Height" to ShapeFormat.length(units, o.heightM.toFloat())
        out += "Volume" to volume(units, o.volume)
        o.hullToOccupancy?.let { if (it >= 1.1) out += "Note" to "approximate (hollow or concave shape)" }
        out += "Computed" to "${o.backend}, " + String.format(Locale.US, "%.1f s", o.durationMs / 1000.0)
        return out
    }
}

/** Text of the room-scan result card for a result that came back from the PC. */
object ScanCardText {
    fun lines(units: Units, r: ResultJson): List<Pair<String, String>> {
        val m = r.measures
        val out = ArrayList<Pair<String, String>>()
        m.areaM2?.let { out += "Area" to ShapeFormat.area(units, it.recommended.toFloat()) }
        m.perimeterM?.let { out += "Perimeter" to ShapeFormat.length(units, it.recommended.toFloat()) }
        m.heightM?.let { out += "Height" to ShapeFormat.length(units, it.recommended.toFloat()) }
        m.volumeM3?.let { out += "Volume" to ShapeFormat.volume(units, it.recommended.toFloat()) }
        m.wallCount?.let { out += "Walls" to it.toString() }
        out += "Computed" to "${r.stats.backend}, " + String.format(Locale.US, "%.1f s", r.stats.durationMs / 1000.0)
        return out
    }
}
