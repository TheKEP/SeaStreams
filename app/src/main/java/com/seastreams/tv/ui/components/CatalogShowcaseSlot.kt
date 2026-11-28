package com.seastreams.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.CatalogTypography
import com.seastreams.tv.ui.theme.Obsidian
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.focusRing

private val SlotLogoSize = DpSize(216.dp, 68.dp)
private val SlotTitleInset = 12.dp
private val TitleAboveBarGap = 6.dp

/**
 * A catalog row's stationary 16:9 showcase slot (PRD §4), pinned at the left gutter. It is not
 * focusable: D-pad focus sits on the strip poster underneath it, and the slot shows that row's
 * last-focused [item] (backdrop, TMDB title logo (Montserrat title text only when TMDB has none), watched badge, [overlay] e.g. the Continue Watching
 * progress bar, and [trailer] for inline previews), crossfading in [SLOT_CROSSFADE_MS] when it changes.
 * The 3dp ring follows [focusRing] (the row holds focus on a media poster).
 */
@Composable
fun CatalogShowcaseSlot(
    item: MediaItem,
    focusRing: Boolean,
    modifier: Modifier = Modifier,
    /** [overlay] draws an [ArtworkProgressBar]: the title logo sits above its clearance. */
    reserveProgressBar: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    trailer: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier
            .size(ExpandedSize)
            .clip(CardShape)
            .background(SurfaceCard)
            .focusRing(focusRing, CardShape),
    ) {
        Crossfade(
            targetState = item,
            animationSpec = tween(SLOT_CROSSFADE_MS, easing = FastOutSlowInEasing),
            label = "showcaseSlot",
        ) { shown -> SlotArtwork(shown, reserveProgressBar) }
        overlay()
        WatchedBadgeIfWatched(item)
        trailer()
    }
}

@Composable
private fun SlotArtwork(item: MediaItem, reserveProgressBar: Boolean) {
    Box(Modifier.fillMaxSize().background(SurfaceCard)) {
        SizedImage(item.backdropUrl ?: item.posterUrl, ExpandedSize, contentDescription = null, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Obsidian.copy(alpha = 0.85f)),
            ),
        )
        TitleArt(
            item,
            logoSize = SlotLogoSize,
            textStyle = CatalogTypography.slotFallbackTitle,
            modifier = Modifier.align(Alignment.BottomStart).padding(
                start = SlotTitleInset,
                top = SlotTitleInset,
                end = SlotTitleInset,
                bottom = if (reserveProgressBar) ArtworkProgressClearance + TitleAboveBarGap else SlotTitleInset,
            ),
        )
    }
}
