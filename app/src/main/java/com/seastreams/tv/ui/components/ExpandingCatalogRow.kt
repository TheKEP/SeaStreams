package com.seastreams.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.core.datastore.CatalogRowScrollSpeed
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.ManualBringIntoViewSpec
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.throttleHorizontalDpadRepeat
import com.seastreams.tv.ui.theme.trailingOverflowPx
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Left/right page gutter shared by the Home rows, hero and nav bar. */
val RowGutter = 48.dp
private val CardSpacing = 12.dp
/**
 * Title (24) + metadata line (20) + 2-line synopsis (40) + 2 × 2dp gaps. With the 36dp header,
 * 218dp artwork and [InfoTopPadding], a focused row is 348dp tall, so on a 540dp-tall screen the
 * next row's title and the top ~45–50dp of its posters still show below it.
 */
private val InfoHeight = 88.dp
private val InfoTopPadding = 6.dp
private const val VIEW_MORE_KEY = "view-more"

/**
 * Info panel expand/collapse time. Kept under [SNAP_TO_TOP_MILLIS] so that on `Down` the row
 * above has finished collapsing before the page's snap-to-top glide makes its final, exact moves.
 */
private const val INFO_ANIM_MILLIS = 130

/**
 * Horizontal catalog row (`catalog1.jpg`, `catalog2.jpg`, `home-catalogs.png`).
 *
 * - Every row always shows one 16:9 backdrop card: its last-focused card (the first card by default).
 *   It stays wide after focus leaves the row; only the card holding D-pad focus draws the focus ring.
 *   The focused card is held at the left gutter while the row scrolls beneath it, and its title,
 *   rating, year, genre, runtime and synopsis show below while the row has focus.
 * - [items] should already be capped (`ROW_CAP` = 20 on every tab); [hasMore] appends a `View More`
 *   card at position 21 (index 20).
 * - Edge-stop-then-wrap (PRD §1): holding `Right` stops on the last card (or `View More`); a fresh
 *   `Right` press while already there wraps focus back to the first card.
 * - Focus moves glide the row so the focused card lands on the left gutter, and cards widen, at the
 *   [scrollSpeed] timing (Catalog row scroll speed setting). Fast taps and held keys retarget the
 *   glide mid-flight without losing momentum ([rowGlideEasing]).
 * - Held `Left`/`Right` is paced to [com.seastreams.tv.ui.theme.HORIZONTAL_REPEAT_INTERVAL_MS]; fresh
 *   taps are never throttled. The throttle sits on the row container, ahead of the edge-wrap cards.
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
    cardOverlay: @Composable BoxScope.(index: Int, expanded: Boolean) -> Unit = { _, _ -> },
    /** Held `OK` on card [index] (Continue Watching's action menu); `null` leaves long-press unhandled. */
    onItemLongClick: ((index: Int) -> Unit)? = null,
    /** Returning from this row's View More: scroll back to the first card, focus it, then [onFocusReturned]. */
    returnFocusToStart: Boolean = false,
    onFocusReturned: () -> Unit = {},
    /** Last-focused card; pass [RowFocusMemory.stateFor] so it outlives the row's composition. */
    focusedIndexState: MutableIntState = remember { mutableIntStateOf(0) },
    /** Attached to the active (expanded) card, i.e. [focusedIndexState]'s card; lets the page focus it directly. */
    activeCard: FocusRequester = remember { FocusRequester() },
    /** Catalog row scroll speed setting: horizontal glide and card widen timing. */
    scrollSpeed: CatalogRowScrollSpeed,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var focusedIndex by focusedIndexState
    val lastFocusedCard = activeCard
    var rowFocused by remember { mutableStateOf(false) }
    var viewMoreFocused by remember { mutableStateOf(false) }
    val expandedIndex = expandedCardIndex(focusedIndex, items.size, viewMoreFocused)
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
    // Focus moves never scroll the row on their own; each one glides the card to the left gutter.
    val glideTo: (Int) -> Unit = { index ->
        scope.launch {
            val easing = rowGlideEasing(interruptsGlide = listState.isScrollInProgress)
            listState.glideItemToStart(index, scrollSpeed.scrollDurationMs, easing)
        }
    }
    KeepFocusedCardInView(listState, focusedIndex, rowFocused)

    Column(modifier.onFocusChanged { rowFocused = it.hasFocus }.throttleHorizontalDpadRepeat()) {
        RowTitle(title)
        CompositionLocalProvider(LocalBringIntoViewSpec provides ManualBringIntoViewSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(CardHeight).focusProperties {
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
                                    listState.scrollToItem(target)
                                    if (!lastFocusedCard.requestFocusAfterFrame()) firstCard.requestFocusAfterFrame()
                                }
                            }
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = RowGutter),
                horizontalArrangement = Arrangement.spacedBy(CardSpacing),
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                    ExpandingMediaCard(
                        item = item,
                        expanded = index == expandedIndex,
                        onClick = { onItemClick(index) },
                        onFocused = {
                            focusedIndex = index
                            glideTo(index)
                        },
                        onLongClick = onItemLongClick?.let { { it(index) } },
                        expandDurationMs = scrollSpeed.expandDurationMs,
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
                            .then(if (index == focusedIndex) Modifier.focusRequester(lastFocusedCard) else Modifier)
                            .then(if (index == lastIndex) Modifier.onDpadEdgeWrap(Key.DirectionRight, wrapToStart) else Modifier),
                        overlay = { expanded -> cardOverlay(index, expanded) },
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
                                    glideTo(items.size)
                                }
                            },
                            expandDurationMs = scrollSpeed.expandDurationMs,
                            modifier = Modifier
                                .then(if (focusedIndex == items.size) Modifier.focusRequester(lastFocusedCard) else Modifier)
                                .onDpadEdgeWrap(Key.DirectionRight, wrapToStart),
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = rowFocused,
            enter = fadeIn(tween(INFO_ANIM_MILLIS)) + expandVertically(tween(INFO_ANIM_MILLIS)),
            exit = fadeOut(tween(INFO_ANIM_MILLIS)) + shrinkVertically(tween(INFO_ANIM_MILLIS)),
        ) {
            // Fixed height so moving between cards (or onto View More) never shifts the rows below.
            Box(Modifier.padding(start = RowGutter, top = InfoTopPadding).height(InfoHeight)) {
                items.getOrNull(focusedIndex)?.let { CatalogItemInfo(it) }
            }
        }
    }
}

