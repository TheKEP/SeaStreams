package com.seastreams.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ClickableSurfaceDefaults
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

/**
 * The app's standard focusable-pill colours: [containerColor]/[contentColor] at rest, the light
 * [FocusPill] with dark [OnFocusPill] text when D-pad focused.
 */
@Composable
fun focusPillColors(containerColor: Color = Color.Transparent, contentColor: Color = TextPrimary) =
    ClickableSurfaceDefaults.colors(
        containerColor = containerColor,
        contentColor = contentColor,
        focusedContainerColor = FocusPill,
        focusedContentColor = OnFocusPill,
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
