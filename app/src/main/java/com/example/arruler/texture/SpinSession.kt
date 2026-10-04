package com.example.arruler.texture

import com.example.arruler.objscan.ObjectBox

/** Where a spin capture is: taking photos, paused between two turns, or out of photos. */
enum class SpinStage { SPINNING, BETWEEN, DONE }

/**
 * The turn logic of the spin capture, with no Android types (the Android class feeds it the Y plane of each camera
 * image). One [SpinKeyframePolicy] per turn: its reference pose and its ROI are taken from the FIRST image of the turn,
 * because the phone is tilted between the turns. While the phone has moved away from the reference pose
 * ([SpinKeyframePolicy.phoneMoved]) no photo is taken and [phoneMoved] is true.
 *
 * Turn ends: at [SpinConfig.maxPerTurn] photos (automatic), or by [endTurn] once [MIN_TO_END_TURN] photos exist.
 * The last turn ends in [SpinStage.DONE] and takes no more photos.
 */
class SpinSession(
    val box: ObjectBox,
    private val config: SpinConfig = SpinConfig(),
    val turns: Int = config.turns,
) {
    var stage = SpinStage.SPINNING; private set
    /** 1-based. */
    var turn = 1; private set
    var keptInTurn = 0; private set
    var totalKept = 0; private set
    var phoneMoved = false; private set

    private var policy: SpinKeyframePolicy? = null

    val canEndTurn: Boolean get() = stage == SpinStage.SPINNING && turn < turns && keptInTurn >= MIN_TO_END_TURN

    /**
     * One camera image. [y] is the luma plane. Returns the verdict (KEEP means the caller stores this image), or null
     * when the image was not judged: between turns, finished, no usable ROI, or the phone moved.
     */
    fun onImage(
        tracking: Boolean, pose: FloatArray, k: Intrinsics, y: ByteArray, rowStride: Int, pixelStride: Int, sharpness: Double,
    ): SpinVerdict? {
        if (stage != SpinStage.SPINNING) return null
        if (!tracking) return SpinVerdict.NOT_TRACKING
        var p = policy
        if (p == null) {
            val roi = SpinRoi.fromHull(BoxMask.hull(box, pose, k), k.width, k.height) ?: return null
            p = SpinKeyframePolicy(config, roi)
            p.begin(pose)
            policy = p
        }
        phoneMoved = p.phoneMoved(pose)
        if (phoneMoved) return null
        val v = p.decide(true, com.example.arruler.gpu.GpuGate.roiSignature(p.roi, y, rowStride, pixelStride), sharpness)
        if (v == SpinVerdict.KEEP) {
            keptInTurn++; totalKept++
            if (keptInTurn >= config.maxPerTurn) finishTurn()
        }
        return v
    }

    /** Ends the running turn early; false when it cannot be ended (too few photos, or the last turn). */
    fun endTurn(): Boolean {
        if (!canEndTurn) return false
        finishTurn()
        return true
    }

    /** Starts the next turn when between turns; the next image becomes its reference. */
    fun startNextTurn(): Boolean {
        if (stage != SpinStage.BETWEEN) return false
        turn++
        keptInTurn = 0
        policy = null
        phoneMoved = false
        stage = SpinStage.SPINNING
        return true
    }

    private fun finishTurn() {
        stage = if (turn < turns) SpinStage.BETWEEN else SpinStage.DONE
        phoneMoved = false
    }

    companion object {
        /** A turn can be ended by the user once it holds this many photos (about one third of a full turn). */
        const val MIN_TO_END_TURN = 12
    }
}
