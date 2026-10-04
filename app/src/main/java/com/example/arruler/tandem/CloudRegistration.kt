package com.example.arruler.tandem

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.LongIntMap
import com.example.arruler.objscan.SupportPlane
import com.example.arruler.objscan.cellKey
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Rigid transform p' = R p + t in doubles; [r] row-major 3x3. Maps HELPER-frame points into the LEADER frame in this file. */
class Rigid(val r: DoubleArray, val t: DoubleArray) {
    fun mul(o: Rigid): Rigid {
        val rr = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) rr[i * 3 + j] = r[i * 3] * o.r[j] + r[i * 3 + 1] * o.r[3 + j] + r[i * 3 + 2] * o.r[6 + j]
        val tt = DoubleArray(3) { i -> r[i * 3] * o.t[0] + r[i * 3 + 1] * o.t[1] + r[i * 3 + 2] * o.t[2] + t[i] }
        return Rigid(rr, tt)
    }

    fun inverse(): Rigid {
        val rt = DoubleArray(9) { r[(it % 3) * 3 + it / 3] }
        val tt = DoubleArray(3) { i -> -(rt[i * 3] * t[0] + rt[i * 3 + 1] * t[1] + rt[i * 3 + 2] * t[2]) }
        return Rigid(rt, tt)
    }

    fun applyX(x: Double, y: Double, z: Double) = r[0] * x + r[1] * y + r[2] * z + t[0]
    fun applyY(x: Double, y: Double, z: Double) = r[3] * x + r[4] * y + r[5] * z + t[1]
    fun applyZ(x: Double, y: Double, z: Double) = r[6] * x + r[7] * y + r[8] * z + t[2]

    fun apply(p: Vec3): Vec3 {
        val x = p.x.toDouble(); val y = p.y.toDouble(); val z = p.z.toDouble()
        return Vec3(applyX(x, y, z).toFloat(), applyY(x, y, z).toFloat(), applyZ(x, y, z).toFloat())
    }

    fun transformPoints(p: FloatArray): FloatArray {
        val out = FloatArray(p.size)
        for (i in 0 until p.size / 3) {
            val x = p[i * 3].toDouble(); val y = p[i * 3 + 1].toDouble(); val z = p[i * 3 + 2].toDouble()
            out[i * 3] = applyX(x, y, z).toFloat(); out[i * 3 + 1] = applyY(x, y, z).toFloat(); out[i * 3 + 2] = applyZ(x, y, z).toFloat()
        }
        return out
    }

    /** Rotation angle of the linear part in degrees. */
    fun rotationDeg(): Double = Math.toDegrees(acos(((r[0] + r[4] + r[8] - 1.0) / 2.0).coerceIn(-1.0, 1.0)))

    /** Angle in degrees of the rotation taking this transform to [o]. */
    fun rotationDiffDeg(o: Rigid): Double = inverse().mul(o).rotationDeg()

    /** Difference of the translation parts (depends on the origin: a rotation error shows up here multiplied by the distance to it). */
    fun translationDiff(o: Rigid): Double = sqrt((0..2).sumOf { (t[it] - o.t[it]) * (t[it] - o.t[it]) })

    /** How far apart the two transforms put the point [p]; use the object's centre for a meaningful "translation error". */
    fun displacementAt(o: Rigid, p: Vec3): Double {
        val x = p.x.toDouble(); val y = p.y.toDouble(); val z = p.z.toDouble()
        val dx = applyX(x, y, z) - o.applyX(x, y, z); val dy = applyY(x, y, z) - o.applyY(x, y, z); val dz = applyZ(x, y, z) - o.applyZ(x, y, z)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** 4x4 column-major (ARCore pose layout). */
    fun toMatrix16(): FloatArray = floatArrayOf(
        r[0].toFloat(), r[3].toFloat(), r[6].toFloat(), 0f,
        r[1].toFloat(), r[4].toFloat(), r[7].toFloat(), 0f,
        r[2].toFloat(), r[5].toFloat(), r[8].toFloat(), 0f,
        t[0].toFloat(), t[1].toFloat(), t[2].toFloat(), 1f,
    )

    /** Pre-multiplies a camera-to-world [pose] (16 floats, column-major) so it is expressed in the leader frame. */
    fun applyToPose(pose: FloatArray): FloatArray {
        val m = Rigid(
            doubleArrayOf(pose[0].toDouble(), pose[4].toDouble(), pose[8].toDouble(), pose[1].toDouble(), pose[5].toDouble(), pose[9].toDouble(), pose[2].toDouble(), pose[6].toDouble(), pose[10].toDouble()),
            doubleArrayOf(pose[12].toDouble(), pose[13].toDouble(), pose[14].toDouble()),
        )
        return mul(m).toMatrix16()
    }

    companion object {
        val IDENTITY = Rigid(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0))

        /** Rodrigues rotation about the unit [axis] by [rad]. */
        fun rotation(axis: DoubleArray, rad: Double, t: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0)): Rigid {
            val l = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
            val x = axis[0] / l; val y = axis[1] / l; val z = axis[2] / l
            val c = cos(rad); val s = sin(rad); val k = 1 - c
            return Rigid(
                doubleArrayOf(
                    c + x * x * k, x * y * k - z * s, x * z * k + y * s,
                    y * x * k + z * s, c + y * y * k, y * z * k - x * s,
                    z * x * k - y * s, z * y * k + x * s, c + z * z * k,
                ),
                t,
            )
        }

        /** Minimal rotation taking unit vector [a] to unit vector [b]. */
        fun between(a: Vec3, b: Vec3): Rigid {
            val an = a.normalized(); val bn = b.normalized()
            val d = an.dot(bn).toDouble().coerceIn(-1.0, 1.0)
            val c = an.cross(bn)
            val cl = c.length().toDouble()
            if (cl < 1e-9) {
                if (d > 0) return IDENTITY
                val ortho = if (abs(an.x) < 0.9f) Vec3(1f, 0f, 0f).cross(an) else Vec3(0f, 1f, 0f).cross(an)
                return rotation(doubleArrayOf(ortho.x.toDouble(), ortho.y.toDouble(), ortho.z.toDouble()), Math.PI)
            }
            return rotation(doubleArrayOf(c.x.toDouble(), c.y.toDouble(), c.z.toDouble()), atan2(cl, d))
        }

        fun translation(x: Double, y: Double, z: Double) = Rigid(IDENTITY.r, doubleArrayOf(x, y, z))
    }
}

