package com.seastreams.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.player.InlineTrailerSlot
import com.seastreams.tv.ui.theme.ManualBringIntoViewSpec
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.throttleHorizontalDpadRepeat
import kotlinx.coroutines.launch

/** Left/right page gutter shared by the Home rows, hero and nav bar. */
val RowGutter = 48.dp
private const val VIEW_MORE_KEY = "view-more"
private const val STRIP_END_KEY = "strip-end"
private val DpadKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

/**
 * Info panel expand/collapse time. Kept under [SNAP_TO_TOP_MILLIS] so that on `Down` the row
 * above has finished collapsing before the page's snap-to-top glide makes its final, exact moves.
 */
private const val INFO_ANIM_MILLIS = 130

/**
 * Horizontal catalog row (`catalog1.jpg`, `catalog2.jpg`, `home-catalogs.png`).
 *
 * - Fixed-slot layout (`CatalogStripGeometry.kt`): a stationary 16:9 [CatalogShowcaseSlot] pinned at the
 *   left gutter shows the row's last-focused item (the first by default), and beside it a strip of
 *   uniform 2:3 posters (plus `View More`) slides along; no strip item ever changes width.
 * - D-pad focus moves across the strip posters. The focused poster is glided to sit underneath the
 *   slot (laid out and focusable, but never drawn), so the next poster always sits right next to the
 *   slot, while [PreviousPosterPeek] shows a sliver of the previous poster bleeding off the left screen
 *   edge. The slot draws the focus ring while a media poster of this row holds focus; `View More` draws
 *   its own ring and leaves the slot on the last media item.
 * - The focused item's rating, year, genre, runtime and synopsis ([CatalogRowInfo]) show below the slot
 *   while the row has focus, or while a long-press menu opened from it holds focus ([holdInfo]).
 * - [items] should already be capped (`ROW_CAP` = 20 on every tab); [hasMore] appends a `View More`
 *   card at position 21 (index 20).
 * - Edge-stop-then-wrap (PRD §1): holding `Right` stops on the last card (or `View More`); a fresh
 *   `Right` press while already there wraps focus back to the first card.
 * - The strip follows focus with one continuous glide per row ([StripGlider], [glideFrame]): a tap
 *   settles in ~280ms, and a held key (paced at
 *   [com.seastreams.tv.ui.theme.HORIZONTAL_REPEAT_INTERVAL_MS]) retargets it without stopping, so the
 *   strip flows at a steady speed and decelerates onto the focused poster on release. Fresh taps are
 *   never throttled. The throttle sits on the row container, ahead of the edge-wrap cards.
 * - Re-entering the row with `Up`/`Down` restores the last-focused card (first card by default),
 *   kept in [focusedIndexState] so the page can hold it while the row is scrolled away and disposed.
 */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun ExpandingCatalogRow(
    title: String,
    items: List<MediaItem>,
    hasMore: Boolean,
    onItemClick: (index: Int) -> Unit,
    onViewMore: () -> Unit,
    modifier: Modifier = Modifier,
    /** Focuses the first card; also where re-entry lands when no card was focused before. */
    firstCard: FocusRequester = remember { FocusRequester() },
    /** Drawn over card [index]'s artwork: `expanded` = in the 16:9 slot, else on its 2:3 strip poster. */
    cardOverlay: @Composable BoxScope.(index: Int, expanded: Boolean) -> Unit = { _, _ -> },
    /** Held `OK` on card [index] (Continue Watching's action menu); `null` leaves long-press unhandled. */
    onItemLongClick: ((index: Int) -> Unit)? = null,
    /** Returning from this row's View More: scroll back to the first card, focus it, then [onFocusReturned]. */
    returnFocusToStart: Boolean = false,
    onFocusReturned: () -> Unit = {},
    /** Last-focused card; pass [RowFocusMemory.stateFor] so it outlives the row's composition. */
    focusedIndexState: MutableIntState = remember { mutableIntStateOf(0) },
    /** Attached to the active card, i.e. [focusedIndexState]'s strip card; lets the page focus it directly. */
    activeCard: FocusRequester = remember { FocusRequester() },
    /**
     * This row instance's unique id (across tabs) when hovering a card plays its trailer inside the
     * slot (Home/Movies/TV rows); `null` (Continue Watching) means no previews.
     */
    trailerRowKey: String? = null,
    /** Keeps the last-focused card's info panel shown while an overlay opened from this row holds focus. */
    holdInfo: Boolean = false,
    /** [cardOverlay] draws an [ArtworkProgressBar]; the slot lifts its title logo above it. */
    reserveProgressBar: Boolean = false,
) {
    val trailers = if (trailerRowKey != null) LocalInlineTrailers.current else null
    var focusedIndex by focusedIndexState
    var viewMoreFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = stripScrollIndex(focusedIndex, items.size, viewMoreFocused = false),
    )
    val scope = rememberCoroutineScope()
    val lastFocusedCard = activeCard
    var rowFocused by remember { mutableStateOf(false) }
    // Whether a media poster of this row holds D-pad focus (the slot's trailer may play).
    var mediaFocused by remember { mutableStateOf(false) }
    val trailerGate = remember { HeldKeyTrailerGate() }
    val shownIndex = slotIndex(focusedIndex, items.size)
    val lastIndex = rowLastIndex(items.size, hasMore)
    val wrapToStart: () -> Unit = {
        scope.launch {
            listState.scrollToItem(0)
            firstCard.requestFocusAfterFrame()
        }
    }
    LaunchedEffect(returnFocusToStart) {
        if (!returnFocusToStart) return@LaunchedEffect
        listState.scrollToItem(RETURN_CARD_INDEX)
        focusedIndex = RETURN_CARD_INDEX
        firstCard.requestFocusAfterFrame()
        // Last: clearing the request flips the key above and cancels this effect.
        onFocusReturned()
    }
    // A refresh resets the remembered card (RowFocusMemory): line the strip up with the slot again.
    LaunchedEffect(items) {
        if (!rowFocused) listState.scrollToItem(stripScrollIndex(focusedIndex, items.size, viewMoreFocused = false))
    }
    // Focus moves never scroll the strip on their own; each one retargets the row's continuous glide.
    val pitchPx = with(LocalDensity.current) { PosterPitch.toPx() }
    val glider = remember(listState, scope, pitchPx) { StripGlider(listState, scope, pitchPx) }
    val glideTo: (Int, Boolean) -> Unit = { index, viewMore -> glider.retarget(stripScrollIndex(index, items.size, viewMore)) }

    Column(
        modifier
            .onFocusChanged { rowFocused = it.hasFocus }
            // Held-key scrolling never starts a preview (PRD §4); the card that settles restarts the delay,
            // or, when a held key stopped on an edge card without moving focus, the key's release does.
            .onPreviewKeyEvent { event ->
                if (trailers == null) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    if (trailerGate.onKeyDown(event.nativeKeyEvent.repeatCount)) trailers.cancel()
                } else if (event.type == KeyEventType.KeyUp && event.key in DpadKeys && trailerGate.onKeyUp() && mediaFocused) {
                    items.getOrNull(focusedIndex)?.let { trailers.onCardFocused(InlineTrailerSlot(trailerRowKey.orEmpty(), focusedIndex, it.key), it) }
                }
                false
            }
            .throttleHorizontalDpadRepeat(),
    ) {
        RowTitle(title)
        Box(Modifier.fillMaxWidth().height(CardHeight)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides ManualBringIntoViewSpec) {
                LazyRow(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CardHeight)
                        // Posters underneath the slot (the focused one and those before it) are never drawn.
                        .drawWithContent { clipRect(left = (RowGutter + ExpandedCardWidth).toPx()) { this@drawWithContent.drawContent() } }
                        .focusProperties {
                            onEnter = {
                                val target = restoreIndex(focusedIndex, lastIndex)
                                val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == target }
                                when (rowEntry(requestedFocusDirection, visible)) {
                                    RowEntry.DEFAULT -> Unit
                                    // An unattached requester (not laid out yet) falls back to default search.
                                    RowEntry.FOCUS_LAST -> runCatching { lastFocusedCard.requestFocus() }
                                    RowEntry.SCROLL_THEN_FOCUS_LAST -> {
                                        cancelFocusChange()
                                        scope.launch {
                                            listState.scrollToItem(stripScrollIndex(target, items.size, target == items.size))
                                            if (!lastFocusedCard.requestFocusAfterFrame()) firstCard.requestFocusAfterFrame()
                                        }
                                    }
                                }
                            }
                        },
                    contentPadding = PaddingValues(start = RowGutter + StripLead, end = RowGutter),
                    horizontalArrangement = Arrangement.spacedBy(CardSpacing),
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                        val slot = InlineTrailerSlot(trailerRowKey.orEmpty(), index, item.key)
                        StripPosterCard(
                            item = item,
                            onClick = { onItemClick(index) },
                            onFocused = {
                                mediaFocused = true
                                focusedIndex = index
                                glideTo(index, false)
                                trailers?.onCardFocused(slot, item)
                                trailerGate.onCardArmed()
                            },
                            onBlurred = {
                                mediaFocused = false
                                trailers?.onFocusLeft(slot)
                            },
                            onLongClick = onItemLongClick?.let { { it(index) } },
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
                                .then(if (index == focusedIndex) Modifier.focusRequester(lastFocusedCard) else Modifier)
                                .then(if (index == lastIndex) Modifier.onDpadEdgeWrap(Key.DirectionRight, wrapToStart) else Modifier),
                            overlay = { cardOverlay(index, false) },
                        )
                    }
                    if (hasMore) {
                        item(key = VIEW_MORE_KEY) {
                            ViewMoreCard(
                                onClick = onViewMore,
                                onFocusChanged = { focused ->
                                    viewMoreFocused = focused
                                    if (focused) {
                                        focusedIndex = items.size
                                        glideTo(items.size, true)
                                    }
                                },
                                modifier = Modifier
                                    .then(if (focusedIndex == items.size) Modifier.focusRequester(lastFocusedCard) else Modifier)
                                    .onDpadEdgeWrap(Key.DirectionRight, wrapToStart),
                            )
                        }
                    }
                    // Lets the last card (or the last media card, with View More focused) reach the slot.
                    item(key = STRIP_END_KEY) { Spacer(Modifier.fillParentMaxWidth()) }
                }
            }
            PreviousPosterPeek(items, listState, overlay = { index -> cardOverlay(index, false) })
            items.getOrNull(shownIndex)?.let { item ->
                CatalogShowcaseSlot(
                    item = item,
                    focusRing = slotFocusRing(rowFocused, viewMoreFocused),
                    modifier = Modifier.padding(start = RowGutter),
                    reserveProgressBar = reserveProgressBar,
                    overlay = { cardOverlay(shownIndex, true) },
                    trailer = {
                        if (trailers != null) {
                            InlineTrailerOverlay(item, InlineTrailerSlot(trailerRowKey.orEmpty(), shownIndex, item.key), mediaFocused)
                        }
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = rowInfoVisible(rowFocused, holdInfo),
            enter = fadeIn(tween(INFO_ANIM_MILLIS)) + expandVertically(tween(INFO_ANIM_MILLIS)),
            exit = fadeOut(tween(INFO_ANIM_MILLIS)) + shrinkVertically(tween(INFO_ANIM_MILLIS)),
        ) {
            CatalogRowInfo(items.getOrNull(focusedIndex))
        }
    }
}
