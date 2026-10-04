package com.example.arruler.gpu

import android.content.Context
import android.os.Build
import android.os.Looper
import com.example.arruler.objscan.ObjectDenoise
import com.example.arruler.texture.Keyframe
import com.example.arruler.texture.Sharpness
import com.example.arruler.texture.SpinRoi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the in-app self-test found on one device + driver + app build: which kernels passed their equivalence check and the
 * measured cost model. Persisted as text ([encode] / [decode], pure).
 */
data class GpuVerification(
    /** [GpuGateLogic.key] of the run. */
    val key: String,
    val renderer: String,
    val glVersion: String,
    /** Kernels whose GPU output matched the CPU reference within tolerance AND ran on the GPU. */
    val passed: Set<GpuKernel>,
    /** Measured cost per kernel (units per [GpuKernel.unit]); a kernel without an entry never goes to the GPU. */
    val costs: Map<GpuKernel, KernelCost>,
    val gpuScore: Double,
    val atMs: Long,
) {
    fun encode(): String = buildString {
        append("v1\n")
        append("key=").append(esc(key)).append('\n')
        append("renderer=").append(esc(renderer)).append('\n')
        append("gl=").append(esc(glVersion)).append('\n')
        append("passed=").append(passed.joinToString(",") { it.name }).append('\n')
        append("score=").append(gpuScore).append('\n')
        append("at=").append(atMs).append('\n')
        for ((k, c) in costs) append("cost=").append(k.name).append(':').append(c.cpuNsPerUnit).append(':').append(c.gpuNsPerUnit).append(':').append(c.gpuFixedMs).append('\n')
    }

    /** The decision table for the verified kernels (unverified kernels get no row, so [GpuProfile.useGpu] is false for them). */
    fun profile(): GpuProfile = GpuProfile(passed.isNotEmpty(), gpuScore, costs.filterKeys { it in passed })

    companion object {
        private fun esc(s: String) = s.replace("\n", " ")

        /** Null when [text] is not a record written by [encode]. */
        fun decode(text: String?): GpuVerification? {
            if (text == null) return null
            val lines = text.lines()
            if (lines.firstOrNull() != "v1") return null
            val m = HashMap<String, String>()
            val costs = LinkedHashMap<GpuKernel, KernelCost>()
            for (l in lines.drop(1)) {
                val i = l.indexOf('=')
                if (i < 0) continue
                val k = l.substring(0, i)
                val v = l.substring(i + 1)
                if (k == "cost") {
                    val p = v.split(':')
                    val kernel = GpuKernel.values().firstOrNull { it.name == p.getOrNull(0) } ?: continue
                    val a = p.getOrNull(1)?.toDoubleOrNull()
                    val b = p.getOrNull(2)?.toDoubleOrNull()
                    val c = p.getOrNull(3)?.toDoubleOrNull()
                    if (a != null && b != null && c != null) costs[kernel] = KernelCost(a, b, c)
                } else m[k] = v
            }
            val key = m["key"] ?: return null
            val passed = (m["passed"] ?: "").split(',').mapNotNull { n -> GpuKernel.values().firstOrNull { it.name == n } }.toSet()
            return GpuVerification(key, m["renderer"] ?: "", m["gl"] ?: "", passed, costs, m["score"]?.toDoubleOrNull() ?: 0.0, m["at"]?.toLongOrNull() ?: 0L)
        }
    }
}

/** Pure decisions of the per-device GPU gate. */
object GpuGateLogic {
    /** Persistence key: build fingerprint, app versionCode, GL_RENDERER, GL_VERSION. */
    fun key(fingerprint: String, versionCode: Long, glRenderer: String, glVersion: String): String =
        "${devicePrefix(fingerprint, versionCode)}$glRenderer|$glVersion"

    /** The part of the key known without a GL context. */
    fun devicePrefix(fingerprint: String, versionCode: Long): String = "$fingerprint|v$versionCode|"

    /** True when [rec] was recorded on this build + app version (the GL half is checked when a context exists). */
    fun matchesDevice(rec: GpuVerification?, fingerprint: String, versionCode: Long): Boolean =
        rec != null && rec.key.startsWith(devicePrefix(fingerprint, versionCode))

