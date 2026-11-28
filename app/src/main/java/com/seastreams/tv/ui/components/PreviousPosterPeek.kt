package com.seastreams.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.SurfaceCard
import kotlin.math.floor

/**
 * The left-edge previous-poster peek of a fixed-slot catalog row (PRD §4, `trending_movies_catalog_font.jpg`):
 * a draw-only layer that shows the last [PeekVisibleWidth] of poster `i - 1` bleeding off the screen's
 * left edge while poster `i` is under the slot. It follows the strip's live scroll position from
 * [listState] (read in the layout phase, so gliding never recomposes it), so mid-glide poster `i`
 * slides out from behind the slot as `i - 1` slides away. Clipped at [PeekClipRight], a gap short of the
 * slot, so it never covers the slot or its focus ring. Not focusable and not clickable: D-pad focus
 * stays on the strip's real posters. Nothing shows while item #1 is under the slot.
 */
@Composable
internal fun PreviousPosterPeek(
    items: List<MediaItem>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(index: Int) -> Unit = {},
) {
    val pitchPx = with(LocalDensity.current) { PosterPitch.toPx() }
    val position = { stripPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, pitchPx) }
    val base by remember(listState, pitchPx) { derivedStateOf { floor(position()).toInt() } }
    Box(
        modifier
            .fillMaxWidth()
            .height(CardHeight)
            .drawWithContent { clipRect(right = PeekClipRight.toPx()) { this@drawWithContent.drawContent() } },
    ) {
        for (index in peekItems(base, items.size)) {
            val item = items[index]
            key(item.key) {
                Box(
                    Modifier
                        .offset { IntOffset(peekPosterX(index, position()).roundToPx(), 0) }
                        .size(PosterSize)
                        .clip(CardShape)
                        .background(SurfaceCard),
                ) {
                    (item.posterUrl ?: item.backdropUrl)?.let { url ->
                        SizedImage(url, PosterSize, contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                    overlay(index)
                    WatchedBadgeIfWatched(item)
                }
            }
        }
    }
}
