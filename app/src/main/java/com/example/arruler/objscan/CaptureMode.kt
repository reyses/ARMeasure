package com.example.arruler.objscan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How an object is captured after the box looks right. */
enum class CaptureMode { WALK, SPIN, HYBRID }

/** One of the three large cards of the capture chooser. */
data class CaptureOption(
    val mode: CaptureMode,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    /** Why it is greyed out ('needs your PC', 'coming soon'); null when enabled. */
    val reason: String?,
)

object CaptureModes {
    const val NEEDS_PC = "needs your PC"
    const val COMING_SOON = "coming soon"

    /**
     * The three cards. Spin and Hybrid process photos on the PC, so they need [pcPaired]; they are also off
     * while [spinAvailable] is false (no spin capture implementation is wired yet).
     */
    fun options(pcPaired: Boolean, spinAvailable: Boolean): List<CaptureOption> {
        fun gated(mode: CaptureMode, title: String, sub: String): CaptureOption {
            val reason = when {
                !pcPaired -> NEEDS_PC
                !spinAvailable -> COMING_SOON
                else -> null
            }
            return CaptureOption(mode, title, sub, reason == null, reason)
        }
        return listOf(
            CaptureOption(CaptureMode.WALK, "Walk around", "Circle the object with the phone. Works on this phone alone.", true, null),
            gated(CaptureMode.SPIN, "Spin (phone on a stand)", "Phone stays still, you turn the object. Photos go to your PC."),
            gated(CaptureMode.HYBRID, "Hybrid (walk once, then spin)", "Walk around once, then spin it. Most accurate."),
        )
    }

    const val HELP_TITLE = "What's best?"
    val HELP_LINES = listOf(
        "Walk around: fastest, needs nothing. Accuracy is limited by this phone's depth sensor.",
        "On this phone depth needs camera motion, so a phone on a stand sees no depth. Spin and Hybrid therefore process photos on your PC.",
        "Spin: phone on a stand, object on a turntable or turned by hand. Good colour and texture.",
        "Hybrid: walk once for the shape, then spin for the detail. Expected to be the most accurate.",
    )
}

/** Spin progress: [turn] of [turns] and the photos kept so far. */
data class SpinProgress(val turn: Int, val turns: Int, val photos: Int)

object SpinText {
    /** 'Turn 1 of 2 · 34 photos' */
    fun progress(p: SpinProgress): String = "Turn ${p.turn} of ${p.turns} · ${p.photos} photos"

    const val PHONE_MOVED = "The phone moved. Put it back on the stand and keep it still."
    const val INSTRUCTION = "Turn the object slowly. Keep the phone still."

    /** Shown between the turns: the second turn is taken from a steeper angle so the top of the object is seen. */
    const val NEXT_TURN = "Next turn: tilt the phone down ~25°"

    fun startTurnLabel(nextTurn: Int) = "Start turn $nextTurn"
}

/**
 * Hook for the SPIN and HYBRID capture (written by the texture drone, processed by the PC drone). MainActivity holds
 * one instance ([NoSpinCapture] until the real one is wired, see docs/WIRING_ROUND3.md); the UI only reads the flows.
 */
interface SpinCapture {
    /** False while there is no implementation; the Spin / Hybrid cards then say 'coming soon'. */
    val available: Boolean

    /** True while the phone is moving during a spin (the banner shows [SpinText.PHONE_MOVED]). */
    val phoneMoved: StateFlow<Boolean>

    /** Turn and photo counters while spinning, null otherwise. */
    val progress: StateFlow<SpinProgress?>

    /** True between two turns: the photos are paused and [SpinText.NEXT_TURN] is shown until [nextTurn] is called. */
    val betweenTurns: StateFlow<Boolean>

    /** True while the current turn has enough photos to be ended early with [nextTurn] (and a further turn exists). */
    val canEndTurn: StateFlow<Boolean>

    /** Ends the running turn (-> between turns), or starts the next turn when between turns. */
    fun nextTurn()

    /** Starts taking photos for [box] on its support plane; [hybrid] is true when a walk capture came first. */
    fun startSpinCapture(box: ObjectBox, plane: SupportPlane, hybrid: Boolean)

    /** Stops taking photos (Done, Reset or leaving the mode). */
    fun stopSpinCapture()
}

/** The placeholder until the real spin capture exists. */
object NoSpinCapture : SpinCapture {
    override val available: Boolean = false
    override val phoneMoved: StateFlow<Boolean> = MutableStateFlow(false)
    override val progress: StateFlow<SpinProgress?> = MutableStateFlow(null)
    override val betweenTurns: StateFlow<Boolean> = MutableStateFlow(false)
    override val canEndTurn: StateFlow<Boolean> = MutableStateFlow(false)
    override fun nextTurn() = Unit
    override fun startSpinCapture(box: ObjectBox, plane: SupportPlane, hybrid: Boolean) = Unit
    override fun stopSpinCapture() = Unit
}
