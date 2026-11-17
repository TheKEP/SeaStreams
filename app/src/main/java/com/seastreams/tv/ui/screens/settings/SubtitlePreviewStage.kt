package com.seastreams.tv.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.seastreams.tv.core.datastore.SubtitleStyle
import com.seastreams.tv.ui.components.SubtitleCueText

/** Two-line sample cue shown on the preview stage. */
const val SAMPLE_SUBTITLE = "I never thought we'd make it this far.\nNeither did I. But here we are."

/**
 * Pinned 16:9 preview (PRD §10 Live Subtitle Style Preview): a high-contrast dusk scene (bright sun,
 * pale sky, black foreground) so legibility shows against both light and dark areas, with
 * [SAMPLE_SUBTITLE] drawn by the same [SubtitleCueText] renderer the player uses.
 */
@Composable
fun SubtitlePreviewStage(style: SubtitleStyle, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
        val stageHeight = maxHeight
        Canvas(Modifier.fillMaxSize()) { drawDuskScene() }
        Box(Modifier.fillMaxSize().padding(bottom = stageHeight * CUE_BOTTOM_FRACTION), contentAlignment = Alignment.BottomCenter) {
            SubtitleCueText(SAMPLE_SUBTITLE, style, containerHeight = stageHeight)
        }
    }
}

private fun DrawScope.drawDuskScene() {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF0B1A3A),
            0.45f to Color(0xFF6A4C93),
            0.7f to Color(0xFFFFB46B),
            1f to Color(0xFFFFF1C9),
        ),
    )
    val sun = Offset(w * 0.68f, h * 0.62f)
    drawCircle(Brush.radialGradient(listOf(Color(0xCCFFF6D8), Color.Transparent), center = sun, radius = h * 0.5f), radius = h * 0.5f, center = sun)
    drawCircle(Color(0xFFFFFBEA), radius = h * 0.11f, center = sun)
    val farHills = Path().apply {
        moveTo(0f, h * 0.72f)
        cubicTo(w * 0.2f, h * 0.6f, w * 0.35f, h * 0.78f, w * 0.55f, h * 0.68f)
        cubicTo(w * 0.75f, h * 0.58f, w * 0.85f, h * 0.74f, w, h * 0.66f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(farHills, Color(0xFF2B1B3D))
    val foreground = Path().apply {
        moveTo(0f, h * 0.84f)
        cubicTo(w * 0.25f, h * 0.76f, w * 0.45f, h * 0.9f, w * 0.7f, h * 0.82f)
        cubicTo(w * 0.85f, h * 0.78f, w * 0.95f, h * 0.86f, w, h * 0.83f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(foreground, Color.Black)
}

private const val CUE_BOTTOM_FRACTION = 0.06f
