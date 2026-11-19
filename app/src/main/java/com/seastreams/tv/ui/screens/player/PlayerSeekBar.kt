package com.seastreams.tv.ui.screens.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.seastreams.tv.ui.theme.SeekBuffered
import com.seastreams.tv.ui.theme.SeekPlayed
import com.seastreams.tv.ui.theme.SeekThumb
import com.seastreams.tv.ui.theme.SeekThumbRing
import com.seastreams.tv.ui.theme.SeekTrack
import com.seastreams.tv.ui.theme.SeekTrackBorder

// PRD §9 Seek Bar Styling: unbuffered track, buffered range, played fill, focused thumb.

/** Seek bar segment ends as fractions of its width, always `0 <= played <= buffered <= 1`. */
data class SeekBarFractions(val played: Float, val buffered: Float)

/**
 * Clamps the playhead ([positionMs], or the scrub marker) and ExoPlayer's [bufferedMs] onto the bar.
 * The buffered tier never starts behind the playhead or runs past the end. An unknown duration
 * (`<= 0`) leaves only the track.
 */
fun seekBarFractions(positionMs: Long, bufferedMs: Long, durationMs: Long): SeekBarFractions {
    if (durationMs <= 0) return SeekBarFractions(0f, 0f)
    val duration = durationMs.toFloat()
    val played = (positionMs.coerceAtLeast(0) / duration).coerceIn(0f, 1f)
    val buffered = (bufferedMs.coerceAtLeast(0) / duration).coerceIn(played, 1f)
    return SeekBarFractions(played, buffered)
}

/**
 * Draws, all opaque so bright video can't wash them out: a dark backing outline, the slate track,
 * the blue buffered fill (0 → buffer end), the white played fill on top (its round cap ends on the
 * blue), then a white playhead tick, or the ringed thumb when [focused].
 */
fun DrawScope.drawSeekBar(fractions: SeekBarFractions, focused: Boolean) {
    val y = size.height / 2
    val stroke = if (focused) 6.dp.toPx() else 4.dp.toPx()
    fun x(fraction: Float) = size.width * fraction
    fun segment(color: Color, from: Float, to: Float, width: Float = stroke) {
        // A zero-length round-capped line would still paint a stray dot.
        if (to > from) drawLine(color, Offset(x(from), y), Offset(x(to), y), width, StrokeCap.Round)
    }
    segment(SeekTrackBorder, 0f, 1f, stroke + 2 * SEEK_BORDER.toPx())
    segment(SeekTrack, 0f, 1f)
    segment(SeekBuffered, 0f, fractions.buffered)
    segment(SeekPlayed, 0f, fractions.played)
    val head = Offset(x(fractions.played), y)
    if (focused) {
        val radius = 9.dp.toPx()
        drawCircle(SeekThumb, radius = radius, center = head)
        drawCircle(SeekThumbRing, radius = radius, center = head, style = Stroke(2.dp.toPx()))
    } else if (fractions.buffered > 0f) {
        // Unfocused playhead tick (skipped when the duration is unknown or nothing is loaded yet).
        val half = (stroke + SEEK_TICK_OVERHANG.toPx()) / 2
        val tickWidth = SEEK_TICK_WIDTH.toPx()
        val top = Offset(head.x, y - half)
        val bottom = Offset(head.x, y + half)
        drawLine(SeekTrackBorder, top, bottom, tickWidth + 2 * SEEK_BORDER.toPx(), StrokeCap.Round)
        drawLine(SeekPlayed, top, bottom, tickWidth, StrokeCap.Round)
    }
}

private val SEEK_BORDER = 1.dp
private val SEEK_TICK_WIDTH = 3.dp
private val SEEK_TICK_OVERHANG = 8.dp
