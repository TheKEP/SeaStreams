package com.seastreams.tv.ui.screens.home

import androidx.tv.material3.MaterialTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
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
import com.seastreams.tv.core.datastore.CatalogRowScrollSpeed
import com.seastreams.tv.ui.components.ActionMenu
import com.seastreams.tv.ui.components.ActionMenuRequest
import com.seastreams.tv.ui.components.HeroAutoCycleEffect
import com.seastreams.tv.ui.components.LocalFocusOrigins
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.components.RestoreRowFocusEffect
import com.seastreams.tv.ui.components.ReturnRowFocus
import com.seastreams.tv.ui.components.RowRestoreStep
import com.seastreams.tv.ui.components.TopNavBar
import com.seastreams.tv.ui.components.rememberHeroCarouselState
import com.seastreams.tv.ui.components.rememberRowFocusMemory
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Home (`home-hero.png`, `home-genres-continue.png`, `home-catalogs.png`): fixed [TopNavBar] over a
 * vertically scrolling page of hero, Continue Watching and catalog rows.
 *
 * D-pad rules (PRD §1):
 * - `Back` from any content moves focus to the nav bar's `Home` pill (scrolling to the top), never a
 *   previously focused pill such as the Settings gear; `Back` while already on `Home` leaves to the
 *   profile switcher.
 * - Edge-stop-then-wrap: holding `Down` stops on the bottom-most row; a fresh `Down` press while
 *   already there wraps to the top of the page (the hero, else the `Home` pill).
 * - The focused section snaps to the top of the viewport.
 * - Clicking a Continue Watching card plays it straight away when *Auto-play from Continue Watching*
 *   is on ([HomeDestination.Play]; Details is never opened), else opens Details.
 * - Long-press on a Continue Watching card opens its `View details` / `Remove` / `Clear progress` menu;
 *   while it is open the card's description stays shown beneath its artwork; after a removal focus
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
    val origins = LocalFocusOrigins.current
    val onContinueWatchingLongPress: (Int) -> Unit = { index ->
        state.continueWatching.getOrNull(index)?.let { item ->
            val removing = ContinueWatchingRemoval(item.continueWatchingKey, index)
            menu = continueWatchingMenu(
                item = item,
                returnFocus = continueWatchingCard,
                onViewDetails = { details ->
                    // The menu has just refocused the card; mark it so Back from Details lands there.
                    origins?.mark(continueWatchingCard)
                    onNavigate(details)
                },
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
                RowFocus(rowFocus, cards, onContinueWatchingLongPress, continueWatchingMenuOpen = menu != null), scrollSpeed,
            )
        }
        menu?.let { request -> ActionMenu(request, onClose = { menu = null }) }
    }
}

/** Scrolls Home back to the top and focuses the `Home` pill. */
private fun CoroutineScope.focusTop(listState: LazyListState, homePill: FocusRequester) {
    launch {
        listState.scrollToItem(0)
        homePill.requestFocusAfterFrame()
    }
}
