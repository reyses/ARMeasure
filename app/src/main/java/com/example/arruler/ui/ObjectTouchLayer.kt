package com.example.arruler.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A transparent full-screen layer over the AR view for the box placement: a tap is 'tap the object', a drag moves
 * the box. Coordinates are view pixels of the AR view, so the layer must not sit inside the system-bar padding.
 */
@Composable
fun ObjectTouchLayer(
    onTap: (x: Float, y: Float) -> Unit,
    onDragStart: (x: Float, y: Float) -> Unit,
    onDrag: (x: Float, y: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Box(
        modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { tap(it.x, it.y) }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start(it.x, it.y) },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { change, _ ->
                    change.consume()
                    drag(change.position.x, change.position.y)
                }
            },
    )
}
