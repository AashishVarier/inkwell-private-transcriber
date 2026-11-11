package com.variableduck.inkwell.transcriber.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF0B1B33),
    primaryContainer = Color(0xFF26385A),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFE6C98F),
    onSecondary = Color(0xFF3D2E0A),
    secondaryContainer = Color(0xFF574420),
    onSecondaryContainer = Color(0xFFFFE0A6),
    background = Color(0xFF12141A),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF12141A),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF2A2E37),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainer = Color(0xFF1C1F26),
    surfaceContainerHigh = Color(0xFF242832),
    surfaceContainerHighest = Color(0xFF2E323C),
    outline = Color(0xFF8E9099),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2D4A7A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF7A5C1E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA4),
    onSecondaryContainer = Color(0xFF271900),
    background = Color(0xFFF8F6F1),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFF8F6F1),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceContainer = Color(0xFFEFEDE7),
    surfaceContainerHigh = Color(0xFFE9E7E1),
    surfaceContainerHighest = Color(0xFFE3E1DB),
    outline = Color(0xFF74777F),
)

/** Follows the system light/dark setting. */
@Composable
fun InkwellTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
