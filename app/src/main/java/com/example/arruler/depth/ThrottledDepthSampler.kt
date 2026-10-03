package com.example.arruler.depth

import com.google.ar.core.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The throttled depth path shared by [ScanController] (room scan) and the object scan: every
 * [every]-th AR frame, never with a sample still in flight, the depth images are copied on the frame
 * callback thread ([DepthFrameSampler.acquire]) and un-projected to world points on Dispatchers.Default,
 * where [onFrame]'s sink consumes them.
 */
class ThrottledDepthSampler(
    private val scope: CoroutineScope,
    private val every: Int = ScanLogic.SAMPLE_EVERY_N_FRAMES,
    private val sampler: DepthFrameSampler = DepthFrameSampler(),
) {
    @Volatile private var inFlight = false
    private var frameIndex = 0L

    /**
     * Main thread, once per AR frame while sampling is wanted (call only then, so the frame counter
     * only advances while active). [sink] runs on Dispatchers.Default with the world-space sample.
     */
    fun onFrame(frame: Frame, sink: suspend (DepthSample) -> Unit) {
        frameIndex++
        if (!ScanLogic.shouldSample(frameIndex, every, true, inFlight)) return
        val raw = sampler.acquire(frame) ?: return
        inFlight = true
        scope.launch(Dispatchers.Default) {
            try {
                sink(sampler.process(raw))
            } finally {
                inFlight = false
            }
        }
    }
}
