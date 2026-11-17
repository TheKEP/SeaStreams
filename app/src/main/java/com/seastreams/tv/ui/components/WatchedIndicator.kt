package com.seastreams.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.playback.isCompleted
import com.seastreams.tv.ui.theme.FocusPill
import kotlin.math.roundToLong

/**
 * What an artwork tile shows for its watch state (PRD §1 Watched Artwork Indicator): nothing, a
 * bottom bar for an in-progress title, or the top-right checkmark once watched. Never both.
 */
sealed interface ArtworkProgress {
    data object None : ArtworkProgress

    /** Started but under the `>= 90%` completion rule; [fraction] is in `(0, 1)`. */
    data class InProgress(val fraction: Float) : ArtworkProgress

    data object Watched : ArtworkProgress
}

/** Classifies a saved position: `>= 90%` (or position = duration from `Mark watched`) is [ArtworkProgress.Watched]. */
fun artworkProgress(positionMs: Long, durationMs: Long): ArtworkProgress = when {
    durationMs <= 0 || positionMs <= 0 -> ArtworkProgress.None
    isCompleted(positionMs, durationMs) -> ArtworkProgress.Watched
    else -> ArtworkProgress.InProgress((positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
}

/** Same rule for a stored 0–1 fraction (Continue Watching cards). */
fun artworkProgress(fraction: Float): ArtworkProgress =
    artworkProgress((fraction.coerceIn(0f, 1f) * FRACTION_SCALE).roundToLong(), FRACTION_SCALE)

/**
 * Titles the active profile has finished, provided once by the profile shell so every catalog card
 * and grid poster can draw [WatchedCheckmarkBadge] without each screen re-querying watch progress.
 */
val LocalWatchedTitles = compositionLocalOf<Set<MediaRef>> { emptySet() }

/** Draws the checkmark on [item]'s artwork when the active profile has watched it. */
@Composable
fun BoxScope.WatchedBadgeIfWatched(item: MediaItem) {
    if (item.ref in LocalWatchedTitles.current) WatchedCheckmarkBadge(Modifier.align(Alignment.TopEnd))
}

/** Crisp white check in a dark translucent circle; callers align it to the artwork's top-right corner. */
@Composable
fun WatchedCheckmarkBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(BadgeInset)
            .shadow(BadgeShadow, CircleShape)
            .size(BadgeSize)
            .background(Color.Black.copy(alpha = BADGE_ALPHA), CircleShape)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = BORDER_ALPHA)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, contentDescription = "Watched", tint = Color.White, modifier = Modifier.size(IconSize))
    }
}

/** Thin bottom bar for an in-progress title only; finished titles get [WatchedCheckmarkBadge] instead. */
@Composable
fun ArtworkProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(BarHeight).background(Color.White.copy(alpha = TRACK_ALPHA))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(FocusPill))
    }
}

/** Bottom bar or top-right checkmark for [progress] on the enclosing artwork box. */
@Composable
fun BoxScope.ArtworkProgressIndicator(progress: ArtworkProgress) {
    when (progress) {
        ArtworkProgress.None -> Unit
        is ArtworkProgress.InProgress -> ArtworkProgressBar(progress.fraction, Modifier.align(Alignment.BottomStart))
        ArtworkProgress.Watched -> WatchedCheckmarkBadge(Modifier.align(Alignment.TopEnd))
    }
}

private const val FRACTION_SCALE = 10_000L
private const val BADGE_ALPHA = 0.7f
private const val BORDER_ALPHA = 0.35f
private const val TRACK_ALPHA = 0.3f
internal val BadgeInset = 7.dp
internal val BadgeSize = 26.dp
private val IconSize = 18.dp
private val BadgeShadow = 3.dp
private val BarHeight = 4.dp
