package com.example.arruler.tandem

import com.example.arruler.processing.JobType
import com.example.arruler.processing.ObjectQuality
import com.example.arruler.processing.Tier
import kotlin.math.max
import kotlin.math.min

/**
 * What the planner knows about one phone. [thermalStatus] is PowerManager.currentThermalStatus (0 none .. 6 shutdown),
 * [thermalHeadroom] is PowerManager.getThermalHeadroom(10) (API 30+; 1.0 = throttling begins, NaN / null = unknown).
 */
data class PeerCaps(
    val name: String,
    val tier: Tier,
    val benchMs: Long? = null,
    val thermalStatus: Int = 0,
    val thermalHeadroom: Float? = null,
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val depthSupported: Boolean = true,
    /** Observed throughput against the model (1.0 = as expected), e.g. from chunk timings; multiplies the modelled speed. */
    val measuredFactor: Double = 1.0,
) {
    /** Work units per second relative to a nominal MID phone (1.0), including thermal and battery derating. */
    fun speed(): Double =
        SpeedModel.base(tier, benchMs) * SpeedModel.derate(thermalStatus, thermalHeadroom, batteryPct, charging) * measuredFactor.coerceIn(0.05, 4.0)
}

object SpeedModel {
    /** The processing/DeviceProfile micro-benchmark time (ms) of a nominal MID phone. */
    const val REFERENCE_BENCH_MS = 1000.0

    fun base(tier: Tier, benchMs: Long?): Double =
        if (benchMs != null && benchMs > 0) (REFERENCE_BENCH_MS / benchMs).coerceIn(0.2, 4.0)
        else when (tier) { Tier.LOW -> 0.5; Tier.MID -> 1.0; Tier.HIGH -> 1.8 }

    /**
     * Throttling factor in (0, 1]. Status: none/light 1.0, moderate 0.8, severe 0.55, critical 0.35, emergency+ 0.2. Headroom
     * forecast (1.0 = the throttle threshold): below 0.7 no effect, 0.7 to 1.0 falls to 0.8, above 1.0 falls further to 0.4 at 1.4.
     * The slower of the two applies; a low battery that is not charging costs another 15 %.
     */
    fun derate(status: Int, headroom: Float?, batteryPct: Int, charging: Boolean): Double {
        val s = when {
            status <= 1 -> 1.0
            status == 2 -> 0.8
            status == 3 -> 0.55
            status == 4 -> 0.35
            else -> 0.2
        }
        val h = if (headroom == null || headroom.isNaN()) 1.0 else when {
            headroom < 0.7f -> 1.0
            headroom < 1.0f -> 1.0 - (headroom - 0.7) / 0.3 * 0.2
            else -> (0.8 - (headroom - 1.0) / 0.4 * 0.4).coerceAtLeast(0.4)
        }
        val b = if (batteryPct in 0..14 && !charging) 0.85 else 1.0
        return min(s, h) * b
    }
}

/** Who leads a heterogeneous pair (e.g. a Pixel 11 Pro with a Pixel 9 Pro): the faster phone, because the leader does registration, fusion and the final mesh. */
object LeaderChoice {
    /** Ties (within 5 %) go to [a], the phone the user is holding. Returns 0 for [a], 1 for [b]. */
    fun pick(a: PeerCaps, b: PeerCaps): Int = if (b.speed() > a.speed() * 1.05) 1 else 0
}

/**
 * Provisional cost constants, work units per second at speed 1.0 (a nominal MID phone). Taken from JVM timings scaled by
 * a 4x phone factor; to be re-calibrated on the Pixel 8 Pro / 9 Pro (docs/TANDEM.md, device-only items).
 */
object CostModel {
    const val ISOLATE_POINTS_PER_S = 40_000.0
    const val REGISTER_FIXED_S = 0.8
    const val REGISTER_POINTS_PER_S = 150_000.0
    const val FUSE_POINTS_PER_S = 400_000.0
    const val MC_CELLS_PER_S = 3_000_000.0
    const val FIELD_CELLS_PER_S = 6_000_000.0
    const val TEXTURE_TRIANGLES_PER_S = 25_000.0
    const val ROOM_POINTS_PER_S = 60_000.0

