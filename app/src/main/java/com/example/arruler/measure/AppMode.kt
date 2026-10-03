package com.example.arruler.measure

/**
 * The four tools of the mode switch. [MeasureMode] (session level) only knows DISTANCE and AREA;
 * SHAPES and SCAN own their state elsewhere (ShapeCapture, ScanController), so the session stays
 * in DISTANCE (cleared) while they are active.
 */
enum class AppMode {
    DISTANCE, AREA, SHAPES, SCAN;

    /** The MeasurementSession mode that goes with this tool. */
    val sessionMode: MeasureMode get() = if (this == AREA) MeasureMode.AREA else MeasureMode.DISTANCE
}
