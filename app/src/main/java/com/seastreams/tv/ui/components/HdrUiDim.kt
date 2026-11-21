package com.seastreams.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * HDR White Dimming (PRD §10): dims everything this element draws to [HDR_UI_WHITE_LEVEL] of its
 * brightness. The content renders into its own offscreen layer and a black wash is drawn `SrcAtop`,
 * so only pixels the UI actually painted get darker: transparent areas stay transparent and the
 * video behind them is never dimmed. Draw-only, so focus and key handling are unaffected.
 */
fun Modifier.hdrUiDim(enabled: Boolean): Modifier = if (!enabled) {
    this
} else {
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(HdrDimWash, blendMode = BlendMode.SrcAtop)
        }
}

/** A UI colour channel's level once dimmed (`1.0` white → [HDR_UI_WHITE_LEVEL]). */
fun hdrDimmedLevel(level: Float): Float = level * HDR_UI_WHITE_LEVEL

/** Dimmed UI whites keep 75% of their brightness. */
const val HDR_UI_WHITE_LEVEL = 0.75f

private val HdrDimWash = Color.Black.copy(alpha = 1f - HDR_UI_WHITE_LEVEL)