/** What both phones know about their own ARCore frames. All fields are in the owning phone's frame. */
data class FrameConstraints(
    val mode: CaptureMode,
    /** Support planes (n.p = d, normal up). Null = unknown; both are needed to fix the vertical offset. */
    val leaderPlane: SupportPlane? = null,
    val helperPlane: SupportPlane? = null,
    /** Up vectors, used only when the corresponding plane is null. ARCore frames are gravity aligned, so +Y. */
    val leaderUp: Vec3 = Vec3(0f, 1f, 0f),
    val helperUp: Vec3 = Vec3(0f, 1f, 0f),
    /** A point on the vertical turntable axis (SPIN) or the object box base centre, in each frame. */
    val leaderAxis: Vec3? = null,
    val helperAxis: Vec3? = null,
    /** Hint (deg) of the helper-frame yaw about up relative to the leader frame (+-[yawPriorHalfWidthDeg]); null = search the full circle. */
    val yawPriorDeg: Float? = null,
    val yawPriorHalfWidthDeg: Float = 30f,
)

data class RegistrationOptions(
    /** Typical point spacing in m (isolated clouds: the voxel size). Everything else scales from it. */
    val spacing: Float = 0.005f,
    /** Distance (m) to the other cloud's nearest point below which a helper point counts as overlapping (fitness, RMSE). */
    val inlierDist: Float = 2f * spacing,
    /** Width of the Gaussian kernel of the refinement cost: about the depth noise (bounded cost 1 - exp(-d^2 / 2 s^2)). */
    val kernelSigma: Float = 2f * spacing,
    /** PCA radius of the surface normals and the smallest |cos| between normals for two points to be matched (one face does not match its neighbour across an edge). */
    val normalRadius: Float = 3f * spacing,
    val normalCosMin: Float = 0.85f,
    /** Project points of flat neighbourhoods onto their local plane before registering (takes the depth noise out of the cost). */
    val denoise: Boolean = true,
    /** Points per cloud in the polish of the winning candidate (all candidates are refined with [maxSourceSamples]). */
    val polishSamples: Int = 12000,
    /** Helper points used by the refinement cost (the fitness uses up to 8000). */
    val maxSourceSamples: Int = 2000,
    /** Largest horizontal correction (m) and yaw correction (deg) the refinement may apply to a candidate; a result that sits on the limit is rejected. */
    val maxShiftM: Float = 0.08f,
    val icpMaxDriftDeg: Float = 8f,
    /** Reject a result whose total move away from the constraint-based pose exceeds this rotation (deg). */
    val maxRotationDeg: Float = 25f,
    /** Overlap (fitness) below this fails the registration. */
    val minOverlap: Float = 0.10f,
    val minInliers: Int = 50,
    val yawStepDeg: Float = 1f,
    val candidates: Int = 4,
    /** Footprint search (WALK): cell size, step and the height above the support plane from which points count. */
    val footprintCell: Float = 0.10f,
    val footprintYawStepDeg: Float = 3f,
    val footprintMinHeight: Float = 0.30f,
    /** Score cell of the coarse search; 0 = the spacing. */
    val coarseCell: Float = 0f,
    /** How well the object axis was placed (m): the prior on the horizontal shift; with no shared axis (WALK) the shift is free within [maxShiftM]. */
    val axisSigmaM: Float = 0.012f,
    /** A direction whose cost curvature is below this fraction of the strongest one is reported as not pinned by the overlap. */
    val underconstrainedRatio: Float = 0.05f,
)