    /**
     * The one rule: the GPU runs [kernel] only when the user setting is on, the record is for THIS device + driver + app
     * version ([keyMatches]), the kernel PASSED its equivalence check there, and the measured profile says the GPU is faster
     * for [size] units (null size = the caller checks the size itself). Everything else is the CPU.
     */
    fun useGpu(enabled: Boolean, rec: GpuVerification?, keyMatches: Boolean, kernel: GpuKernel, size: Long?): Boolean {
        if (!enabled || rec == null || !keyMatches) return false
        if (kernel !in rec.passed) return false
        return size == null || rec.profile().useGpu(kernel, size)
    }

    /** The Settings line. */
    fun statusLine(enabled: Boolean, rec: GpuVerification?, deviceMatches: Boolean): String {
        val total = GpuKernel.values().size
        return when {
            rec == null -> "GPU: not verified - run Diagnostics"
            !deviceMatches -> "GPU: verified on another build or driver - run Diagnostics again"
            !enabled -> "GPU: off (${rec.passed.size}/$total kernels verified on ${rec.renderer})"
            rec.passed.isEmpty() -> "GPU: 0/$total kernels passed on ${rec.renderer} - CPU is used"
            else -> "GPU: verified ${rec.passed.size}/$total kernels on ${rec.renderer}"
        }
    }
}

/**
 * Process-wide GPU gate. Before any self-test, with the setting off, on another build/driver, or for a kernel that failed or is
 * not faster at this size, every wrapper below returns exactly what the CPU implementation returns (default = CPU).
 * The GL context is created lazily on the first verified use, never on the main thread (callers there get the CPU path).
 */
object GpuGate {
    private const val PREFS = "gpu_gate"
    private const val KEY_RECORD = "record"

    @Volatile private var app: Context? = null
    @Volatile private var enabled = true
    @Volatile private var record: GpuVerification? = null
    @Volatile private var versionCode = 0L
    private val lock = Any()
    private var ctx: GpuContext? = null
    private var ctxChecked = false
    private var ctxMatches = false

    private val _record = MutableStateFlow<GpuVerification?>(null)
    /** The persisted verification (null = never run), for the Settings line. */
    val verification: StateFlow<GpuVerification?> = _record.asStateFlow()

    /** Call once at start (MainActivity.onCreate). [useGpu] = the Settings switch. */
    fun init(context: Context, useGpu: Boolean) {
        val a = context.applicationContext
        app = a
        enabled = useGpu
        versionCode = try {
            val info = a.packageManager.getPackageInfo(a.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (e: Exception) { 0L }
        record = try { GpuVerification.decode(a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORD, null)) } catch (e: Exception) { null }
        _record.value = record
        resetContext()
    }

    fun setEnabled(on: Boolean) { enabled = on }

    /** The app versionCode read by [init] (0 before). */
    fun appVersionCode(): Long = versionCode

    /** The key this device + app version would produce for a GL context reporting [glRenderer] / [glVersion]. */
    fun keyFor(glRenderer: String, glVersion: String) = GpuGateLogic.key(Build.FINGERPRINT, versionCode, glRenderer, glVersion)

    /** Stores a self-test outcome (replaces the old record, drops the cached context so the next use re-checks the driver). */
    fun record(rec: GpuVerification) {
        record = rec
        _record.value = rec
        try { app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY_RECORD, rec.encode())?.apply() } catch (e: Exception) { /* keep in memory */ }
        resetContext()
    }

    fun statusLine(enabledNow: Boolean = enabled, rec: GpuVerification? = record): String =
        GpuGateLogic.statusLine(enabledNow, rec, GpuGateLogic.matchesDevice(rec, Build.FINGERPRINT, versionCode))

    private fun resetContext() {
        synchronized(lock) {
            try { ctx?.close() } catch (e: Exception) { /* ignore */ }
            ctx = null; ctxChecked = false; ctxMatches = false
        }
    }

