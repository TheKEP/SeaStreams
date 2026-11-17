package com.seastreams.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.seastreams.tv.core.datastore.SubtitleEdgeType
import com.seastreams.tv.core.datastore.SubtitleStyle

/**
 * Draws one subtitle cue with a [SubtitleStyle] — the single renderer shared by the player overlay and
 * the Settings preview stage, so the preview shows exactly what playback will.
 *
 * Text size scales with [containerHeight] (the video's height on screen): at `fontScale = 1` a line is
 * [BASE_HEIGHT_FRACTION] of it, so a 16:9 preview card and a full screen look the same, just smaller.
 */
@Composable
fun SubtitleCueText(text: String, style: SubtitleStyle, containerHeight: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val fontPx = with(density) { containerHeight.toPx() } * BASE_HEIGHT_FRACTION * style.fontScale
    val fontSize = with(density) { fontPx.toSp() }
    val base = TextStyle(
        fontSize = fontSize,
        lineHeight = fontSize * LINE_HEIGHT,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Medium,
        fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = TextAlign.Center,
    )
    val fill = Color(style.textColor)
    val boxColor = Color.Black.copy(alpha = style.backgroundOpacity.coerceIn(0f, 1f))
    Box(
        modifier
            .background(boxColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (style.edgeType) {
            SubtitleEdgeType.OUTLINE -> {
                BasicText(
                    text,
                    style = base.copy(color = Color.Black, drawStyle = Stroke(width = fontPx * OUTLINE_FRACTION, join = StrokeJoin.Round)),
                )
                BasicText(text, style = base.copy(color = fill))
            }
            SubtitleEdgeType.DROP_SHADOW -> BasicText(
                text,
                style = base.copy(
                    color = fill,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.9f),
                        offset = Offset(fontPx * SHADOW_OFFSET_FRACTION, fontPx * SHADOW_OFFSET_FRACTION),
                        blurRadius = fontPx * SHADOW_BLUR_FRACTION,
                    ),
                ),
            )
            SubtitleEdgeType.NONE -> BasicText(text, style = base.copy(color = fill))
        }
    }
}

/** Line height at 100% size, as a fraction of the video height (about 57px on a 1080p screen). */
const val BASE_HEIGHT_FRACTION = 0.053f
private const val LINE_HEIGHT = 1.2f
private const val OUTLINE_FRACTION = 0.14f
private const val SHADOW_OFFSET_FRACTION = 0.06f
private const val SHADOW_BLUR_FRACTION = 0.14f