    /** Nearby over Wi-Fi Direct, realistic sustained rate. */
    const val LINK_BYTES_PER_S = 8_000_000.0
    const val POINT_BYTES = 15L
    /** The occupancy field is 0 / 1 with one blurred layer: deflated it is about 1/40 of its 4 bytes per cell. */
    const val FIELD_BYTES_PER_CELL = 0.1
    const val MESH_BYTES_PER_TRIANGLE = 30L
}

/** What the user wants processed. Counts are what the capture produced; zero = that step does not exist. */
data class TandemJobSpec(
    val type: JobType,
    val quality: ObjectQuality? = null,
    val leaderPoints: Int = 0,
    /** Points the helper captured (it processes them locally); 0 = the helper did not capture (single capture, shared processing only). */
    val helperPoints: Int = 0,
    /** Marching-cubes grid cells of the merged object (nx*ny*nz). */
    val meshCells: Long = 0,
    val triangles: Int = 0,
    val textured: Boolean = false,
    /** Fraction of the triangles whose best view belongs to the helper's keyframes (see [TriangleOwnership]); 0.5 when unknown. */
    val helperTriangleShare: Double = 0.5,
)

enum class PlanBackend { PC, TANDEM, LEADER_ONLY, BLOCKED }

data class PlanStep(val name: String, val leaderMs: Long, val helperMs: Long, val transferMs: Long, val note: String = "") {
    val ms: Long get() = max(leaderMs, helperMs) + transferMs
}

data class SplitPlan(
    val backend: PlanBackend,
    val steps: List<PlanStep>,
    val expectedMs: Long,
    /** The same job with the leader doing everything alone (its own captured data only counts for the single-capture comparison). */
    val soloMs: Long,
    /** Share (0..1) of the final heavy steps (mesh, texture) the leader keeps. */
    val leaderShare: Double,
    val reason: String,
    /** MC slab weights, leader first (sums to 1); empty when the mesh is not split. */
    val slabWeights: List<Double> = emptyList(),
) {
    val speedup: Double get() = if (expectedMs > 0) soloMs.toDouble() / expectedMs else 1.0
}

/** Decides who does what. Pure. */
object WorkSplit {
    /** Below this many seconds of work the transfer and bookkeeping cost more than the split saves. */
    const val MIN_SPLIT_GAIN_MS = 300L

    fun plan(job: TandemJobSpec, leader: PeerCaps, helper: PeerCaps?, pcAvailable: Boolean): SplitPlan {
        val pcOnly = job.type == JobType.PHOTOGRAMMETRY || job.quality == ObjectQuality.DETAILED || job.quality == ObjectQuality.DETAILED_SPLAT
        if (pcOnly) {
            return if (pcAvailable) SplitPlan(PlanBackend.PC, emptyList(), 0, 0, 0.0, "DETAILED photogrammetry is sent to the paired PC; tandem does not replace it")
            else SplitPlan(PlanBackend.BLOCKED, emptyList(), 0, 0, 1.0, "DETAILED photogrammetry needs the PC and none is reachable; tandem cannot replace it")
        }
        val sL = leader.speed()
        // the yardstick: ONE phone doing all the work of the job (both captures' points), so the speed-up is the gain from sharing
        val solo = soloMs(job.copy(leaderPoints = job.leaderPoints + job.helperPoints), sL)
        if (helper == null || !helper.depthSupported && job.helperPoints > 0) {
            return SplitPlan(PlanBackend.LEADER_ONLY, emptyList(), solo, solo, 1.0, if (helper == null) "no peer" else "peer cannot capture depth")
        }
        val sH = helper.speed()
        val steps = when (job.type) {
            JobType.OBJECT_MESH -> objectSteps(job, sL, sH)
            JobType.SCAN_ANALYZE -> roomSteps(job, sL, sH)
            JobType.PHOTOGRAMMETRY -> emptyList()
        }
        val total = steps.sumOf { it.ms }
        val meshStep = steps.firstOrNull { it.name == "mesh" }
        val weights = if (meshStep != null && meshStep.helperMs > 0) listOf(sL / (sL + sH), sH / (sL + sH)) else emptyList()
        val leaderShare = if (weights.isEmpty()) 1.0 else weights[0]
        // sharing only pays when the merged result is better (dual capture) or the time drops
        val worthIt = job.helperPoints > 0 || solo - total >= MIN_SPLIT_GAIN_MS
        return if (worthIt) SplitPlan(
            PlanBackend.TANDEM, steps, total, solo, leaderShare,
            "leader %.2f vs helper %.2f work units/s".format(sL, sH), weights,
        ) else SplitPlan(PlanBackend.LEADER_ONLY, emptyList(), solo, solo, 1.0, "split saves < $MIN_SPLIT_GAIN_MS ms, running alone")
    }

