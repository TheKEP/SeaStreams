package com.seastreams.tv.ui.screens.home

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
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.ui.components.ExpandingCatalogRow
import com.seastreams.tv.ui.components.HeroAutoCycleEffect
import com.seastreams.tv.ui.components.HeroCarousel
import com.seastreams.tv.ui.components.HeroCarouselState
import com.seastreams.tv.ui.components.HeroPlaceholder
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.components.TopNavBar
import com.seastreams.tv.ui.components.rememberHeroCarouselState
import com.seastreams.tv.ui.theme.ManualBringIntoViewSpec
import com.seastreams.tv.ui.theme.Obsidian
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
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
 */
@Composable
fun HomeScreen(
    profileId: Long,
    viewModel: HomeViewModel,
    onNavigate: (HomeDestination) -> Unit,
    onTabSelected: (NavTab) -> Unit,
    onSwitchProfile: () -> Unit,
) {
    LaunchedEffect(profileId) { viewModel.showProfile(profileId) }
    val latest by viewModel.uiState.collectAsState()
    // The ViewModel is activity-scoped: ignore anything still cached from a previously shown profile.
    val state = if (latest.profile?.id == profileId) latest else HomeUiState()
    LaunchedEffect(latest.missingProfileId) { if (latest.missingProfileId == profileId) onSwitchProfile() }

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

    LaunchedEffect(Unit) {
        // Home is normally revealed with the hero already loaded: land straight on `View Details`.
        heroAutoFocused = heroLoaded && heroButton.requestFocusAfterFrame()
        if (!heroAutoFocused) homePill.requestFocusAfterFrame()
    }
    LaunchedEffect(heroLoaded) {
        // First load: hand focus from the Home pill to `View Details`, unless the user already moved.
        if (heroLoaded && !heroAutoFocused && homePillFocused) heroAutoFocused = heroButton.requestFocusAfterFrame()
    }
    BackHandler {
        if (homePillFocused) onSwitchProfile() else scope.focusTop(listState, homePill)
    }

    val wrapToTop: () -> Unit = {
        scope.launch {
            listState.scrollToItem(0)
            if (!(heroLoaded && heroButton.requestFocusAfterFrame())) homePill.requestFocusAfterFrame()
        }
    }

    Column(Modifier.fillMaxSize().background(Obsidian)) {
        TopNavBar(
            profile = state.profile,
            selectedTab = NavTab.HOME,
            homeFocusRequester = homePill,
            onTabSelected = onTabSelected,
            onAvatarClick = onSwitchProfile,
            onHomeFocusChanged = { homePillFocused = it },
        )
        HomeContent(state, listState, heroCarousel, heroButton, wrapToTop, onNavigate)
    }
}

@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
private fun HomeContent(
    state: HomeUiState,
    listState: LazyListState,
    heroCarousel: HeroCarouselState,
    heroButton: FocusRequester,
    wrapToTop: () -> Unit,
    onNavigate: (HomeDestination) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sections = buildHomeSections(state)
    val lastFocusable = sections.indexOfLast { it.focusable }

    // Sections snap to the top themselves (below), so the default "scroll just enough" is disabled.
    CompositionLocalProvider(LocalBringIntoViewSpec provides ManualBringIntoViewSpec) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(sections, key = { _, section -> section.key }) { index, section ->
                Box(
                    Modifier
                        .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(index) } }
                        .then(if (index == lastFocusable) Modifier.onDpadEdgeWrap(Key.DirectionDown, wrapToTop) else Modifier),
                ) {
                    HomeSectionContent(section, state, heroCarousel, heroButton, onNavigate)
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
    onNavigate: (HomeDestination) -> Unit,
) {
    when (section) {
        HomeSection.Hero -> Box(Modifier.padding(horizontal = RowGutter)) {
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
        )
        is HomeSection.Catalog -> when (val content = section.content) {
            RowContent.Loading -> LoadingRow(section.spec.title)
            RowContent.Failed -> FailedRow(section.spec.title)
            is RowContent.Loaded -> ExpandingCatalogRow(
                title = section.spec.title,
                items = content.row.items,
                hasMore = content.row.hasMore,
                onItemClick = { index -> onNavigate(HomeDestination.Details(content.row.items[index])) },
                onViewMore = {
                    onNavigate(HomeDestination.ViewMore(section.spec.id, section.spec.title, section.spec.listUrl))
                },
            )
        }
    }
}

/** Scrolls Home back to the top and focuses the `Home` pill. */
private fun CoroutineScope.focusTop(listState: LazyListState, homePill: FocusRequester) {
    launch {
        listState.scrollToItem(0)
        homePill.requestFocusAfterFrame()
    }
}
