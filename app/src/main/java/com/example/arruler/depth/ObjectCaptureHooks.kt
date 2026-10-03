package com.example.arruler.depth

import com.example.arruler.objscan.ObjectBox
import com.example.arruler.objscan.SupportPlane
import com.google.ar.core.Frame

/**
 * Hook for keyframe capture during the walk-around object scan (texture drone). MainActivity calls these on the
 * main thread; every method has an empty default so an observer overrides only what it needs. [onFrame] gets the
 * live ARCore frame, valid only inside the call (acquire the camera image there, never keep the frame).
 */
interface CaptureObserver {
    /** The capture started (box locked) or resumed after a pause. */
    fun onCaptureStart(box: ObjectBox, plane: SupportPlane, resumed: Boolean) {}

    /** Once per AR frame while the phase is CAPTURING. */
    fun onFrame(frame: Frame) {}

    fun onCapturePause() {}

    /** Finish was pressed: the capture is over and the result is being computed. */
    fun onCaptureFinish() {}

    /** The object was reset or the mode left: drop everything captured. */
    fun onCaptureReset() {}
}
