package com.example.arruler.objscan

import com.example.arruler.geometry.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Where the depth points around the tap come from (the OBJECT controller's pre-scan cloud). The cloud keeps
 * filling while a proposal waits, so a provider may poll [voxelCount] and call [points] again later.
 */
interface DepthPointSource {
    /** Voxels collected inside the search region so far. */
    fun voxelCount(): Int

    /** The collected points as packed world xyz (meters). */
    suspend fun points(): FloatArray
}

/** A camera image around the tap for ML providers (filled by the activity only when a provider needs it; null today). */
class FrameImage(val width: Int, val height: Int, val argb: IntArray, val tapU: Float, val tapV: Float)

/**
 * Everything an [AutoBoxProvider] may look at for one tap. Lengths are meters, world frame, +Y up.
 *
 * @property plane the support plane (table or floor) under the tapped object.
 * @property tapOnObject the world point the tap ray hit (a point ON the object, usually).
 * @property tapOnPlane [tapOnObject] dropped straight down onto [plane]; the centre of the search region.
 */
class FrameContext(
    val plane: SupportPlane,
    val tapOnObject: Vec3,
    val tapOnPlane: Vec3,
    val camera: Vec3,
    val viewWidth: Int,
    val viewHeight: Int,
    val depth: DepthPointSource,
    val image: FrameImage? = null,
)

/**
 * Turns a tap on the object into a box aligned with it. The built-in [DepthFitAutoBox] needs only depth;
 * an ML detector can replace it by swapping the one provider line in MainActivity (see docs/WIRING_ROUND3.md).
 * Returns null when it could not find the object (the caller keeps the default box and says so).
 */
interface AutoBoxProvider {
    suspend fun propose(tapX: Float, tapY: Float, frameContext: FrameContext): ObjectBox?
}

/**
 * Built-in provider: the existing Fit seeded at the tap. Depth points above the support plane within 40 cm of
 * the tap are isolated, and the oriented minimum-area footprint gives the yaw, so the box is aligned to the
 * object with no rotate step. Waits up to [timeoutMs] for enough depth, retrying when the cloud has grown.
 */
class DepthFitAutoBox(
    private val timeoutMs: Long = 4000L,
    private val pollMs: Long = 150L,
    private val minVoxels: Int = 150,
) : AutoBoxProvider {

    override suspend fun propose(tapX: Float, tapY: Float, frameContext: FrameContext): ObjectBox? {
        val search = ObjectPlacement.searchBox(frameContext.tapOnPlane)
        var waited = 0L
        var lastTried = 0
        while (true) {
            val n = frameContext.depth.voxelCount()
            val last = waited >= timeoutMs
            if (n >= minVoxels && (n * 2 >= lastTried * 3 || last)) {
                lastTried = n
                val pts = frameContext.depth.points()
                val box = withContext(Dispatchers.Default) { ObjectPlacement.fit(pts, search, frameContext.plane) }
                if (box != null) return box
            }
            if (last) return null
            delay(pollMs)
            waited += pollMs
        }
    }
}
