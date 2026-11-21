package com.seastreams.tv.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.ui.components.HoverSidebar
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.components.PosterGrid
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.components.TabScaffold
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus

/**
 * Library (PRD §7): hover-activated `All` / `Movies` / `TV Shows` filter on the left, Sort (saved
 * per profile) and Genre dropdowns on top, a 5-column 2:3 poster grid, and the A–Z quick-jump strip
 * on the right edge.
 *
 * Opens with `All` selected and focused, the grid in the profile's last-used sort order.
 *
 * The sidebar highlight follows focus at once; the grid switches once focus rests on a filter
 * ([FILTER_SETTLE_MILLIS]), or at once when focus leaves the sidebar ([SidebarGridEntry]).
 *
 * The filter sidebar and the A–Z bar wrap into each other both ways ([LibraryWrapMemory]): a fresh
 * `Left` on a filter lands on the bar's last focused letter (`#` by default), a fresh `Right` on the
 * bar returns to the last focused filter. Held `Left`/`Right` stops on the edge (PRD §1, §7).
 */
@Composable
fun LibraryScreen(
    profileId: Long,
    viewModel: LibraryViewModel,
    onTabSelected: (NavTab) -> Unit,
    onSwitchProfile: () -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
) {
    LaunchedEffect(profileId) { viewModel.open(profileId) }
    val latest by viewModel.uiState.collectAsState()
    val sidebarFilter by viewModel.sidebarFilter.collectAsState()
    val state = if (latest.profile?.id == profileId) latest else LibraryUiState()
    LaunchedEffect(latest.missingProfileId) { if (latest.missingProfileId == profileId) onSwitchProfile() }

    // `All` is the sidebar's first entry: focusing it directly can't hover-select a stale filter.
    val allFilter = remember { FocusRequester() }
    LaunchedEffect(Unit) { allFilter.requestFocusAfterFrame() }
    val scope = rememberCoroutineScope()
    val memory = remember { LibraryWrapMemory() }
    val letterBar = remember { LetterBarFocus() }
    val gridEntry = remember { SidebarGridEntry() }
    val filterRequesters = remember { LibraryFilter.entries.associateWith { FocusRequester() } }
    // The bar only exists with titles; without it `Left` on a filter stays ordinary focus search.
    val barShown = state.view.entries.isNotEmpty()
    val wrapToFilters: () -> Unit = remember(viewModel) {
        { filterRequesters[memory.filterTarget(viewModel.sidebarFilter.value)]?.tryRequestFocus() }
    }
    val entryModifier: (Int, LibraryFilter) -> Modifier = remember(viewModel, barShown) {
        { _, filter ->
            Modifier
                .then(filterRequesters[filter]?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { if (it.isFocused) memory.onFilterFocused(filter) }
                .then(gridEntry.rightIntoGrid({ viewModel.sidebarFilter.value }, viewModel::commitPendingFilter))
                .then(
                    if (barShown) {
                        Modifier.onDpadEdgeWrap(Key.DirectionLeft) {
                            viewModel.commitPendingFilter()
                            letterBar.wrapTo(scope, memory.letterTarget())
                        }
                    } else {
                        Modifier
                    },
                )
        }
    }

    TabScaffold(state.profile, NavTab.LIBRARY, onTabSelected, onSwitchProfile) { _ ->
        Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
            HoverSidebar(
                entries = LibraryFilter.entries,
                selected = sidebarFilter,
                label = { it.label },
                onSelect = viewModel::selectFilter,
                firstEntry = allFilter,
                entryModifier = entryModifier,
                modifier = Modifier
                    .width(SIDEBAR_WIDTH)
                    .fillMaxHeight()
                    .padding(start = RowGutter - 8.dp, top = 52.dp)
                    // Leaving the sidebar any other way (Back, Up to the nav bar) applies the filter too.
                    .onFocusChanged { if (!it.hasFocus) viewModel.commitPendingFilter() },
            )
            LibraryBody(state, viewModel, onOpenDetails, letterBar, memory, gridEntry, wrapToFilters, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LibraryBody(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    onOpenDetails: (MediaItem) -> Unit,
    letterBar: LetterBarFocus,
    memory: LibraryWrapMemory,
    gridEntry: SidebarGridEntry,
    onWrapToFilters: () -> Unit,
    modifier: Modifier,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val view = state.view
    val hasEntries = view.entries.isNotEmpty()
    // Every poster has its own jump requester: a jump can only ever focus its own target (PRD §7).
    val jumpTargets = remember(view.posters) { JumpFocusTargets() }
    // One cancellable grid scroll: letter focus is debounced, OK jumps at once (PRD §7).
    val letterJumps = remember(gridState) { LetterJumpScheduler(scope) { gridState.scrollToItem(it) } }
    LaunchedEffect(view.entries) { letterJumps.cancel() }
    // A `Right` that applied a pending filter lands on that filter's first poster once it is composed.
    LaunchedEffect(state) {
        if (gridEntry.awaiting(state.filter)) retryEachFrame(GRID_ENTRY_FRAMES) { gridEntry.first.tryRequestFocus() }
    }

    Row(modifier) {
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            LibrarySortAndGenre(
                sort = state.sort,
                onSort = viewModel::selectSort,
                genres = view.genreOptions,
                genreId = state.genreId,
                onGenre = viewModel::selectGenre,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            when {
                state.isLoading -> Unit
                hasEntries -> PosterGrid(
                    items = view.posters,
                    onItemClick = onOpenDetails,
                    state = gridState,
                    firstItemFocusRequester = gridEntry.first,
                    contentPadding = PaddingValues(top = 4.dp, end = 12.dp, bottom = 48.dp),
                    modifier = Modifier.fillMaxSize(),
                    itemModifier = { index -> Modifier.focusRequester(jumpTargets.requesterFor(index)) },
                )
                state.genreId != null || state.filter != LibraryFilter.ALL -> EmptyNote("No titles match these filters.")
                else -> EmptyNote("Your library is empty. Titles you add from a movie or show page will appear here.")
            }
        }
        if (hasEntries) {
            LetterBar(
                letterIndex = view.letterIndex,
                leftTargets = view.letterLeftTargets,
                onPreview = letterJumps::preview,
                // OK and Left: the poster attaches after the scroll, possibly a frame late on a far jump.
                onJump = { index -> letterJumps.jump(index) { jumpTargets.focus(index, JUMP_FOCUS_FRAMES) } },
                focus = letterBar,
                onLetterFocused = memory::onLetterFocused,
                onWrapRight = onWrapToFilters,
                modifier = Modifier.padding(end = 12.dp).width(LETTER_WIDTH).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}

private val SIDEBAR_WIDTH = 200.dp

/** Frames an `OK`/`Left` letter jump keeps trying to focus its poster before leaving focus on the letter. */
private const val JUMP_FOCUS_FRAMES = 10

/** Frames a `Right` that applied a pending filter keeps trying to focus the new grid's first poster. */
private const val GRID_ENTRY_FRAMES = 10
