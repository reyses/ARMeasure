package com.example.arruler.measure

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { IDLE, MEASURING, FINISHED }

enum class MeasureMode { DISTANCE, AREA }

data class MeasureState(
    val points: List<MeasurePoint> = emptyList(),
    /** Moving end point under the crosshair while measuring. */
    val live: MeasurePoint? = null,
    val phase: Phase = Phase.IDLE,
    val unit: Units = Units.CM,
    val mode: MeasureMode = MeasureMode.DISTANCE,
    /** AREA mode: the polygon outline has been closed. */
    val closed: Boolean = false,
    /** AREA mode: the height step is running (height point follows the crosshair). */
    val heightActive: Boolean = false,
    /** AREA mode: top point (ceiling) used for the height; live while [heightActive]. */
    val heightPoint: MeasurePoint? = null,
) {
    /** Placed points plus the live point when one is being tracked. */
    val displayPoints: List<MeasurePoint>
        get() = if (live != null && points.isNotEmpty()) points + live else points

    val measurement: Measurement?
        get() {
            val pts = displayPoints
            if (mode == MeasureMode.AREA) {
                val a = if (pts.size >= 3) Measurement.Area(pts) else null
                return when {
                    a != null && closed && heightPoint != null -> Measurement.Volume(a, a.heightOf(heightPoint))
                    a != null -> a
                    pts.size == 2 -> Measurement.Distance(pts[0], pts[1])
                    else -> null
                }
            }
            return when {
                pts.size < 2 -> null
                pts.size == 2 -> Measurement.Distance(pts[0], pts[1])
                else -> Measurement.Polyline(pts)
            }
        }

    val lengthMeters: Float get() = measurement?.lengthMeters ?: 0f

    /** AREA mode: the polygon (also when a volume is shown), else null. */
    val areaMeasurement: Measurement.Area?
        get() = when (val m = measurement) {
            is Measurement.Area -> m
            is Measurement.Volume -> m.area
            else -> null
        }

    val volumeMeasurement: Measurement.Volume? get() = measurement as? Measurement.Volume
}

/**
 * Holds the measurement state. [maxPoints] = 2 gives the two-point ruler; larger values
 * (e.g. Int.MAX_VALUE) will let polygon/polyline tools reuse the same holder. In AREA mode the
 * limit is always unlimited.
 */
class MeasurementSession(private val maxPoints: Int = 2) {
    private val _state = MutableStateFlow(MeasureState())
    val state: StateFlow<MeasureState> = _state.asStateFlow()

    /** Discards any previous points and starts measuring from [p]. */
    fun start(p: MeasurePoint) {
        _state.update {
            it.copy(
                points = listOf(p), live = null, phase = Phase.MEASURING,
                closed = false, heightActive = false, heightPoint = null,
            )
        }
    }

    fun addPoint(p: MeasurePoint) {
        _state.update { s ->
            if (s.closed) return@update s
            val pts = s.points + p
            val limit = if (s.mode == MeasureMode.AREA) Int.MAX_VALUE else maxPoints
            s.copy(
                points = pts,
                live = null,
                phase = if (pts.size >= limit) Phase.FINISHED else Phase.MEASURING,
            )
        }
    }

    fun setLive(p: MeasurePoint?) {
        _state.update { if (it.phase == Phase.MEASURING) it.copy(live = p) else it }
    }

    /** Leaves MEASURING without dropping the points. */
    fun stop() {
        _state.update { if (it.phase == Phase.MEASURING) it.copy(phase = Phase.IDLE) else it }
    }

    /**
     * Removes the last point. In AREA mode a set height is dropped first, then a closed outline
     * is re-opened (points kept), then points are removed one by one.
     */
    fun undo() {
        _state.update { s ->
            if (s.mode == MeasureMode.AREA) {
                when {
                    s.heightActive || s.heightPoint != null ->
                        return@update s.copy(heightActive = false, heightPoint = null)
                    s.closed -> return@update s.copy(closed = false, live = null, phase = Phase.MEASURING)
                }
            }
            if (s.points.isEmpty()) return@update s
            val pts = s.points.dropLast(1)
            s.copy(points = pts, live = null, phase = if (pts.isEmpty()) Phase.IDLE else Phase.MEASURING)
        }
    }

    fun clear() {
        _state.update {
            it.copy(
                points = emptyList(), live = null, phase = Phase.IDLE,
                closed = false, heightActive = false, heightPoint = null,
            )
        }
    }

    fun setUnit(unit: Units) {
        _state.update { it.copy(unit = unit) }
    }

    fun nextUnit() {
        _state.update { it.copy(unit = it.unit.next()) }
    }

    /** Switches mode and discards the current measurement (the unit is kept). */
    fun setMode(mode: MeasureMode) {
        _state.update { MeasureState(unit = it.unit, mode = mode) }
    }

    /** AREA mode: closes the outline. Ignored with fewer than 3 points. */
    fun closePolygon() {
        _state.update { s ->
            if (s.mode != MeasureMode.AREA || s.closed || s.points.size < 3) s
            else s.copy(closed = true, live = null, phase = Phase.FINISHED)
        }
    }

    /** AREA mode: starts the height step on a closed outline. */
    fun startHeight() {
        _state.update { if (it.closed) it.copy(heightActive = true, heightPoint = null) else it }
    }

    /** Height step: the top point follows the crosshair. */
    fun setHeightLive(p: MeasurePoint?) {
        _state.update { if (it.heightActive) it.copy(heightPoint = p) else it }
    }

    /** Height step: fixes the top point and ends the step. */
    fun commitHeight(p: MeasurePoint) {
        _state.update { if (it.heightActive) it.copy(heightActive = false, heightPoint = p) else it }
    }
}
