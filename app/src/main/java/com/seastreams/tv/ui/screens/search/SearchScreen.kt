package com.seastreams.tv.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.usecase.SearchCategory
import com.seastreams.tv.ui.components.HoverSidebar
import com.seastreams.tv.ui.components.PosterGrid
import com.seastreams.tv.ui.components.PosterGridPlaceholder
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.theme.Obsidian
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/**
 * Search (`search-screen.jpg`, `search-results.jpg`, PRD §7).
 *
 * - Opens with D-pad focus on the microphone and `Trending Movies` showing in the grid.
 * - With an empty query: the hover-activated category sidebar on the left drives the 5-column
 *   poster grid on the right as focus moves over it.
 * - With a query: `Movies` and `TV Shows` result rows replace the browser. `Down` from the top bar
 *   and the bottom-row wrap both land on item #1 of the top results row ([SearchResultsFocus]).
 * - `Back` is handled by the caller (it returns to the previous tab).
 */
@Composable
fun SearchScreen(
    profileId: Long,
    viewModel: SearchViewModel,
    onOpenDetails: (MediaItem) -> Unit,
    onProfileMissing: () -> Unit,
) {
    LaunchedEffect(profileId) { viewModel.open(profileId) }
    val latest by viewModel.uiState.collectAsState()
    // Activity-scoped ViewModel: ignore state that still belongs to another profile.
    val state = if (latest.profile?.id == profileId) latest else SearchUiState()
    val query by viewModel.query.collectAsState()
    LaunchedEffect(latest.missingProfileId) { if (latest.missingProfileId == profileId) onProfileMissing() }

    val mic = remember { FocusRequester() }
    LaunchedEffect(Unit) { mic.requestFocusAfterFrame() }
    val voice = rememberVoiceSearch(
        onPartial = { viewModel.onQueryChange(it) },
        onResult = { viewModel.submit(it) },
    )

    val scope = rememberCoroutineScope()
    val loaded = (state.results as? ResultsState.Loaded)?.results
    // One router per result set, so every new search opens scrolled to the top.
    val resultsFocus = remember(loaded) { SearchResultsFocus(loaded?.let(::resultSections).orEmpty()) }

    Column(Modifier.fillMaxSize().background(Obsidian).padding(top = 28.dp)) {
        SearchTopBar(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            onSubmit = { viewModel.submit() },
            onClearQuery = viewModel::clearQuery,
            voice = voice,
            micFocusRequester = mic,
            recentSearches = state.recentSearches,
            onRecentClick = { viewModel.submit(it) },
            onClearRecents = viewModel::clearRecentSearches,
            onDownToContent = {
                // Results showing: always enter item #1 of the top row, never a row further down.
                resultsFocus.hasRows.also { if (it) scope.launch { resultsFocus.focusTopRow() } }
            },
        )
        Box(Modifier.padding(top = 16.dp)) {
            val results = state.results
            if (results == null) {
                CategoryBrowser(state, onSelect = viewModel::selectCategory, onOpenDetails = onOpenDetails)
            } else {
                SearchResultsContent(results, query, resultsFocus, onOpenDetails)
            }
        }
    }
}

@Composable
private fun CategoryBrowser(state: SearchUiState, onSelect: (SearchCategory) -> Unit, onOpenDetails: (MediaItem) -> Unit) {
    Row(Modifier.fillMaxSize()) {
        HoverSidebar(
            entries = state.categories,
            selected = state.selectedCategory,
            label = { it.title },
            onSelect = onSelect,
            modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().padding(start = RowGutter - SIDEBAR_INSET),
        )
        val gridPadding = PaddingValues(start = 20.dp, end = RowGutter, top = 4.dp, bottom = 48.dp)
        val grid = state.grid
        val selectedId = state.selectedCategory.id
        when {
            grid.categoryId != selectedId || grid is GridState.Loading ->
                PosterGridPlaceholder(Modifier.fillMaxSize(), contentPadding = gridPadding)
            grid is GridState.Failed -> CenteredNote("Couldn't load ${state.selectedCategory.title}. Check your connection.")
            grid is GridState.Loaded && grid.items.isEmpty() -> CenteredNote("Nothing to show in ${state.selectedCategory.title}.")
            grid is GridState.Loaded -> key(selectedId) {
                // Keyed by category so every category opens scrolled to its top.
                PosterGrid(
                    items = grid.items,
                    onItemClick = onOpenDetails,
                    contentPadding = gridPadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private val SIDEBAR_WIDTH = 250.dp

/** Pulls the sidebar pills slightly into the gutter so their text lines up with the mic button. */
private val SIDEBAR_INSET = 8.dp