class RegistrationResult(
    val ok: Boolean,
    /** Maps helper-frame points into the leader frame. */
    val helperToLeader: Rigid,
    /** Fraction of helper samples with a leader surface point within the inlier distance and a matching normal. */
    val fitness: Double,
    /** RMS distance (m) of the inlier correspondences. */
    val rmseM: Double,
    val inliers: Int,
    /** Another well-separated yaw explains the data nearly as well (a symmetric object): do not trust without a yaw prior. */
    val ambiguous: Boolean,
    /** The overlap does not constrain some translation direction (e.g. only parallel faces overlap): that direction follows the constraints. */
    val underconstrained: Boolean,
    val yawDeg: Double,
    val reason: String? = null,
    /** False when the yaw came from the geometry alone: a symmetric object seen from opposite sides can then be mirrored without any sign of it. */
    val yawPriorUsed: Boolean = false,
)

/**
 * Registers the helper's point cloud into the leader's frame.
 *
 *  1. Shared constraints give most of the pose. ARCore frames are gravity aligned, so the unknowns are a yaw about the
 *     vertical and a translation: the up vectors fix the tilt, the support planes fix the height, and in SPIN the
 *     turntable axis (a point in both frames) fixes the horizontal translation, leaving ONE unknown: the yaw.
 *     A 1-D search over the yaw (full circle, or the window of [FrameConstraints.yawPriorDeg]) maximises voxel overlap.
 *     In WALK (no common axis) the footprints of the points above the support plane are matched by a coarse
 *     (yaw, tx, tz) grid search instead.
 *  2. The best [RegistrationOptions.candidates] distinct peaks are refined by [KcRefiner]: a symmetric kernel-correlation cost over
 *     (yaw, horizontal shift) on denoised clouds, matching only like-oriented surface (PCA normals), with a Gaussian prior on the shift
 *     (the axis placement accuracy) and the directions the overlap cannot pin (a strip of parallel faces) frozen at the constraint value.
 *     The winner is polished on a larger point sample.
 *  3. The result with the best fitness wins; fitness / RMSE / ambiguity / under-constraint are reported, and the
 *     registration FAILS when overlap is below [RegistrationOptions.minOverlap] or the refinement wandered too far from the guess.
 *     Without a yaw prior ([RegistrationResult.yawPriorUsed] = false) a symmetric object seen from opposite sides can come out
 *     mirrored with no sign of it: geometry alone cannot decide that, so production calls pass a prior (compass or planned positions).
 */
object CloudRegistration {
    /** Diagnostic sink for the candidate list (tests and bring-up); null in production. */
    @Volatile var trace: ((String) -> Unit)? = null

