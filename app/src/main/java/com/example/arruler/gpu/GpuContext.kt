package com.example.arruler.gpu

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Any GL / EGL failure; the kernel wrappers catch exactly this and fall back to the CPU implementation. */
class GpuException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Result of a kernel wrapper: the [value], whether the GPU produced it, wall time, and why the CPU ran instead (if it did). */
class GpuRun<T>(val value: T, val usedGpu: Boolean, val millis: Double, val fallbackReason: String?)

/** What the GL driver and the platform report. All sizes in the units named. */
data class GpuInfo(
    val glVersion: String,
    val glRenderer: String,
    val glVendor: String,
    val esMajor: Int,
    val esMinor: Int,
    val computeSupported: Boolean,
    /** MAX_COMPUTE_WORK_GROUP_INVOCATIONS (threads per work group). */
    val maxWorkGroupInvocations: Int,
    /** MAX_COMPUTE_WORK_GROUP_SIZE per axis (threads). */
    val maxWorkGroupSize: IntArray,
    /** MAX_COMPUTE_WORK_GROUP_COUNT per axis (work groups). */
    val maxWorkGroupCount: IntArray,
    /** MAX_SHADER_STORAGE_BLOCK_SIZE in bytes. */
    val maxSsboBytes: Long,
    /** android.hardware.vulkan.level (-1 absent, 0 = level 0, 1 = level 1). */
    val vulkanLevel: Int,
    /** android.hardware.vulkan.version encoded (major<<22 | minor<<12 | patch), 0 absent. */
    val vulkanVersion: Int,
) {
    val vulkanText: String get() = vulkanVersionText(vulkanLevel, vulkanVersion)

    override fun toString() =
        "GL_VERSION='$glVersion' GL_RENDERER='$glRenderer' GL_VENDOR='$glVendor' compute=$computeSupported " +
            "maxInvocations=$maxWorkGroupInvocations maxSize=${maxWorkGroupSize.toList()} maxCount=${maxWorkGroupCount.toList()} " +
            "maxSsbo=${maxSsboBytes / (1024 * 1024)} MiB vulkan=$vulkanText"

    companion object {
        /** Parses "OpenGL ES 3.1 ..." into (3, 1); (0, 0) when it does not parse. Pure. */
        fun parseEsVersion(s: String): Pair<Int, Int> {
            val m = Regex("OpenGL ES (\\d+)\\.(\\d+)").find(s) ?: return 0 to 0
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }

        fun vulkanVersionText(level: Int, version: Int): String {
            if (level < 0 && version == 0) return "none"
            val v = if (version == 0) "version n/a" else "${version ushr 22}.${(version ushr 12) and 0x3FF}.${version and 0xFFF}"
            return "level $level, $v"
        }

        /** Vulkan feature flags from the package manager (level, encoded version); (-1, 0) for absent. */
        fun probeVulkan(context: Context?): Pair<Int, Int> {
            if (context == null) return -1 to 0
            return try {
                var level = -1; var version = 0
                for (f in context.packageManager.systemAvailableFeatures) {
                    when (f.name) {
                        PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL -> level = f.version
                        PackageManager.FEATURE_VULKAN_HARDWARE_VERSION -> version = f.version
                    }
                }
                level to version
            } catch (e: RuntimeException) { -1 to 0 }
        }
    }
}

/** Packing helpers (native byte order, direct buffers). */
object GpuBuffers {
    fun alloc(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    fun floats(a: FloatArray, count: Int = a.size): ByteBuffer = alloc(count * 4).also { it.asFloatBuffer().put(a, 0, count) }
    fun ints(a: IntArray, count: Int = a.size): ByteBuffer = alloc(count * 4).also { it.asIntBuffer().put(a, 0, count) }
    /** Bytes padded with zeros to a multiple of 4 (a GLSL uint array holds them four per word). */
    fun bytesPadded(a: ByteArray): ByteBuffer = alloc((a.size + 3) and 3.inv()).also { it.put(a); it.rewind() }
    fun toFloats(b: ByteBuffer, count: Int): FloatArray = FloatArray(count).also { b.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().get(it) }
    fun toInts(b: ByteBuffer, count: Int): IntArray = IntArray(count).also { b.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(it) }
}

/**
 * Off-screen EGL context (1x1 pbuffer, OpenGL ES 3.1 when available) owned by its own [HandlerThread]. It is never the main
 * thread and never SceneView / Filament's context: EGL contexts are per thread, so nothing here can disturb the renderer.
 * All GL work goes through [call], which runs the block on the GL thread and returns its result (blocking the caller, so
 * call it from a worker thread - a call on the main thread is refused).
 *
 * Mali tuning: kernels use 64-thread work groups, std430 buffers with vec4/ivec4 elements, no shared-memory atomics, and
 * [dispatchChunked] keeps every dispatch short (default 4096 groups x 64 threads) with a glFinish between chunks so no single
 * dispatch approaches the GPU watchdog.
 */
class GpuContext private constructor() : AutoCloseable {
    private val thread = HandlerThread("gpu-compute").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private val programs = HashMap<String, Int>()
    @Volatile private var closed = false
    lateinit var info: GpuInfo
        private set

