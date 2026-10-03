package com.example.arruler.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF007AFF),
    secondary = Color(0xFF34C759),
    tertiary = Color(0xFFB25E00),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    secondary = Color(0xFF30D158),
    tertiary = Color(0xFFFFB454),
)

/** Material3 theme that follows the system light/dark setting (the AR overlay uses its own glass colours). */
@Composable
fun ArRulerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