    /** The profile of the verified kernels, or null when nothing is verified. */
    fun profile(): GpuProfile? = record?.takeIf { it.passed.isNotEmpty() }?.profile()

    /**
     * The GL context to run [kernel] on, or null = use the CPU. [size] (units of [GpuKernel.unit]) lets the profile veto small
     * jobs; pass null when the wrapper does that itself.
     */
    fun contextFor(kernel: GpuKernel, size: Long? = null): GpuContext? {
        val rec = record
        if (!enabled || rec == null || kernel !in rec.passed) return null
        if (!GpuGateLogic.matchesDevice(rec, Build.FINGERPRINT, versionCode)) return null
        if (!GpuGateLogic.useGpu(enabled, rec, true, kernel, size)) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        return try {
            synchronized(lock) {
                if (!ctxChecked) {
                    ctxChecked = true
                    val c = GpuContext.create(app)
                    ctx = c
                    ctxMatches = c != null && c.computeSupported && keyFor(c.info.glRenderer, c.info.glVersion) == rec.key
                    if (!ctxMatches) {
                        try { c?.close() } catch (e: Exception) { /* ignore */ }
                        ctx = null
                    }
                }
                if (ctxMatches) ctx else null
            }
        } catch (e: Throwable) { null }
    }

    // ---- gated wrappers: closed gate = the CPU implementation, unchanged ----

    fun denoise(points: FloatArray, radius: Float, iterations: Int): FloatArray {
        val c = contextFor(GpuKernel.DENOISE, (points.size / 3).toLong()) ?: return ObjectDenoise.smooth(points, radius, iterations)
        return try { GpuDenoise.smooth(c, points, radius, iterations, policy = profile()).value } catch (e: Exception) { ObjectDenoise.smooth(points, radius, iterations) }
    }

    /** Atlas texel colours (0 = unfilled) from the GPU, or null = the gate is closed / the GPU declined: run the CPU loop. */
    fun textureSample(plan: TexelPlan, keyframes: List<Keyframe>): IntArray? {
        val c = contextFor(GpuKernel.TEXTURE_BAKE, plan.side.toLong() * plan.side) ?: return null
        return try { GpuTextureBake.sample(c, plan, keyframes, profile()).takeIf { it.usedGpu }?.value } catch (e: Exception) { null }
    }

    /** The GL context to hand a PlaneExtractor (null = CPU RANSAC); the extractor applies [profile] per job size. */
    fun ransacContext(): GpuContext? = contextFor(GpuKernel.RANSAC, null)

    fun sharpness(y: ByteArray, w: Int, h: Int, rowStride: Int, pixelStride: Int): Double {
        val c = contextFor(GpuKernel.SHARPNESS, w.toLong() * h) ?: return Sharpness.laplacianVariance(y, w, h, rowStride, pixelStride)
        return try { GpuImageOps.laplacianVariance(c, y, w, h, rowStride, pixelStride, policy = profile()).value }
        catch (e: Exception) { Sharpness.laplacianVariance(y, w, h, rowStride, pixelStride) }
    }

    fun roiSignature(roi: SpinRoi, y: ByteArray, rowStride: Int, pixelStride: Int): FloatArray {
        val size = (roi.x1 - roi.x0).toLong() * (roi.y1 - roi.y0)
        val c = contextFor(GpuKernel.ROI_SIGNATURE, size) ?: return roi.signature(y, rowStride, pixelStride)
        return try { GpuImageOps.roiSignature(c, roi, y, rowStride, pixelStride, profile()).value } catch (e: Exception) { roi.signature(y, rowStride, pixelStride) }
    }

    fun nv21ToArgb(nv21: ByteArray, w: Int, h: Int): IntArray {
        val c = contextFor(GpuKernel.YUV_CONVERT, w.toLong() * h) ?: return GpuImageOps.nv21ToArgbCpu(nv21, w, h)
        return try { GpuImageOps.nv21ToArgb(c, nv21, w, h, profile()).value } catch (e: Exception) { GpuImageOps.nv21ToArgbCpu(nv21, w, h) }
    }
}
