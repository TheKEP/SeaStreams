package com.seastreams.tv.ui.screens.home

import androidx.tv.material3.MaterialTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.seastreams.tv.core.datastore.CatalogRowScrollSpeed
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.ui.components.ActionMenu
import com.seastreams.tv.ui.components.ActionMenuRequest
import com.seastreams.tv.ui.components.ExpandingCatalogRow
import com.seastreams.tv.ui.components.FailedRow
import com.seastreams.tv.ui.components.HeroAutoCycleEffect
import com.seastreams.tv.ui.components.HeroCarousel
import com.seastreams.tv.ui.components.HeroCarouselState
import com.seastreams.tv.ui.components.HeroPlaceholder
import com.seastreams.tv.ui.components.LoadingRow
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.components.RestoreRowFocusEffect
import com.seastreams.tv.ui.components.RowFocusMemory
import com.seastreams.tv.ui.components.ReturnRowFocus
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.components.RowRestoreStep
import com.seastreams.tv.ui.components.TopNavBar
import com.seastreams.tv.ui.components.rememberHeroCarouselState
import com.seastreams.tv.ui.components.rememberRowFocusMemory
import com.seastreams.tv.ui.components.snapItemToTop
import com.seastreams.tv.ui.theme.ManualBringIntoViewSpec
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.throttleVerticalDpadRepeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Home (`home-hero.png`, `home-genres-continue.png`, `home-catalogs.png`): fixed [TopNavBar] over a
 * vertically scrolling page of hero, Continue Watching and catalog rows.
 *
 * D-pad rules (PRD §1):
 * - `Back` from any content moves focus to the nav bar's `Home` pill (scrolling to the top); `Back`
 *   while already on `Home` leaves to the profile switcher.
 * - Edge-stop-then-wrap: holding `Down` stops on the bottom-most row; a fresh `Down` press while
 *   already there wraps to the top of the page (the hero, else the `Home` pill).
 * - The focused section snaps to the top of the viewport.
 * - Long-press on a Continue Watching card opens its `Remove` / `Clear progress` menu; afterwards focus
 *   moves to the card that took its place ([ContinueWatchingRemovalFocusEffect]).
 */