    private fun ms(units: Double, perS: Double, speed: Double): Long = (units / (perS * speed) * 1000.0).toLong()
    private fun xferMs(bytes: Long): Long = (bytes / CostModel.LINK_BYTES_PER_S * 1000.0).toLong()

    /** Leader alone, processing only what it captured (plus the helper's capture is not available to it). */
    fun soloMs(job: TandemJobSpec, speed: Double): Long = when (job.type) {
        JobType.OBJECT_MESH ->
            ms(job.leaderPoints.toDouble(), CostModel.ISOLATE_POINTS_PER_S, speed) +
                ms(job.meshCells.toDouble(), CostModel.FIELD_CELLS_PER_S, speed) + ms(job.meshCells.toDouble(), CostModel.MC_CELLS_PER_S, speed) +
                (if (job.textured) ms(job.triangles.toDouble(), CostModel.TEXTURE_TRIANGLES_PER_S, speed) else 0L)
        JobType.SCAN_ANALYZE -> ms(job.leaderPoints.toDouble(), CostModel.ROOM_POINTS_PER_S, speed)
        JobType.PHOTOGRAMMETRY -> 0L
    }

    private fun objectSteps(job: TandemJobSpec, sL: Double, sH: Double): List<PlanStep> {
        val steps = ArrayList<PlanStep>()
        val hp = job.helperPoints
        steps += PlanStep(
            "isolate", ms(job.leaderPoints.toDouble(), CostModel.ISOLATE_POINTS_PER_S, sL),
            ms(hp.toDouble(), CostModel.ISOLATE_POINTS_PER_S, sH), 0, "each phone isolates and denoises its own capture",
        )
        if (hp > 0) {
            val fusedPts = job.leaderPoints + hp
            steps += PlanStep(
                "register+fuse", ms(fusedPts.toDouble(), CostModel.REGISTER_POINTS_PER_S, sL) + (CostModel.REGISTER_FIXED_S * 1000 / sL).toLong() +
                    ms(fusedPts.toDouble(), CostModel.FUSE_POINTS_PER_S, sL),
                0, xferMs(hp * CostModel.POINT_BYTES), "helper sends its isolated cloud, leader registers it and fuses the voxels",
            )
        }
        if (job.meshCells > 0) {
            val cells = job.meshCells.toDouble()
            val field = ms(cells, CostModel.FIELD_CELLS_PER_S, sL)
            val aloneMc = ms(cells, CostModel.MC_CELLS_PER_S, sL)
            val wH = sH / (sL + sH)
            val shareCells = cells * wH
            val splitL = ms(cells * (1 - wH), CostModel.MC_CELLS_PER_S, sL)
            val splitH = ms(shareCells, CostModel.MC_CELLS_PER_S, sH)
            val xfer = xferMs((shareCells * CostModel.FIELD_BYTES_PER_CELL).toLong() + (job.triangles * wH * CostModel.MESH_BYTES_PER_TRIANGLE).toLong())
            val splitTotal = max(splitL, splitH + xfer)
            if (aloneMc - splitTotal >= MIN_SPLIT_GAIN_MS) {
                steps += PlanStep("field", field, 0, 0, "leader builds the occupancy field (closing, column fill, blur)")
                steps += PlanStep("mesh", splitL, splitH + xfer, 0, "marching cubes in z slabs, 1-node overlap, welded on the leader")
            } else {
                steps += PlanStep("mesh", field + aloneMc, 0, 0, "grid too small to split, leader meshes alone")
            }
        }
        if (job.textured && job.triangles > 0) {
            val hs = job.helperTriangleShare.coerceIn(0.0, 1.0)
            val tl = ms(job.triangles * (1 - hs), CostModel.TEXTURE_TRIANGLES_PER_S, sL)
            val th = ms(job.triangles * hs, CostModel.TEXTURE_TRIANGLES_PER_S, sH)
            steps += PlanStep("texture", tl, th, xferMs((job.triangles * hs * CostModel.MESH_BYTES_PER_TRIANGLE).toLong() + 4_000_000L), "each phone bakes the triangles its own keyframes see best; atlases packed side by side")
        }
        return steps
    }