    val computeSupported: Boolean get() = !closed && info.computeSupported

    /** Runs [block] on the GL thread (directly if already on it). Exceptions propagate; a stuck driver times out. */
    fun <T> call(timeoutMs: Long = 20_000, block: () -> T): T {
        if (closed) throw GpuException("context closed")
        if (Thread.currentThread() === thread) return block()
        check(Looper.myLooper() != Looper.getMainLooper()) { "GpuContext.call must not run on the main thread" }
        val task = FutureTask { block() }
        handler.post(task)
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw (e.cause as? GpuException) ?: GpuException("GL call failed: ${e.cause}", e.cause)
        } catch (e: TimeoutException) {
            task.cancel(true)
            throw GpuException("GL call timed out after $timeoutMs ms", e)
        }
    }

    private fun setup(appContext: Context?) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw GpuException("no EGL display")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw GpuException("eglInitialize failed 0x${Integer.toHexString(EGL14.eglGetError())}")
        var config: EGLConfig? = null
        for (renderable in intArrayOf(EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_OPENGL_ES2_BIT)) {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, renderable, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
            )
            val cfgs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
            if (EGL14.eglChooseConfig(display, attribs, 0, cfgs, 0, 1, num, 0) && num[0] > 0) { config = cfgs[0]; break }
        }
        if (config == null) throw GpuException("no EGL config")
        surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) throw GpuException("pbuffer failed 0x${Integer.toHexString(EGL14.eglGetError())}")
        // ES 3.1 first, then any ES 3.x, then ES 2 (which reports compute unsupported)
        val tries = listOf(
            intArrayOf(EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 1, EGL14.EGL_NONE),
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
        )
        for (a in tries) {
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, a, 0)
            if (context != EGL14.EGL_NO_CONTEXT) break
        }
        if (context == EGL14.EGL_NO_CONTEXT) throw GpuException("eglCreateContext failed 0x${Integer.toHexString(EGL14.eglGetError())}")
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw GpuException("eglMakeCurrent failed 0x${Integer.toHexString(EGL14.eglGetError())}")
        info = probe(appContext)
    }

    private fun probe(appContext: Context?): GpuInfo {
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: ""
        val (major, minor) = GpuInfo.parseEsVersion(version)
        val compute = major * 10 + minor >= 31
        var inv = 0; var ssbo = 0L
        val size = IntArray(3); val count = IntArray(3)
        if (compute) {
            val t = IntArray(1)
            GLES20.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, t, 0); inv = t[0]
            GLES20.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE, t, 0); ssbo = t[0].toLong() and 0xFFFFFFFFL
            for (i in 0..2) {
                GLES30.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, i, t, 0); size[i] = t[0]
                GLES30.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, i, t, 0); count[i] = t[0]
            }
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
        }
        val (vl, vv) = GpuInfo.probeVulkan(appContext)
        return GpuInfo(version, renderer, vendor, major, minor, compute, inv, size, count, ssbo, vl, vv)
    }

    // ---- GL helpers (call only inside [call]) ----

    fun checkGl(where: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) throw GpuException("GL error 0x${Integer.toHexString(e)} at $where")
    }

    /** Compiles and links [source] (cached per [key]); throws with the driver's log on failure. */
    fun program(key: String, source: String): Int {
        programs[key]?.let { return it }
        val sh = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES20.glShaderSource(sh, source)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES20.glGetShaderInfoLog(sh); GLES20.glDeleteShader(sh); throw GpuException("shader '$key' compile failed: $log") }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh)
        GLES20.glLinkProgram(p)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(sh)
        if (ok[0] == 0) { val log = GLES20.glGetProgramInfoLog(p); GLES20.glDeleteProgram(p); throw GpuException("program '$key' link failed: $log") }
        programs[key] = p
        return p
    }

    /** New shader-storage buffer of [bytes] bytes, filled from [data] when given (zero-filled otherwise). */
    fun ssbo(bytes: Int, data: ByteBuffer? = null): Int {
        if (bytes <= 0) throw GpuException("empty buffer")
        if (bytes.toLong() > info.maxSsboBytes) throw GpuException("buffer $bytes B exceeds MAX_SHADER_STORAGE_BLOCK_SIZE ${info.maxSsboBytes}")
        val id = IntArray(1)
        GLES20.glGenBuffers(1, id, 0)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id[0])
        data?.rewind()
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, data, GLES20.GL_DYNAMIC_DRAW)
        checkGl("ssbo")
        return id[0]
    }

    fun bind(binding: Int, buffer: Int) {
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, buffer)
    }

    /** Reads [bytes] bytes back from [buffer] (after the barrier) into a fresh native-order direct buffer. */
    fun download(buffer: Int, bytes: Int): ByteBuffer {
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT or GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
        val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
            ?: throw GpuException("glMapBufferRange returned null")
        val out = GpuBuffers.alloc(bytes)
        mapped.order(ByteOrder.nativeOrder()).limit(bytes)
        out.put(mapped); out.rewind()
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        checkGl("download")
        return out
    }

    fun dispatch(program: Int, x: Int, y: Int = 1, z: Int = 1) {
        if (x > info.maxWorkGroupCount[0] || y > info.maxWorkGroupCount[1] || z > info.maxWorkGroupCount[2])
            throw GpuException("dispatch $x,$y,$z exceeds MAX_COMPUTE_WORK_GROUP_COUNT ${info.maxWorkGroupCount.toList()}")
        GLES20.glUseProgram(program)
        GLES31.glDispatchCompute(x, y, z)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        checkGl("dispatch")
    }

    /**
     * Dispatches [totalGroups] 1-D work groups in chunks of at most [groupsPerChunk], setting the int uniform `uBase`
     * (first group of the chunk) before each, with glFinish between chunks so no one dispatch runs long (Mali watchdog / jank).
     * The shader must compute its group index as `int(gl_WorkGroupID.x) + uBase`.
     */
    fun dispatchChunked(program: Int, totalGroups: Int, groupsPerChunk: Int = DEFAULT_GROUPS_PER_CHUNK) {
        var base = 0
        while (base < totalGroups) {
            val n = minOf(groupsPerChunk, totalGroups - base)
            uniform1i(program, "uBase", base)
            dispatch(program, n)
            base += n
            if (base < totalGroups) GLES20.glFinish()
        }
    }

    fun uniform1i(program: Int, name: String, v: Int) { GLES20.glUseProgram(program); GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), v) }
    fun uniform1f(program: Int, name: String, v: Float) { GLES20.glUseProgram(program); GLES20.glUniform1f(GLES20.glGetUniformLocation(program, name), v) }

    fun deleteBuffers(vararg ids: Int) { if (ids.isNotEmpty()) GLES20.glDeleteBuffers(ids.size, ids, 0) }

    /** Waits for the GPU (used for timing). */
    fun finish() { GLES20.glFinish() }

    override fun close() {
        if (closed) return
        val task = FutureTask {
            for (p in programs.values) GLES20.glDeleteProgram(p)
            programs.clear()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY; surface = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT
        }
        if (Thread.currentThread() === thread) task.run() else handler.post(task)
        closed = true
        try { task.get(5, TimeUnit.SECONDS) } catch (e: Exception) { /* teardown best effort */ }
        thread.quitSafely()
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        private const val EGL_CONTEXT_MAJOR_VERSION_KHR = 0x3098
        private const val EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB
        const val DEFAULT_GROUPS_PER_CHUNK = 4096

        /**
         * Creates the context and probes it, or returns null when EGL cannot be brought up. A context that came up without
         * compute (ES < 3.1) is still returned so callers can read [info]; check [computeSupported] before dispatching.
         */
        fun create(appContext: Context? = null): GpuContext? {
            val c = GpuContext()
            return try {
                val task = FutureTask { c.setup(appContext) }
                c.handler.post(task)
                task.get(20, TimeUnit.SECONDS)
                c
            } catch (e: Exception) {
                try { c.close() } catch (e2: Exception) { /* ignore */ }
                null
            }
        }
    }
}
