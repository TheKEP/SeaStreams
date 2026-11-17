package com.seastreams.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val SeaStreamsColors = darkColorScheme(
    primary = FocusPill,
    onPrimary = OnFocusPill,
    secondary = TextSecondary,
    background = DefaultBackground,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    border = OutlineDim,
    error = ErrorRed,
)

/** [background] is the user's page colour (Settings > Background color), pure black by default. */
@Composable
fun SeaStreamsTheme(background: Color = DefaultBackground, content: @Composable () -> Unit) {
    val colors = remember(background) { SeaStreamsColors.copy(background = background) }
    MaterialTheme(colorScheme = colors) {
        // Text outside a tv Surface reads LocalContentColor; default it to on-background white.
        CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
    }
}
