package com.seastreams.tv.ui.screens.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.domain.model.StreamingProvider
import com.seastreams.tv.ui.components.ExpandingCatalogRow
import com.seastreams.tv.ui.components.FailedRow
import com.seastreams.tv.ui.components.LoadingRow
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.components.RestoreRowFocusEffect
import com.seastreams.tv.ui.components.RowFocusMemory
import com.seastreams.tv.ui.components.ReturnRowFocus
import com.seastreams.tv.ui.components.RowRestoreStep
import com.seastreams.tv.ui.components.rememberRowFocusMemory
import com.seastreams.tv.ui.components.snapItemToTop
import com.seastreams.tv.ui.components.resolveRowRestore
import com.seastreams.tv.ui.components.TabChrome
import com.seastreams.tv.ui.components.TabScaffold
import com.seastreams.tv.ui.theme.ManualBringIntoViewSpec
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.throttleVerticalDpadRepeat
import com.seastreams.tv.ui.theme.tryRequestFocus
import kotlinx.coroutines.launch

/**
 * Body shared by [MoviesScreen] and [TvShowsScreen] (PRD §5): the nav bar, then `Popular` and one
 * row per enabled service (each an [ExpandingCatalogRow] with `View More` past 20 titles), and finally the `Streaming services` toggles at the bottom of the page.
 *
 * D-pad: entering the tab keeps focus on the tab's nav pill with the page at its top;
 * `Down` from the nav bar lands on `Popular` item #1; the focused row snaps to the top; `Down` into
 * the toggles lands on the first service; `Up`/`Down` between rows (and `Up` from the toggles)
 * returns to each row's last-focused card ([RowFocusMemory], card 1 if never visited); holding `Down` stops on the toggles and a fresh `Down`
 * there scrolls to the top and lands on `Popular`'s active card ([wrapBottomBarToRow], never the nav
 * bar); `Back` goes to the tab's pill, then Home.
 */
@Composable
fun DiscoverTabScreen(
    profileId: Long,
    mediaType: MediaType,
    tab: NavTab,
    viewModel: DiscoverViewModel,
    onTabSelected: (NavTab) -> Unit,
    onSwitchProfile: () -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    onViewMore: (CatalogRowSpec) -> Unit,
    returnFocus: ReturnRowFocus = ReturnRowFocus.None,
) {
    LaunchedEffect(profileId, mediaType) { viewModel.show(profileId, mediaType) }
    val latest by viewModel.uiState.collectAsState()
    // Rows already in memory (warmed after Home loaded) are drawn on the very first frame.
    val initial = remember(profileId, mediaType) { viewModel.initialState(profileId, mediaType) }
    // The ViewModel is activity-scoped: ignore anything cached for another profile.
    val state = if (latest.profile?.id == profileId && latest.mediaType == mediaType) latest else initial
    LaunchedEffect(latest.missingProfileId) { if (latest.missingProfileId == profileId) onSwitchProfile() }
    // Kids profiles have no Movies/TV Shows tabs (PRD §1); leave if the profile became Kids meanwhile.
    LaunchedEffect(state.profile?.isKids) { if (state.profile?.isKids == true) onTabSelected(NavTab.HOME) }

    TabScaffold(state.profile, tab, onTabSelected, onSwitchProfile) { chrome ->
        DiscoverContent(state, tab, chrome, viewModel::toggleProvider, onOpenDetails, onViewMore, returnFocus)
    }
}

