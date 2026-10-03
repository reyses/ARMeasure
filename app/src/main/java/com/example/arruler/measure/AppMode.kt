package com.example.arruler.measure

/**
 * The five tools of the mode switch. [MeasureMode] (session level) only knows DISTANCE and AREA;
 * SHAPES, SCAN and OBJECT own their state elsewhere (ShapeCapture, ScanController, ObjectScanController),
 * so the session stays in DISTANCE (cleared) while they are active.
 */
enum class AppMode {
    DISTANCE, AREA, SHAPES, SCAN, OBJECT;

    /** The MeasurementSession mode that goes with this tool. */
    val sessionMode: MeasureMode get() = if (this == AREA) MeasureMode.AREA else MeasureMode.DISTANCE

    /** SCAN and OBJECT read the depth images, so they only exist where ARCore depth is supported. */
    val needsDepth: Boolean get() = this == SCAN || this == OBJECT
}
