package com.seastreams.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val SeaStreamsColors = darkColorScheme(
    primary = FocusPill,
    onPrimary = OnFocusPill,
    secondary = TextSecondary,
    background = Obsidian,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    border = OutlineDim,
    error = ErrorRed,
)

@Composable
fun SeaStreamsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SeaStreamsColors) {
        // Text outside a tv Surface reads LocalContentColor; default it to on-background white.
        CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
    }
}