@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
private fun DiscoverContent(
    state: DiscoverUiState,
    tab: NavTab,
    chrome: TabChrome,
    onToggleProvider: (StreamingProvider, Boolean) -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    onViewMore: (CatalogRowSpec) -> Unit,
    returnFocus: ReturnRowFocus,
) {
    // Not saveable on purpose: the tab host would restore the last scroll position (often the bottom
    // bar) on re-entry, and a return from View More scrolls to its row itself.
    val listState = remember { LazyListState() }
    val scope = rememberCoroutineScope()
    val firstRowCard = remember { FocusRequester() }
    // Active (expanded) card of the row the bottom bar's `Down` wraps to (`Popular`).
    val wrapRowCard = remember { FocusRequester() }
    val rowFocus = rememberRowFocusMemory()
    var barFocused by remember { mutableStateOf(false) }
    val rows = visibleRows(state.rows)
    val barIndex = rows.size
    val wrapTargetRow = bottomWrapTargetRow(rows.map { it.content })
    // Never compose the bottom bar alone: it would become the list's scroll anchor (page opens at the bottom).
    val showBar = providerBarReady(state.rows)

    // Fresh entry: focus stays on the tab's nav pill and the page is held at (0, 0).
    val topEntry = rememberTopEntryState(freshEntry = entryFocusFor(returnFocus.rowId) == EntryFocus.TAB_PILL)
    SideEffect { topEntry.holdTop(listState) }
    TopEntryEffect(topEntry, profileReady = state.profile != null, rowsReady = showBar, chrome = chrome, listState = listState)
    // Back from a row's View More lands on that row's first card instead of the tab pill.
    val restoreStep = returnFocus.rowId?.let { rowId ->
        if (state.profile == null) RowRestoreStep.Wait else discoverRowRestore(rows, rowId)
    }
    RestoreRowFocusEffect(restoreStep, listState) {
        chrome.tabPill.requestFocusAfterFrame()
        returnFocus.consume()
    }
    val scrollTopFromNav: () -> Unit = {
        scope.launch {
            listState.scrollToItem(0)
            // Entered from the nav bar: if `Popular` is not ready, focus simply stays on the tab's pill.
            if (!firstRowCard.requestFocusAfterFrame()) chrome.tabPill.requestFocusAfterFrame()
        }
    }
    // Fresh `Down` on the bottom bar: scroll to the top, then focus the target row once it is attached.
    val wrapFromBar: () -> Unit = {
        wrapTargetRow?.let { target ->
            scope.launch {
                val landed = wrapBottomBarToRow(
                    targetRow = target,
                    scrollTo = listState::scrollToItem,
                    isRowLaidOut = { index -> listState.layoutInfo.visibleItemsInfo.any { it.index == index } },
                    focusRow = { wrapRowCard.tryRequestFocus() || (target == 0 && firstRowCard.tryRequestFocus()) },
                )
                // Never strand focus on an off-screen bar: bring it back into view instead.
                if (!landed) listState.scrollToItem(barIndex)
            }
        }
    }
    // Toggling a service adds/removes a row above the bar: keep the focused bar on screen.
    LaunchedEffect(barIndex, barFocused) { if (barFocused) listState.animateScrollToItem(barIndex) }

    CompositionLocalProvider(LocalBringIntoViewSpec provides ManualBringIntoViewSpec) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .throttleVerticalDpadRepeat()
                .onFocusChanged { if (it.hasFocus) topEntry.onContentFocused() }
                .focusProperties {
                    // `Down` from the nav bar always lands on `Popular` item #1 (PRD §5).
                    onEnter = {
                        val entry = navDownEntry(
                            fromAbove = requestedFocusDirection == FocusDirection.Down,
                            firstRow = rows.firstOrNull()?.content,
                            firstRowVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == 0 },
                            barShown = showBar,
                        )
                        when (entry) {
                            NavDownEntry.FIRST_CARD -> firstRowCard.requestFocus()
                            NavDownEntry.SCROLL_TOP_THEN_FIRST_CARD -> {
                                cancelFocusChange()
                                scrollTopFromNav()
                            }
                            NavDownEntry.STAY_ON_NAV -> cancelFocusChange()
                            NavDownEntry.DEFAULT -> Unit
                        }
                    }
                },
            contentPadding = PaddingValues(top = 8.dp, bottom = BOTTOM_PADDING),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(rows, key = { _, row -> row.spec.id }) { index, row ->
                Box(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { listState.snapItemToTop(index) } }) {
                    DiscoverRow(
                        row = row,
                        firstCard = if (index == 0) firstRowCard else null,
                        activeCard = if (index == wrapTargetRow) wrapRowCard else null,
                        rowFocus = rowFocus,
                        onOpenDetails = onOpenDetails,
                        onViewMore = onViewMore,
                        returnFocus = returnFocus,
                        trailerRowKey = "${tab.name}/${row.spec.id}",
                    )
                }
            }
            // Bottom-most element of the page: owns the page's `Down` edge-stop-then-wrap (PRD §1).
            if (showBar) {
                item(key = PROVIDER_BAR_KEY) {
                    ProviderToggleBar(
                        enabled = state.enabledProviders,
                        onToggle = onToggleProvider,
                        // Never the page's initial focus: only reachable once entry/return focus has settled.
                        focusable = providerBarFocusable(topEntry.pillSettled, returnPending = returnFocus.rowId != null),
                        modifier = Modifier
                            .onFocusChanged { barFocused = it.hasFocus }
                            .onDpadEdgeWrap(Key.DirectionDown, wrapFromBar),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverRow(
    row: CatalogRowState,
    firstCard: FocusRequester?,
    activeCard: FocusRequester?,
    rowFocus: RowFocusMemory,
    onOpenDetails: (MediaItem) -> Unit,
    onViewMore: (CatalogRowSpec) -> Unit,
    returnFocus: ReturnRowFocus,
    /** Unique across tabs, so the same title on a Home and a Movies row never shares a preview slot. */
    trailerRowKey: String,
) {
    val ownFirstCard = remember { FocusRequester() }
    val ownActiveCard = remember { FocusRequester() }
    when (val content = row.content) {
        RowContent.Loading -> LoadingRow(row.spec.title)
        RowContent.Failed -> FailedRow(row.spec.title)
        is RowContent.Loaded -> ExpandingCatalogRow(
            title = row.spec.title,
            items = content.row.items,
            hasMore = content.row.hasMore,
            onItemClick = { index -> onOpenDetails(content.row.items[index]) },
            onViewMore = { onViewMore(row.spec) },
            firstCard = firstCard ?: ownFirstCard,
            returnFocusToStart = row.spec.id == returnFocus.rowId,
            onFocusReturned = returnFocus.consume,
            focusedIndexState = rowFocus.stateFor(row.spec.id, content.row.items.map { it.key }),
            activeCard = activeCard ?: ownActiveCard,
            trailerRowKey = trailerRowKey,
        )
    }
}

/** Rows that loaded empty (e.g. a service with nothing in the US catalogue) are left out. */
internal fun visibleRows(rows: List<CatalogRowState>): List<CatalogRowState> =
    rows.filterNot { (it.content as? RowContent.Loaded)?.row?.items?.isEmpty() == true }

/** Where the tab's page must go to return focus to row [rowId] (back from its View More). */
internal fun discoverRowRestore(rows: List<CatalogRowState>, rowId: String): RowRestoreStep {
    val index = rows.indexOfFirst { it.spec.id == rowId }
    return resolveRowRestore(index, rows.getOrNull(index)?.content)
}

/** The provider bar is the last list item, after every catalog row. */
private const val PROVIDER_BAR_KEY = "providers"

/** Room below the bottom `Streaming services` pills so their 1.05x focus scale is never clipped. */
private val BOTTOM_PADDING = 64.dp