@Composable
fun HomeScreen(
    profileId: Long,
    viewModel: HomeViewModel,
    onNavigate: (HomeDestination) -> Unit,
    onTabSelected: (NavTab) -> Unit,
    onSwitchProfile: () -> Unit,
    returnFocus: ReturnRowFocus = ReturnRowFocus.None,
    scrollSpeed: CatalogRowScrollSpeed = CatalogRowScrollSpeed.DEFAULT,
) {
    LaunchedEffect(profileId) { viewModel.showProfile(profileId) }
    val latest by viewModel.uiState.collectAsState()
    // The ViewModel is activity-scoped: ignore anything still cached from a previously shown profile.
    val state = if (latest.profile?.id == profileId) latest else HomeUiState()
    LaunchedEffect(latest.missingProfileId) { if (latest.missingProfileId == profileId) onSwitchProfile() }
    // Once Home's first load is done, warm the Movies / TV Shows tabs in the background.
    val firstLoadFinished = state.profile != null && state.hero !is HeroState.Loading
    LaunchedEffect(state.profile, firstLoadFinished) {
        val profile = state.profile
        if (firstLoadFinished && profile != null) viewModel.onFirstLoadFinished(profile)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val homePill = remember { FocusRequester() }
    val heroButton = remember { FocusRequester() }
    var homePillFocused by remember { mutableStateOf(false) }
    var heroAutoFocused by rememberSaveable { mutableStateOf(false) }
    val heroLoaded = state.hero is HeroState.Loaded
    // Hoisted out of the LazyColumn so the hero keeps cycling (and keeps its place) while it is
    // scrolled off-screen and focus is on the nav bar or the rows.
    val heroCarousel = rememberHeroCarouselState((state.hero as? HeroState.Loaded)?.items.orEmpty())
    HeroAutoCycleEffect(heroCarousel)

    val focusEntry: suspend () -> Unit = {
        // Home is normally revealed with the hero already loaded: land straight on `View Details`.
        heroAutoFocused = heroLoaded && heroButton.requestFocusAfterFrame()
        if (!heroAutoFocused) homePill.requestFocusAfterFrame()
    }
    // Back from a row's View More lands on that row's first card instead (RestoreRowFocusEffect below).
    LaunchedEffect(Unit) { if (returnFocus.rowId == null) focusEntry() }
    val sections = buildHomeSections(state)
    val rowFocus = rememberRowFocusMemory()
    val cards = remember { SectionCardRequesters() }
    var menu by remember { mutableStateOf<ActionMenuRequest?>(null) }
    var removal by remember { mutableStateOf<ContinueWatchingRemoval?>(null) }
    val continueWatchingCard = cards.of(HomeSection.ContinueWatching.KEY)
    val onContinueWatchingLongPress: (Int) -> Unit = { index ->
        state.continueWatching.getOrNull(index)?.let { item ->
            val removing = ContinueWatchingRemoval(item.continueWatchingKey, index)
            menu = continueWatchingMenu(
                item = item,
                returnFocus = continueWatchingCard,
                onRemove = {
                    removal = removing
                    viewModel.removeFromContinueWatching(profileId, item)
                },
                onClearProgress = {
                    removal = removing
                    viewModel.clearContinueWatchingProgress(profileId, item)
                },
            )
        }
    }
    val focusTopOfPage: suspend () -> Unit = {
        listState.scrollToItem(0)
        if (!(heroLoaded && heroButton.requestFocusAfterFrame())) homePill.requestFocusAfterFrame()
    }
    ContinueWatchingRemovalFocusEffect(
        removal = removal,
        sections = sections,
        moveFocus = { target ->
            val moved = when (target) {
                is RemovalFocus.Card -> {
                    val keys = state.continueWatching.map { it.media.key }
                    rowFocus.stateFor(HomeSection.ContinueWatching.KEY, keys).intValue = target.index
                    continueWatchingCard.requestFocusAfterFrame()
                }
                is RemovalFocus.RowBelow -> cards.of(target.sectionKey).requestFocusAfterFrame()
                RemovalFocus.Top -> false
            }
            if (!moved) focusTopOfPage()
        },
        onDone = { removal = null },
    )
    val restoreStep = returnFocus.rowId?.let { rowId ->
        if (state.profile == null) RowRestoreStep.Wait else homeRowRestore(sections, rowId)
    }
    RestoreRowFocusEffect(restoreStep, listState) {
        focusEntry()
        returnFocus.consume()
    }
    LaunchedEffect(heroLoaded) {
        // First load: hand focus from the Home pill to `View Details`, unless the user already moved.
        if (heroLoaded && !heroAutoFocused && homePillFocused) heroAutoFocused = heroButton.requestFocusAfterFrame()
    }
    BackHandler {
        if (homePillFocused) onSwitchProfile() else scope.focusTop(listState, homePill)
    }

    val wrapToTop: () -> Unit = { scope.launch { focusTopOfPage() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            TopNavBar(
                profile = state.profile,
                selectedTab = NavTab.HOME,
                selectedTabFocusRequester = homePill,
                onTabSelected = onTabSelected,
                onAvatarClick = onSwitchProfile,
                onSelectedTabFocusChanged = { homePillFocused = it },
            )
            HomeContent(
                state, sections, listState, heroCarousel, heroButton, wrapToTop, onNavigate, returnFocus,
                RowFocus(rowFocus, cards, onContinueWatchingLongPress), scrollSpeed,
            )
        }
        menu?.let { request -> ActionMenu(request, onClose = { menu = null }) }
    }
}

/** Home's per-row focus state, plus the Continue Watching long-press handler. */
private class RowFocus(
    val memory: RowFocusMemory,
    val cards: SectionCardRequesters,
    val onContinueWatchingLongPress: (index: Int) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
private fun HomeContent(
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
            )
        }
    }
}

/** Added to the page's 24dp section spacing below the hero. */
private val HeroBottomGap = 10.dp

/** Scrolls Home back to the top and focuses the `Home` pill. */
private fun CoroutineScope.focusTop(listState: LazyListState, homePill: FocusRequester) {
    launch {
        listState.scrollToItem(0)
        homePill.requestFocusAfterFrame()
    }
}