    fun register(leader: FloatArray, helper: FloatArray, c: FrameConstraints, o: RegistrationOptions = RegistrationOptions()): RegistrationResult {
        val nL = leader.size / 3; val nH = helper.size / 3
        if (nL < o.minInliers || nH < o.minInliers) return fail("too few points (leader $nL, helper $nH)")

        val upL = (c.leaderPlane?.normal ?: c.leaderUp).normalized()
        val upH = (c.helperPlane?.normal ?: c.helperUp).normalized()
        val rUp = Rigid.between(upH, upL)
        val delta = if (c.leaderPlane != null && c.helperPlane != null) (c.leaderPlane.d - c.helperPlane.d).toDouble() else 0.0
        val upLd = doubleArrayOf(upL.x.toDouble(), upL.y.toDouble(), upL.z.toDouble())
        // helper frame brought to the leader orientation and support height
        val pre = Rigid(rUp.r, DoubleArray(3) { rUp.t[it] + delta * upLd[it] })

        val coarse = if (o.coarseCell > 0f) o.coarseCell else o.spacing
        val candidates: List<Rigid> =
            if (c.leaderAxis != null && c.helperAxis != null) {
                spinCandidates(leader, helper, c, o, pre, upLd, coarse)
            } else {
                footprintCandidates(leader, helper, c, o, pre, upL, coarse)
            }
        if (candidates.isEmpty()) return fail("no candidate pose")

        // ICP runs in coordinates centred on the leader cloud so rotation and translation do not couple through a far-away origin
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (i in 0 until nL) { cx += leader[i * 3]; cy += leader[i * 3 + 1]; cz += leader[i * 3 + 2] }
        cx /= nL; cy /= nL; cz /= nL
        if (c.leaderAxis != null && c.helperAxis != null) {
            // rotate about the object axis (horizontal position of the shared axis, height of the cloud) so a yaw does not look like a shift to the placement prior
            val ah = horizontal(c.leaderAxis, upLd)
            val hc = cx * upLd[0] + cy * upLd[1] + cz * upLd[2]
            cx = ah[0] + hc * upLd[0]; cy = ah[1] + hc * upLd[1]; cz = ah[2] + hc * upLd[2]
        }
        val toC = Rigid.translation(-cx, -cy, -cz); val fromC = Rigid.translation(cx, cy, cz)
        val hasAxis = c.leaderAxis != null && c.helperAxis != null
        val hasPlanes = c.leaderPlane != null && c.helperPlane != null
        val refiner = KcRefiner(
            toC.transformPoints(leader), toC.transformPoints(helper), o, upLd,
            freeHeight = !hasPlanes, shiftSigma = if (hasAxis) o.axisSigmaM.toDouble() else 1.0,
        )
        val results = candidates.map { init ->
            val r = refiner.refine(toC.mul(init).mul(fromC))
            CandidateResult(fromC.mul(r.t).mul(toC), r.fitness, r.rmse, r.inliers, r.curvatureRatio).also { it.init = init }
        }

        trace?.let { tr ->
            for (r in results) tr("candidate yaw %.1f -> fitness %.3f rmse %.2f mm yaw %.1f shift %.0f mm".format(yawOf(r.init!!, upLd), r.fitness, r.rmse * 1000, yawOf(r.t, upLd), r.t.displacementAt(r.init!!, Vec3(0f, 0f, 0f)) * 1000))
        }
        val ranked = results.sortedByDescending { it.fitness }
        val coarseBest = ranked[0]
        val pol = refiner.refine(toC.mul(coarseBest.t).mul(fromC), fine = true)
        val best = CandidateResult(fromC.mul(pol.t).mul(toC), pol.fitness, pol.rmse, pol.inliers, pol.curvatureRatio).also { it.init = coarseBest.init }
        val yawBest = yawOf(best.t, upLd)
        val ambiguous = ranked.drop(1).any { r ->
            r.fitness >= 0.85 * best.fitness && angDiff(yawOf(r.t, upLd), yawBest) > 20.0
        }
        var hx = 0.0; var hy = 0.0; var hz = 0.0
        for (i in 0 until nH) { hx += helper[i * 3]; hy += helper[i * 3 + 1]; hz += helper[i * 3 + 2] }
        val hc = Vec3((hx / nH).toFloat(), (hy / nH).toFloat(), (hz / nH).toFloat())
        val shift = best.t.displacementAt(best.init!!, hc)
        val rot = best.t.rotationDiffDeg(best.init!!)
        val reason = when {
            best.inliers < o.minInliers || best.fitness < o.minOverlap ->
                "overlap %.1f %% is below the %.0f %% minimum".format(best.fitness * 100, o.minOverlap * 100)
            shift > o.maxShiftM -> "refinement moved the constraint-based pose %.0f mm (limit %.0f mm)".format(shift * 1000, o.maxShiftM * 1000)
            rot > o.maxRotationDeg -> "refinement rotated the pose %.1f deg (limit %.0f deg)".format(rot, o.maxRotationDeg)
            else -> null
        }
        val under = best.curvatureRatio < o.underconstrainedRatio
        return RegistrationResult(
            ok = reason == null, helperToLeader = best.t, fitness = best.fitness, rmseM = best.rmse, inliers = best.inliers,
            ambiguous = ambiguous, underconstrained = under, yawDeg = yawBest, reason = reason, yawPriorUsed = c.yawPriorDeg != null,
        )
    }

