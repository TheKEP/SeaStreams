package com.seastreams.tv.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A scrollable card's position: scrolled [value] px of [maxValue], with [viewport] px visible. */
data class ScrollMetrics(val value: Int, val maxValue: Int, val viewport: Int)

/**
 * The slim vertical scrollbar of the right-hand HUD cards (Subtitles, Audio, Speed, Info): a track with a proportional
 * thumb, drawn only while the card overflows. [metrics] is read in the draw phase only, so each scroll step (the
 * D-pad's animated scroll included) redraws the thumb without recomposing or re-laying out the rows. Always lay it out
 * (fixed [SCROLLBAR_WIDTH]) so rows never reflow when it appears. Drawn in the HUD layer, so HDR White Dimming covers it.
 */
@Composable
fun PanelScrollbar(metrics: () -> ScrollMetrics?, modifier: Modifier) {
    Canvas(modifier) {
        val m = metrics() ?: return@Canvas
        val thumb = scrollbarThumb(m.value, m.maxValue, m.viewport, size.height, MIN_THUMB.toPx()) ?: return@Canvas
        val radius = CornerRadius(size.width / 2, size.width / 2)
        drawRoundRect(TRACK_COLOR, cornerRadius = radius)
        drawRoundRect(THUMB_COLOR, topLeft = Offset(0f, thumb.offset), size = Size(size.width, thumb.length), cornerRadius = radius)
    }
}

fun ScrollState.scrollMetrics(): ScrollMetrics = ScrollMetrics(value, maxValue, viewportSize)

/** A lazy card's metrics, estimated from its laid-out rows; `null` while everything fits. */
fun LazyListState.scrollMetrics(): ScrollMetrics? {
    val info = layoutInfo
    return lazyScrollMetrics(
        firstIndex = firstVisibleItemIndex,
        firstOffset = firstVisibleItemScrollOffset,
        visibleSizes = info.visibleItemsInfo.map { it.size },
        totalCount = info.totalItemsCount,
        viewport = info.viewportEndOffset - info.viewportStartOffset,
        spacing = info.mainAxisItemSpacing,
        canScrollBackward = canScrollBackward,
        canScrollForward = canScrollForward,
    )
}

/**
 * Scroll metrics for a lazy list, where only the laid-out rows are measured: every row is taken to be as tall as the
 * average laid-out row (plus [spacing]). The ends are exact: at the top the value is 0, at the bottom it equals
 * the maximum, so the thumb always touches the track's ends there. `null` when the list can't scroll at all.
 */
internal fun lazyScrollMetrics(
    firstIndex: Int,
    firstOffset: Int,
    visibleSizes: List<Int>,
    totalCount: Int,
    viewport: Int,
    spacing: Int,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): ScrollMetrics? {
    if (!canScrollBackward && !canScrollForward) return null
    if (visibleSizes.isEmpty() || totalCount <= 0 || viewport <= 0) return null
    val stride = visibleSizes.average() + spacing
    val content = (stride * totalCount - spacing).roundToInt()
    val maxValue = (content - viewport).coerceAtLeast(1)
    val value = when {
        !canScrollForward -> maxValue
        !canScrollBackward -> 0
        else -> (firstIndex * stride + firstOffset).roundToInt().coerceIn(0, maxValue - 1)
    }
    return ScrollMetrics(value, maxValue, viewport)
}

/** Where the scrollbar thumb sits on its track, in px. */
internal data class ThumbSpan(val offset: Float, val length: Float)

/**
 * The thumb for a list scrolled to [value] of [maxValue] with [viewport] px visible, on a [trackPx] track: its length
 * is the visible share of the content (at least [minThumbPx]), its offset the scrolled share of the rest. `null` when
 * nothing overflows (no scrollbar is drawn).
 */
internal fun scrollbarThumb(value: Int, maxValue: Int, viewport: Int, trackPx: Float, minThumbPx: Float): ThumbSpan? {
    if (maxValue <= 0 || viewport <= 0 || trackPx <= 0f) return null
    val content = viewport.toFloat() + maxValue
    val length = (trackPx * viewport / content).coerceIn(minOf(minThumbPx, trackPx), trackPx)
    val fraction = (value.toFloat() / maxValue).coerceIn(0f, 1f)
    return ThumbSpan(offset = (trackPx - length) * fraction, length = length)
}

internal val SCROLLBAR_WIDTH = 4.dp
internal val SCROLLBAR_GAP = 8.dp
private val MIN_THUMB = 24.dp
private val TRACK_COLOR = Color.White.copy(alpha = 0.12f)
private val THUMB_COLOR = Color.White.copy(alpha = 0.55f)
