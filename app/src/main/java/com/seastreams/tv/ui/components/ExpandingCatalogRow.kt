package com.seastreams.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.PivotBringIntoViewSpec
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.trailingOverflowPx
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Left/right page gutter shared by the Home rows, hero and nav bar. */
val RowGutter = 48.dp
private val CardSpacing = 12.dp
private val InfoHeight = 112.dp
private const val VIEW_MORE_KEY = "view-more"

/**
 * Horizontal catalog row (`catalog1.jpg`, `catalog2.jpg`, `home-catalogs.png`).
 *
 * - The focused card expands from a 2:3 poster to a 16:9 backdrop and is held at the left gutter
 *   while the row scrolls beneath it; its title, rating, year, genre, runtime and synopsis show below.
 * - [items] should already be capped (30 on Home); [hasMore] appends a `View More` card.
 * - Edge-stop-then-wrap (PRD §1): holding `Right` stops on the last card (or `View More`); a fresh
 *   `Right` press while already there wraps focus back to the first card.
 * - Re-entering the row from above/below restores the last-focused card (first card by default).
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
    cardOverlay: @Composable BoxScope.(index: Int, expanded: Boolean) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstCard = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var rowFocused by remember { mutableStateOf(false) }
    val lastIndex = if (hasMore) items.size else items.lastIndex
    val wrapToStart: () -> Unit = {
        scope.launch {
            listState.scrollToItem(0)
            firstCard.requestFocusAfterFrame()
        }
    }
    val pivotPx = with(LocalDensity.current) { RowGutter.toPx() }
    val bringIntoViewSpec = remember(pivotPx) { PivotBringIntoViewSpec(pivotPx) }
    KeepFocusedCardInView(listState, focusedIndex, rowFocused)

    Column(modifier.onFocusChanged { rowFocused = it.hasFocus }) {
        RowTitle(title)
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(CardHeight).focusRestorer(firstCard),
                contentPadding = PaddingValues(horizontal = RowGutter),
                horizontalArrangement = Arrangement.spacedBy(CardSpacing),
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                    ExpandingMediaCard(
                        item = item,
                        onClick = { onItemClick(index) },
                        onFocused = { focusedIndex = index },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
                            .then(if (index == lastIndex) Modifier.onDpadEdgeWrap(Key.DirectionRight, wrapToStart) else Modifier),
                        overlay = { expanded -> cardOverlay(index, expanded) },
                    )
                }
                if (hasMore) {
                    item(key = VIEW_MORE_KEY) {
                        ViewMoreCard(
                            onClick = onViewMore,
                            onFocused = { focusedIndex = items.size },
                            modifier = Modifier.onDpadEdgeWrap(Key.DirectionRight, wrapToStart),
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = rowFocused,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            // Fixed height so moving between cards (or onto View More) never shifts the rows below.
            Box(Modifier.padding(start = RowGutter, top = 12.dp).height(InfoHeight)) {
                items.getOrNull(focusedIndex)?.let { CatalogItemInfo(it) }
            }
        }
    }
}

/**
 * The pivot [LocalBringIntoViewSpec] scrolls only when focus moves, but the focused card keeps
 * widening (poster → 16:9) for ~180 ms afterwards. Near the row's end the list is clamped
 * at max scroll, so that late growth would push the card (notably `View More`, reached by holding
 * `Right`) past the right edge. Once the focus scroll has settled, nudge the row every frame the
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
        modifier = modifier.padding(start = RowGutter, bottom = 10.dp),
    )
}

/** Title, `👍 74%  2026  Comedy  1h 47m` and a 3-line synopsis under the expanded card. */
@Composable
private fun CatalogItemInfo(item: MediaItem) {
    Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
