package com.example.arruler.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The state a gated child lifecycle should be in: the parent's, except that while [paused] it is held at
 * CREATED (the AR view then sees ON_PAUSE/ON_STOP: the session pauses and rendering stops, but nothing is
 * destroyed, so the ARCore session and its anchors survive).
 */
fun gatedState(parent: Lifecycle.State, paused: Boolean): Lifecycle.State = when {
    parent == Lifecycle.State.DESTROYED -> Lifecycle.State.DESTROYED
    paused && parent.isAtLeast(Lifecycle.State.CREATED) -> Lifecycle.State.CREATED
    else -> parent
}

private class GatedLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun sync(parent: Lifecycle.State, paused: Boolean) {
        val target = gatedState(parent, paused)
        if (target != Lifecycle.State.INITIALIZED && registry.currentState != target) registry.currentState = target
    }
}

/** A lifecycle that follows the current owner's but is held paused while [paused] (see [gatedState]). */
@Composable
fun rememberGatedLifecycle(paused: Boolean): Lifecycle {
    val parent = LocalLifecycleOwner.current.lifecycle
    val owner = remember(parent) { GatedLifecycleOwner().also { it.sync(parent.currentState, paused) } }
    SideEffect { owner.sync(parent.currentState, paused) }
    DisposableEffect(owner, parent, paused) {
        val observer = LifecycleEventObserver { _, _ -> owner.sync(parent.currentState, paused) }
        parent.addObserver(observer)
        onDispose { parent.removeObserver(observer) }
    }
    return owner.lifecycle
}
