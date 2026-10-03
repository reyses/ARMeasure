package com.example.arruler.processing

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.arruler.depth.PlaneExtractor
import com.example.arruler.depth.VoxelCloud
import java.util.Random

enum class Tier { LOW, MID, HIGH }

/** Raw capability readings, no Android types so [ProfileRules] is JVM-testable. */
data class DeviceSignals(
    /** Build.VERSION.MEDIA_PERFORMANCE_CLASS (0 when unknown / below API 31). */
    val performanceClass: Int = 0,
    val totalMemBytes: Long,
    val lowRamDevice: Boolean = false,
    val cores: Int,
    val socModel: String = "",
    /** Micro-benchmark wall time in ms; null = not measured. */
    val benchMs: Long? = null
)

/**
 * Tier thresholds (documented; the benchmark ones are provisional until calibrated on devices).
 *
 *  1. isLowRamDevice, or RAM < 3 GiB, or < 4 cores            -> LOW (final)
 *  2. RAM (as reported, i.e. below the marketing figure): < 4.5 GiB LOW, < 7 GiB MID, else HIGH
 *     (a "4 GB" phone reports ~3.7, "6 GB" ~5.5, "8 GB" ~7.4)
 *  3. performance class >= 31 lifts to at least MID, >= 33 to at least HIGH
 *  4. benchmark caps: > [BENCH_MID_MAX_MS] -> LOW, > [BENCH_HIGH_MAX_MS] -> at most MID
 */
object ProfileRules {
    const val GIB = 1024L * 1024 * 1024
    const val BENCH_HIGH_MAX_MS = 600L
    const val BENCH_MID_MAX_MS = 1500L
    const val BATTERY_LOW_PCT = 20

    fun tier(s: DeviceSignals): Tier {
        if (s.lowRamDevice || s.totalMemBytes < 3 * GIB || s.cores < 4) return Tier.LOW
        var t = when {
            s.totalMemBytes < (4.5 * GIB).toLong() -> Tier.LOW
            s.totalMemBytes < 7 * GIB -> Tier.MID
            else -> Tier.HIGH
        }
        if (s.performanceClass >= 33) t = maxOf(t, Tier.HIGH)
        else if (s.performanceClass >= 31) t = maxOf(t, Tier.MID)
        val b = s.benchMs
        if (b != null) {
            t = when {
                b > BENCH_MID_MAX_MS -> Tier.LOW
                b > BENCH_HIGH_MAX_MS -> minOf(t, Tier.MID)
                else -> t
            }
        }
        return t
    }

    fun isBatteryLow(percent: Int, charging: Boolean): Boolean =
        percent in 0 until BATTERY_LOW_PCT && !charging
}

/** Fixed pure-Kotlin workload: voxel insert + RANSAC on synthetic planes. Deterministic. */
object ProfileBenchmark {
    class Result(val millis: Long, val voxels: Int, val planes: Int)

    fun synthetic(n: Int, seed: Long = 7L): FloatArray {
        val r = Random(seed)
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val u = r.nextFloat() * 4f
            val v = r.nextFloat() * 3f
            val noise = (r.nextFloat() - 0.5f) * 0.01f
            when (i % 3) {
                0 -> { out[i * 3] = u; out[i * 3 + 1] = noise; out[i * 3 + 2] = v }
                1 -> { out[i * 3] = u; out[i * 3 + 1] = 2.5f + noise; out[i * 3 + 2] = v }
                else -> { out[i * 3] = noise; out[i * 3 + 1] = v * 0.8f; out[i * 3 + 2] = u }
            }
        }
        return out
    }

    fun run(points: Int = 60_000, clock: () -> Long = System::nanoTime): Result {
        val data = synthetic(points)
        val t0 = clock()
        val cloud = VoxelCloud()
        cloud.addAll(data)
        val pts = cloud.points(1)
        val planes = PlaneExtractor().extract(pts)
        return Result((clock() - t0) / 1_000_000, cloud.count, planes.size)
    }
}

/** Android readings, summarised for routing and for the job manifest. */
data class DeviceProfile(
    val signals: DeviceSignals,
    val tier: Tier,
    val thermalStatus: Int,
    val batteryPercent: Int,
    val charging: Boolean
) {
    val batteryLow: Boolean get() = ProfileRules.isBatteryLow(batteryPercent, charging)

    fun summary() = DeviceSummary(
        tier = tier.name,
        performanceClass = signals.performanceClass,
        totalMemMb = signals.totalMemBytes / (1024 * 1024),
        cores = signals.cores,
        soc = signals.socModel,
        benchMs = signals.benchMs
    )

    companion object {
        private const val PREFS = "device_profile"

        /** Reads the device. [useBenchmark] runs (once per versionCode, ~2 s, blocking) and caches the workload. */
        fun read(context: Context, useBenchmark: Boolean = false): DeviceProfile {
            val app = context.applicationContext
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL ?: "" else ""
            val pc = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
            val bench = if (useBenchmark) benchmarkMs(app) else cachedBenchMs(app)
            val signals = DeviceSignals(pc, mi.totalMem, am.isLowRamDevice, Runtime.getRuntime().availableProcessors(), soc, bench)
            val thermal = if (Build.VERSION.SDK_INT >= 29) {
                (app.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
            } else 0
            val bat: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return DeviceProfile(signals, ProfileRules.tier(signals), thermal, pct, charging)
        }

        private fun versionCode(app: Context): Long {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }

        fun cachedBenchMs(app: Context): Long? {
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return if (p.getLong("bench_version", -1) == versionCode(app)) p.getLong("bench_ms", -1).takeIf { it >= 0 } else null
        }

        /** Cached benchmark; runs the workload (blocking, call off the main thread) when missing. */
        fun benchmarkMs(app: Context): Long {
            cachedBenchMs(app)?.let { return it }
            val ms = ProfileBenchmark.run().millis
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("bench_version", versionCode(app)).putLong("bench_ms", ms).apply()
            return ms
        }
    }
}