    private fun roomSteps(job: TandemJobSpec, sL: Double, sH: Double): List<PlanStep> {
        val hp = job.helperPoints
        val steps = ArrayList<PlanStep>()
        steps += PlanStep(
            "analyze", ms(job.leaderPoints.toDouble(), CostModel.ROOM_POINTS_PER_S, sL), ms(hp.toDouble(), CostModel.ROOM_POINTS_PER_S, sH), 0,
            "each phone extracts planes from its own scan",
        )
        if (hp > 0) steps += PlanStep(
            "merge", ms((job.leaderPoints + hp).toDouble(), CostModel.REGISTER_POINTS_PER_S, sL) + (CostModel.REGISTER_FIXED_S * 1000 / sL).toLong(), 0,
            xferMs(hp * CostModel.POINT_BYTES / 4), "footprint registration, merged planes, room outline",
        )
        return steps
    }
}

/**
 * Chunk scheduling with changing speeds (thermal throttling). A chunk's cost is in work units; a device's speed
 * is units per second and may change over time. Simulation only (the real run uses [shouldTake] per idle device).
 */
object ChunkScheduler {
    class Outcome(val assignment: IntArray, val makespanS: Double)

    /** Static plan: split the chunks in proportion to the speeds at t = 0, never revisit. */
    fun simulateStatic(costs: DoubleArray, speedAt: (device: Int, t: Double) -> Double): Outcome {
        val s0 = speedAt(0, 0.0); val s1 = speedAt(1, 0.0)
        val total = costs.sum()
        val target0 = total * s0 / (s0 + s1)
        val assign = IntArray(costs.size)
        var acc = 0.0
        for (i in costs.indices) { if (acc < target0) { assign[i] = 0; acc += costs[i] } else assign[i] = 1 }
        return Outcome(assign, run(costs, assign, speedAt))
    }

    /**
     * Dynamic plan: whenever a device is free it asks [shouldTake] with the CURRENT speeds; otherwise it leaves the chunk
     * for the other one. Equivalent to re-planning after every chunk.
     */
    fun simulateDynamic(costs: DoubleArray, speedAt: (device: Int, t: Double) -> Double): Outcome {
        val free = doubleArrayOf(0.0, 0.0)
        val assign = IntArray(costs.size)
        for (i in costs.indices) {
            val now = min(free[0], free[1])
            val speeds = doubleArrayOf(speedAt(0, now), speedAt(1, now))
            val d = pick(costs[i], free, speeds, now)
            assign[i] = d
            free[d] = finish(d, max(now, free[d]), costs[i], speedAt)
        }
        return Outcome(assign, max(free[0], free[1]))
    }

    /** True when [device] should start a chunk of [cost] now: it would finish no later than the other device could. */
    fun shouldTake(device: Int, cost: Double, busyUntilS: DoubleArray, speeds: DoubleArray, nowS: Double): Boolean =
        pick(cost, busyUntilS, speeds, nowS, prefer = device) == device

    private fun pick(cost: Double, free: DoubleArray, speeds: DoubleArray, now: Double, prefer: Int = 0): Int {
        val f0 = max(now, free[0]) + cost / speeds[0]
        val f1 = max(now, free[1]) + cost / speeds[1]
        return if (f0 < f1 - 1e-12) 0 else if (f1 < f0 - 1e-12) 1 else prefer
    }

    private fun finish(device: Int, start: Double, cost: Double, speedAt: (Int, Double) -> Double): Double {
        var t = start; var left = cost
        val dt = 0.001
        while (left > 0) {
            val s = speedAt(device, t)
            val done = s * dt
            if (done >= left) return t + left / s
            left -= done; t += dt
        }
        return t
    }

    private fun run(costs: DoubleArray, assign: IntArray, speedAt: (Int, Double) -> Double): Double {
        val free = doubleArrayOf(0.0, 0.0)
        for (i in costs.indices) free[assign[i]] = finish(assign[i], free[assign[i]], costs[i], speedAt)
        return max(free[0], free[1])
    }
}
