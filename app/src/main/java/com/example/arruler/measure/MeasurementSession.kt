package com.example.arruler.measure

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { IDLE, MEASURING, FINISHED }

data class MeasureState(
    val points: List<MeasurePoint> = emptyList(),
    /** Moving end point under the crosshair while measuring. */
    val live: MeasurePoint? = null,
    val phase: Phase = Phase.IDLE,
    val unit: Units = Units.CM,
) {
    /** Placed points plus the live point when one is being tracked. */
    val displayPoints: List<MeasurePoint>
        get() = if (live != null && points.isNotEmpty()) points + live else points

    val measurement: Measurement?
        get() {
            val pts = displayPoints
            return when {
                pts.size < 2 -> null
                pts.size == 2 -> Measurement.Distance(pts[0], pts[1])
                else -> Measurement.Polyline(pts)
            }
        }

    val lengthMeters: Float get() = measurement?.lengthMeters ?: 0f
}

/**
 * Holds the measurement state. [maxPoints] = 2 gives the two-point ruler; larger values
 * (e.g. Int.MAX_VALUE) will let polygon/polyline tools reuse the same holder.
 */
class MeasurementSession(private val maxPoints: Int = 2) {
    private val _state = MutableStateFlow(MeasureState())
    val state: StateFlow<MeasureState> = _state.asStateFlow()

    /** Discards any previous points and starts measuring from [p]. */
    fun start(p: MeasurePoint) {
        _state.update { it.copy(points = listOf(p), live = null, phase = Phase.MEASURING) }
    }

    fun addPoint(p: MeasurePoint) {
        _state.update { s ->
            val pts = s.points + p
            s.copy(
                points = pts,
                live = null,
                phase = if (pts.size >= maxPoints) Phase.FINISHED else Phase.MEASURING,
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

    fun undo() {
        _state.update { s ->
            if (s.points.isEmpty()) return@update s
            val pts = s.points.dropLast(1)
            s.copy(points = pts, live = null, phase = if (pts.isEmpty()) Phase.IDLE else Phase.MEASURING)
        }
    }

    fun clear() {
        _state.update { it.copy(points = emptyList(), live = null, phase = Phase.IDLE) }
    }

    fun setUnit(unit: Units) {
        _state.update { it.copy(unit = unit) }
    }

    fun nextUnit() {
        _state.update { it.copy(unit = it.unit.next()) }
    }
}
