package com.example.arruler.ar

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log

/** What [ArGlContext.check] does this time. Pure, so the decision is unit-tested. */
enum class GlAction { NONE, CAPTURE, RESTORE }

object GlGuard {
    /**
     * [captured]: the AR view's context was recorded; [hasCurrent]: the main thread has a context; [currentIsCaptured]: it
     * is the recorded one. Capture the first context seen, restore when another one took the main thread over.
     */
    fun action(captured: Boolean, hasCurrent: Boolean, currentIsCaptured: Boolean): GlAction = when {
        !captured -> if (hasCurrent) GlAction.CAPTURE else GlAction.NONE
        currentIsCaptured -> GlAction.NONE
        else -> GlAction.RESTORE
    }
}

/**
 * The AR view's EGL context on the main thread. ARCore's `Session.update()` (main thread) updates the camera texture in
 * the context current there, which SceneView created for the AR view. Any other SceneView on the main thread (the 3D
 * viewer) makes its own context current and leaves it so; [check] puts the AR one back before the AR view runs again.
 */
class ArGlContext {
    private var display: EGLDisplay? = null
    private var draw: EGLSurface? = null
    private var read: EGLSurface? = null
    private var context: EGLContext? = null

    /** Main thread, while the AR view is live. */
    fun check() {
        val current = EGL14.eglGetCurrentContext()
        val has = current != EGL14.EGL_NO_CONTEXT
        when (GlGuard.action(context != null, has, current == context)) {
            GlAction.NONE -> {}
            GlAction.CAPTURE -> {
                display = EGL14.eglGetCurrentDisplay()
                draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                context = current
            }
            GlAction.RESTORE -> {
                val ok = EGL14.eglMakeCurrent(display, draw, read, context)
                Log.w(TAG, "AR EGL context was not current on the main thread; restored: $ok")
            }
        }
    }

    private companion object {
        const val TAG = "ArGlContext"
    }
}