    private fun fail(reason: String) = RegistrationResult(false, Rigid.IDENTITY, 0.0, Double.NaN, 0, false, false, 0.0, reason)

    private fun angDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180) d -= 360; if (d < -180) d += 360
        return abs(d)
    }

    /** Yaw (deg) of the rotation part about the unit [up]. */
    private fun yawOf(t: Rigid, up: DoubleArray): Double {
        // image of a horizontal reference vector, measured about up
        val ref = if (abs(up[1]) < 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val e1 = norm(cross(up, ref)); val e2 = cross(up, e1)
        val x = t.r[0] * e1[0] + t.r[1] * e1[1] + t.r[2] * e1[2]
        val y = t.r[3] * e1[0] + t.r[4] * e1[1] + t.r[5] * e1[2]
        val z = t.r[6] * e1[0] + t.r[7] * e1[1] + t.r[8] * e1[2]
        return Math.toDegrees(atan2(x * e2[0] + y * e2[1] + z * e2[2], x * e1[0] + y * e1[1] + z * e1[2]))
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(a: DoubleArray): DoubleArray { val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); return doubleArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun yawWindow(c: FrameConstraints, step: Double): List<Double> {
        val p = c.yawPriorDeg
        return if (p == null) generateSequence(0.0) { it + step }.takeWhile { it < 360.0 }.toList()
        else {
            val hw = c.yawPriorHalfWidthDeg.toDouble()
            generateSequence(-hw) { it + step }.takeWhile { it <= hw + 1e-9 }.map { p + it }.toList()
        }
    }

    /** Local maxima of a circular/windowed score list, best first, at least [minSepDeg] apart. */
    private fun peaks(angles: List<Double>, scores: DoubleArray, k: Int, minSepDeg: Double): List<Int> {
        // a peak is the best score within +-[minSepDeg] / 2 (the slope of a taller peak next to it is not a candidate of its own)
        val half = minSepDeg / 2
        val isPeak = BooleanArray(scores.size) { i ->
            scores.indices.none { j -> j != i && angDiff(angles[i], angles[j]) <= half && (scores[j] > scores[i] || (scores[j] == scores[i] && j < i)) }
        }
        val order = scores.indices.filter { isPeak[it] }.sortedByDescending { scores[it] }
        val out = ArrayList<Int>()
        for (i in order) {
            if (out.size >= k) break
            if (out.all { angDiff(angles[it], angles[i]) >= minSepDeg }) out += i
        }
        return out
    }

    private fun sample(p: FloatArray, maxN: Int): FloatArray {
        val n = p.size / 3
        if (n <= maxN) return p
        val out = FloatArray(maxN * 3)
        for (i in 0 until maxN) { val s = (i.toLong() * n / maxN).toInt(); out[i * 3] = p[s * 3]; out[i * 3 + 1] = p[s * 3 + 1]; out[i * 3 + 2] = p[s * 3 + 2] }
        return out
    }

    /** Occupancy of the points' cells, dilated by one cell; keys are cell indices packed with a bias. */
    private class Occupancy(points: FloatArray, val cell: Float) {
        private val set = HashSet<Long>()
        init {
            val inv = 1f / cell
            for (i in 0 until points.size / 3) {
                val ix = floor(points[i * 3] * inv).toInt(); val iy = floor(points[i * 3 + 1] * inv).toInt(); val iz = floor(points[i * 3 + 2] * inv).toInt()
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) set += key(ix + dx, iy + dy, iz + dz)
            }
        }
        fun has(x: Float, y: Float, z: Float): Boolean {
            val inv = 1f / cell
            return key(floor(x * inv).toInt(), floor(y * inv).toInt(), floor(z * inv).toInt()) in set
        }
        private fun key(x: Int, y: Int, z: Int) = ((x + 1_000_000).toLong() shl 42) or ((y + 1_000_000).toLong() shl 21) or (z + 1_000_000).toLong()
    }

    private fun spinCandidates(leader: FloatArray, helper: FloatArray, c: FrameConstraints, o: RegistrationOptions, pre: Rigid, up: DoubleArray, cell: Float): List<Rigid> {
        // only the horizontal parts of the axis points matter; the vertical offset is already in [pre]
        val aH = horizontal(pre.apply(c.helperAxis!!), up)
        val aL = horizontal(c.leaderAxis!!, up)
        val occ = Occupancy(leader, cell)
        val hs = sample(helper, 2500)
        val nS = hs.size / 3
        val angles = yawWindow(c, o.yawStepDeg.toDouble())
        val cand = angles.map { a -> spinPose(pre, up, aH, aL, Math.toRadians(a)) }
        val scores = DoubleArray(angles.size)
        for ((i, t) in cand.withIndex()) {
            var hit = 0
            for (k in 0 until nS) {
                val x = hs[k * 3].toDouble(); val y = hs[k * 3 + 1].toDouble(); val z = hs[k * 3 + 2].toDouble()
                if (occ.has(t.applyX(x, y, z).toFloat(), t.applyY(x, y, z).toFloat(), t.applyZ(x, y, z).toFloat())) hit++
            }
            scores[i] = hit.toDouble() / nS
        }
        trace?.invoke("yaw scores: " + angles.indices.filter { it % 4 == 0 }.joinToString(" ") { "%.0f:%.2f".format(((angles[it] + 540) % 360) - 180, scores[it]) })
        return peaks(angles, scores, o.candidates, 10.0).map { cand[it] }
    }

    private fun horizontal(v: Vec3, up: DoubleArray): DoubleArray {
        val vv = doubleArrayOf(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
        val s = dot(vv, up)
        return DoubleArray(3) { vv[it] - s * up[it] }
    }

    /** helper point p -> R_yaw (pre p - aH_horizontal) + aL_horizontal. */
    private fun spinPose(pre: Rigid, up: DoubleArray, aH: DoubleArray, aL: DoubleArray, yaw: Double): Rigid {
        val ry = Rigid.rotation(up, yaw)
        val shifted = Rigid(Rigid.IDENTITY.r, doubleArrayOf(-aH[0], -aH[1], -aH[2]))
        val back = Rigid(Rigid.IDENTITY.r, aL)
        return back.mul(ry).mul(shifted).mul(pre)
    }

    private fun footprintCandidates(leader: FloatArray, helper: FloatArray, c: FrameConstraints, o: RegistrationOptions, pre: Rigid, upL: Vec3, cell: Float): List<Rigid> {
        val up = doubleArrayOf(upL.x.toDouble(), upL.y.toDouble(), upL.z.toDouble())
        val ref = if (abs(up[1]) < 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val e1 = norm(cross(up, ref)); val e2 = cross(up, e1)
        val planeD = c.leaderPlane?.d?.toDouble()
        val minH = if (planeD != null) o.footprintMinHeight.toDouble() else Double.NEGATIVE_INFINITY
        val fc = o.footprintCell.toDouble()

        fun footprint(p: FloatArray, shift: Rigid?): List<DoubleArray> {
            val seen = HashSet<Long>(); val out = ArrayList<DoubleArray>()
            for (i in 0 until p.size / 3) {
                var x = p[i * 3].toDouble(); var y = p[i * 3 + 1].toDouble(); var z = p[i * 3 + 2].toDouble()
                if (shift != null) { val nx = shift.applyX(x, y, z); val ny = shift.applyY(x, y, z); val nz = shift.applyZ(x, y, z); x = nx; y = ny; z = nz }
                val h = (x * up[0] + y * up[1] + z * up[2]) - (planeD ?: 0.0)
                if (h < minH) continue
                val u = x * e1[0] + y * e1[1] + z * e1[2]; val v = x * e2[0] + y * e2[1] + z * e2[2]
                val key = (floor(u / fc).toLong() + 1_000_000L shl 24) xor (floor(v / fc).toLong() + 1_000_000L)
                if (seen.add(key)) out += doubleArrayOf(u, v)
            }
            return out
        }

        val lf = footprint(leader, null)
        val hf0 = footprint(helper, pre)
        if (lf.size < 8 || hf0.size < 8) return emptyList()
        val hf = if (hf0.size > 600) List(600) { hf0[(it.toLong() * hf0.size / 600).toInt()] } else hf0
        val lset = HashSet<Long>()
        fun ck(u: Double, v: Double) = (floor(u / fc).toLong() + 1_000_000L shl 24) xor (floor(v / fc).toLong() + 1_000_000L)
        for (p in lf) for (dx in -1..1) for (dy in -1..1) lset += ck(p[0] + dx * fc, p[1] + dy * fc)
        var u0 = Double.MAX_VALUE; var u1 = -Double.MAX_VALUE; var v0 = Double.MAX_VALUE; var v1 = -Double.MAX_VALUE
        for (p in lf) { u0 = min(u0, p[0]); u1 = max(u1, p[0]); v0 = min(v0, p[1]); v1 = max(v1, p[1]) }
        // The area the leader has seen (convex hull of its footprint, as cells). A helper feature that lands inside it without a leader
        // feature to match is a CONFLICT: counting only matches would pick the shift that lays the helper on top of the most leader
        // structure (two parallel walls slide along each other for free), not the one that agrees.
        val hull = com.example.arruler.depth.ConvexHull.of(lf.map { com.example.arruler.geometry.Vec2(it[0].toFloat(), it[1].toFloat()) })
        val hullCells = HashSet<Long>()
        if (hull.size >= 3) {
            var iu = floor(u0 / fc).toLong()
            while (iu <= floor(u1 / fc).toLong()) {
                var iv = floor(v0 / fc).toLong()
                while (iv <= floor(v1 / fc).toLong()) {
                    val pu = (iu + 0.5) * fc; val pv = (iv + 0.5) * fc
                    var inside = true
                    for (e in hull.indices) {
                        val a = hull[e]; val b = hull[(e + 1) % hull.size]
                        if ((b.x - a.x) * (pv - a.y) - (b.y - a.y) * (pu - a.x) < 0) { inside = false; break }
                    }
                    if (inside) hullCells += ck(pu, pv)
                    iv++
                }
                iu++
            }
        }
        val cu = hf.sumOf { it[0] } / hf.size; val cv = hf.sumOf { it[1] } / hf.size

        val angles = yawWindow(c, o.footprintYawStepDeg.toDouble())
        val bestScore = DoubleArray(angles.size); val bestU = DoubleArray(angles.size); val bestV = DoubleArray(angles.size)
        val nu = ((u1 - u0) / fc).toInt() + 1; val nv = ((v1 - v0) / fc).toInt() + 1
        for ((ai, a) in angles.withIndex()) {
            val ca = cos(Math.toRadians(a)); val sa = sin(Math.toRadians(a))
            val ox = DoubleArray(hf.size) { (hf[it][0] - cu) * ca - (hf[it][1] - cv) * sa }
            val oy = DoubleArray(hf.size) { (hf[it][0] - cu) * sa + (hf[it][1] - cv) * ca }
            var best = -1
            for (iu in 0 until nu) for (iv in 0 until nv) {
                val gu = u0 + iu * fc; val gv = v0 + iv * fc
                var hit = 0
                for (k in ox.indices) {
                    val key = ck(gu + ox[k], gv + oy[k])
                    if (key in lset) hit++ else if (key in hullCells) hit -= 3
                }
                if (hit > best) { best = hit; bestU[ai] = gu; bestV[ai] = gv }
            }
            bestScore[ai] = best.toDouble() / hf.size
        }
        return peaks(angles, bestScore, o.candidates, 15.0).map { ai ->
            val a = Math.toRadians(angles[ai])
            val ca = cos(a); val sa = sin(a)
            // footprint: p' = g + R(p - c)  =>  t2 = g - R c
            val tu = bestU[ai] - (cu * ca - cv * sa); val tv = bestV[ai] - (cu * sa + cv * ca)
            val t3 = DoubleArray(3) { tu * e1[it] + tv * e2[it] }
            Rigid.translation(t3[0], t3[1], t3[2]).mul(Rigid.rotation(up, a)).mul(pre)
        }
    }
}


internal class CandidateResult(val t: Rigid, val fitness: Double, val rmse: Double, val inliers: Int, val curvatureRatio: Double) {
    var init: Rigid? = null
}
