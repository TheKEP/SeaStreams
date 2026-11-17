package com.seastreams.tv.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.seastreams.tv.core.datastore.CatalogRowScrollSpeed
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.ui.components.ExpandingCatalogRow
import com.seastreams.tv.ui.components.FailedRow
import com.seastreams.tv.ui.components.HeroCarousel
import com.seastreams.tv.ui.components.HeroCarouselState
import com.seastreams.tv.ui.components.HeroPlaceholder
import com.seastreams.tv.ui.components.LoadingRow
import com.seastreams.tv.ui.components.ReturnRowFocus
import com.seastreams.tv.ui.components.RowFocusMemory
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.components.snapItemToTop
import com.seastreams.tv.ui.theme.ManualBringIntoViewSpec
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.throttleVerticalDpadRepeat
import kotlinx.coroutines.launch

/** Home's per-row focus state, plus the Continue Watching long-press handler. */
internal class RowFocus(
    val memory: RowFocusMemory,
    val cards: SectionCardRequesters,
    val onContinueWatchingLongPress: (index: Int) -> Unit,
)

/** Home's scrolling page below the nav bar: each section snaps to the top when it takes focus. */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
internal fun HomeContent(
    state: HomeUiState,
    sections: List<HomeSection>,
    listState: LazyListState,
    heroCarousel: HeroCarouselState,
    heroButton: FocusRequester,
    wrapToTop: () -> Unit,
    onNavigate: (HomeDestination) -> Unit,
    returnFocus: ReturnRowFocus,
    rowFocus: RowFocus,
    scrollSpeed: CatalogRowScrollSpeed,
) {
    val scope = rememberCoroutineScope()
    val lastFocusable = sections.indexOfLast { it.focusable }

    // Sections snap to the top themselves (below), so the default "scroll just enough" is disabled.
    CompositionLocalProvider(LocalBringIntoViewSpec provides ManualBringIntoViewSpec) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().throttleVerticalDpadRepeat(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(sections, key = { _, section -> section.key }) { index, section ->
                Box(
                    Modifier
                        .onFocusChanged { if (it.hasFocus) scope.launch { listState.snapItemToTop(index) } }
                        .then(if (index == lastFocusable) Modifier.onDpadEdgeWrap(Key.DirectionDown, wrapToTop) else Modifier),
                ) {
                    HomeSectionContent(section, state, heroCarousel, heroButton, rowFocus, onNavigate, returnFocus, scrollSpeed)
                }
            }
        }
    }
}

@Composable
private fun HomeSectionContent(
    section: HomeSection,
    state: HomeUiState,
    heroCarousel: HeroCarouselState,
    heroButton: FocusRequester,
    rowFocus: RowFocus,
    onNavigate: (HomeDestination) -> Unit,
    returnFocus: ReturnRowFocus,
    scrollSpeed: CatalogRowScrollSpeed,
) {
    when (section) {
        // Extra gap under the hero so, while it is focused, only the first row's title and the top
        // ~45dp of its posters peek in at the bottom of the screen.
        HomeSection.Hero -> Box(Modifier.padding(start = RowGutter, end = RowGutter, bottom = HeroBottomGap)) {
            when (val hero = state.hero) {
                is HeroState.Loaded -> HeroCarousel(
                    state = heroCarousel,
                    items = hero.items,
                    buttonFocusRequester = heroButton,
                    onViewDetails = { onNavigate(HomeDestination.Details(it)) },
                )
                HeroState.Loading -> HeroPlaceholder(message = null)
                HeroState.Failed -> HeroPlaceholder(message = "Couldn't load featured titles")
            }
        }
        is HomeSection.ContinueWatching -> ExpandingCatalogRow(
            title = "Continue Watching",
            items = section.items.map { it.media },
            hasMore = false,
            onItemClick = { index ->
                onNavigate(continueWatchingDestination(section.items[index], state.autoPlayFromContinueWatching))
            },
            onViewMore = {},
            cardOverlay = { index, _ -> ContinueWatchingOverlay(section.items[index]) },
            onItemLongClick = rowFocus.onContinueWatchingLongPress,
            focusedIndexState = rowFocus.memory.stateFor(section.key, section.items.map { it.media.key }),
            activeCard = rowFocus.cards.of(section.key),
            scrollSpeed = scrollSpeed,
        )
        is HomeSection.Catalog -> when (val content = section.content) {
            RowContent.Loading -> LoadingRow(section.spec.title)
            RowContent.Failed -> FailedRow(section.spec.title)
            is RowContent.Loaded -> ExpandingCatalogRow(
                title = section.spec.title,
                items = content.row.items,
                hasMore = content.row.hasMore,
                onItemClick = { index -> onNavigate(HomeDestination.Details(content.row.items[index])) },
                onViewMore = { onNavigate(HomeDestination.ViewMore(section.spec)) },
                returnFocusToStart = section.spec.id == returnFocus.rowId,
                onFocusReturned = returnFocus.consume,
                focusedIndexState = rowFocus.memory.stateFor(section.key, content.row.items.map { it.key }),
                activeCard = rowFocus.cards.of(section.key),
                scrollSpeed = scrollSpeed,
                inlineTrailers = true,
            )
        }
    }
}

/** Added to the page's 24dp section spacing below the hero. */
private val HeroBottomGap = 10.dp