/**
 * The row glides only when focus moves, but near the row's end the glide is clamped at max scroll,
 * so a card that is still widening (poster → 16:9) could be pushed (notably `View More`, reached by
 * holding `Right`) past the right edge. Once the glide has settled, nudge the row every frame the
 * card's trailing edge overflows the end gutter.
 */
@Composable
private fun KeepFocusedCardInView(listState: LazyListState, focusedIndex: Int, rowFocused: Boolean) {
    LaunchedEffect(listState, focusedIndex, rowFocused) {
        if (!rowFocused) return@LaunchedEffect
        snapshotFlow {
            if (listState.isScrollInProgress) return@snapshotFlow 0
            val info = listState.layoutInfo
            val card = info.visibleItemsInfo.firstOrNull { it.index == focusedIndex } ?: return@snapshotFlow 0
            trailingOverflowPx(card.offset, card.size, info.viewportEndOffset, info.afterContentPadding)
        }
            .filter { it > 0 }
            .collect { overflow -> listState.scrollBy(overflow.toFloat()) }
    }
}

@Composable
fun RowTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = RowGutter, bottom = 8.dp),
    )
}

/** Title, `👍 74%  2026  Comedy  1h 47m` and a 2-line synopsis under the expanded card. */
@Composable
private fun CatalogItemInfo(item: MediaItem) {
    Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MediaMetadataLine(item, showCertification = false)
        if (item.overview.isNotBlank()) {
            Text(
                item.overview,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
