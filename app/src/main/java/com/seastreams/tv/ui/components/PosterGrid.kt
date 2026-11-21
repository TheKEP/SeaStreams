package com.seastreams.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.throttleVerticalDpadRepeat
import kotlinx.coroutines.launch

/** Every poster grid is five columns wide (PRD §4, §7). */
const val POSTER_GRID_COLUMNS = 5

/**
 * Vertical 5-column grid of standard 2:3 [PosterCard]s (View More, Search, Library). There is no
 * 16:9 expansion here.
 *
 * - `Up`/`Down` stay in the focused poster's column (`index % 5`): the grid moves focus itself to
 *   `index ± 5` (or the last poster when a short bottom row lacks that column) rather than trusting
 *   spatial focus search, which falls back to column 1 when a held key outruns the scroll.
 * - Held `Up`/`Down` steps at most once per [POSTER_GRID_REPEAT_INTERVAL_MS]; fresh presses and
 *   `Left`/`Right` are never delayed. [PeekBringIntoViewSpec] keeps the next row composed.
 * - Edge-stop-then-wrap (PRD §1): holding `Down` on the bottom row stops there; a fresh `Down`
 *   press while already there scrolls back to the top and focuses the first poster.
 * - Re-entering the grid with the D-pad (e.g. from a sidebar) restores the last-focused poster by
 *   key, scrolling to it first when it is off screen, else the first. Programmatic focus requests
 *   (sort reset, letter jumps, exact Back focus) are never redirected ([posterGridEntry]).
 * - [itemModifier] lets callers attach extra focus requesters to specific posters.
 */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun PosterGrid(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    firstItemFocusRequester: FocusRequester = remember { FocusRequester() },
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onItemFocused: (index: Int) -> Unit = {},
    itemModifier: (index: Int) -> Modifier = { Modifier },
    /** `false` while more items are on their way: bottom-row `Down` then holds focus instead of wrapping. */
    wrapAtBottom: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val bottomRowStart = lastRowStart(items.size, POSTER_GRID_COLUMNS)
    val wrapToTop: () -> Unit = {
        if (wrapAtBottom) scope.launch {
            state.scrollToItem(0)
            firstItemFocusRequester.requestFocusAfterFrame()
        }
    }
    val lastFocused = remember { LastFocusedPoster() }
    val requesters = remember { HashMap<Int, FocusRequester>() }
    val requesterFor: (Int) -> FocusRequester = { index ->
        if (index == 0) firstItemFocusRequester else requesters.getOrPut(index) { FocusRequester() }
    }
    val moveFocus: (from: Int, target: Int) -> Unit = { from, target ->
        val requester = requesterFor(target)
        val composed = state.layoutInfo.visibleItemsInfo.any { it.index == target }
        if (!composed || !runCatching { requester.requestFocus() }.getOrDefault(false)) {
            scope.launch { state.focusGridItem(from, target, POSTER_GRID_COLUMNS, requester) }
        }
    }
    val peekPx = with(LocalDensity.current) { RowPeek.toPx() }
    val bringIntoViewSpec = remember(peekPx) { PeekBringIntoViewSpec(peekPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(POSTER_GRID_COLUMNS),
            state = state,
            // The throttle wraps the grid, so it sees each key before the posters' own handlers.
            modifier = modifier
                .throttleVerticalDpadRepeat(POSTER_GRID_REPEAT_INTERVAL_MS)
                .focusProperties {
                    onEnter = {
                        if (items.isNotEmpty()) {
                            val target = posterGridRestoreIndex(lastFocused.key, items)
                            val composed = state.layoutInfo.visibleItemsInfo.any { it.index == target }
                            when (posterGridEntry(requestedFocusDirection, composed)) {
                                PosterGridEntry.REQUESTED -> Unit
                                // An unattached requester falls back to ordinary focus search.
                                PosterGridEntry.RESTORE -> runCatching { requesterFor(target).requestFocus() }
                                PosterGridEntry.SCROLL_THEN_RESTORE -> {
                                    cancelFocusChange()
                                    scope.launch {
                                        state.scrollToItem(target)
                                        if (!requesterFor(target).requestFocusAfterFrame()) firstItemFocusRequester.requestFocusAfterFrame()
                                    }
                                }
                            }
                        }
                    }
                },
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            verticalArrangement = Arrangement.spacedBy(GridSpacing),
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                PosterCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onFocused = {
                        lastFocused.key = item.key
                        onItemFocused(index)
                    },
                    modifier = Modifier
                        .focusRequester(requesterFor(index))
                        .then(
                            if (index >= bottomRowStart) {
                                Modifier.onDpadEdgeWrap(Key.DirectionDown, wrapToTop)
                            } else {
                                Modifier
                            },
                        )
                        .sameColumnVerticalFocus(index, items.size, moveFocus)
                        .then(itemModifier(index)),
                )
            }
        }
    }
}

/**
 * Column-locked `Up`/`Down` for poster [index]: consumes the key and hands the same-column target to
 * [moveFocus]. Passes through when there is no target (top row `Up` leaves the grid; bottom row
 * `Down` is owned by [onDpadEdgeWrap]).
 */
private fun Modifier.sameColumnVerticalFocus(index: Int, count: Int, moveFocus: (Int, Int) -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val target = when (event.key) {
            Key.DirectionDown -> gridDownTarget(index, count, POSTER_GRID_COLUMNS)
            Key.DirectionUp -> gridUpTarget(index, POSTER_GRID_COLUMNS)
            else -> null
        } ?: return@onPreviewKeyEvent false
        moveFocus(index, target)
        true
    }

/** Non-focusable stand-in grid while posters load. */
@Composable
fun PosterGridPlaceholder(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(POSTER_GRID_COLUMNS),
        modifier = modifier,
        contentPadding = contentPadding,
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(GridSpacing),
        verticalArrangement = Arrangement.spacedBy(GridSpacing),
    ) {
        items(PLACEHOLDER_COUNT) { PosterPlaceholder() }
    }
}

/** Index of the first item on the bottom row of a [columns]-wide grid of [count] items (`0` when empty). */
fun lastRowStart(count: Int, columns: Int): Int = if (count <= 0) 0 else (count - 1) / columns * columns

private val GridSpacing = 16.dp

/** How much of the neighbouring row stays visible around the focused poster while scrolling. */
private val RowPeek = 40.dp
private const val PLACEHOLDER_COUNT = POSTER_GRID_COLUMNS * 2
