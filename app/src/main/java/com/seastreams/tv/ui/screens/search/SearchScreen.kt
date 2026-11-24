package com.seastreams.tv.ui.screens.search

import androidx.tv.material3.MaterialTheme
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.usecase.SearchCategory
import com.seastreams.tv.ui.components.HoverSidebar
import com.seastreams.tv.ui.components.POSTER_GRID_COLUMNS
import com.seastreams.tv.ui.components.PosterGrid
import com.seastreams.tv.ui.components.PosterGridPlaceholder
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.theme.onDpadKeyDown
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/**
 * Search (`search-screen.jpg`, `search-results.jpg`, PRD §7).
 *
 * - Opens with D-pad focus on the microphone (on the search field where the device has no voice
 *   input, e.g. Fire OS, and the mic is hidden) and `Trending Movies` showing in the grid.
 * - With an empty query: the hover-activated category sidebar on the left drives the 5-column
 *   poster grid on the right as focus moves over it.
 * - With a query: `Movies` and `TV Shows` result rows replace the browser. `Down` from the top bar
 *   and the bottom-row wrap both land on item #1 of the top results row ([SearchResultsFocus]).
 * - `Back` is handled by the caller (it returns to the previous tab).
 * - Each new [micFocusRequest] (long-press `Back` while already here, PRD §1) refocuses that same control.
 */
@Composable
fun SearchScreen(
    profileId: Long,
    viewModel: SearchViewModel,
    onOpenDetails: (MediaItem) -> Unit,
    onProfileMissing: () -> Unit,
    micFocusRequest: Int = 0,
) {
    LaunchedEffect(profileId) { viewModel.open(profileId) }
    val latest by viewModel.uiState.collectAsState()
    // Activity-scoped ViewModel: ignore state that still belongs to another profile.
    val state = if (latest.profile?.id == profileId) latest else SearchUiState()
    val query by viewModel.query.collectAsState()
    LaunchedEffect(latest.missingProfileId) { if (latest.missingProfileId == profileId) onProfileMissing() }

    val mic = remember { FocusRequester() }
    val topBarFocus = remember { SearchTopBarFocus() }
    val scope = rememberCoroutineScope()
    // After the speech activity closes, focus lands once the window has focus again.
    val voiceActions = remember(viewModel, scope) {
        object : VoiceSearchActions {
            override fun submit(text: String) = viewModel.submit(text)
            override fun focusMic() {
                scope.launch { topBarFocus.homeRequester(mic).requestFocusAfterFrame() }
            }
            // The field's LaunchedEffect focuses the EditText and opens the keyboard.
            override fun focusAndOpenKeyboard() = topBarFocus.fieldInput.openKeyboard()
        }
    }
    val voice = rememberVoiceSearch(onPartial = { viewModel.onQueryChange(it) }, actions = voiceActions)
    // Without voice input (Fire OS) the mic is hidden: focus starts on, and falls back to, the field.
    topBarFocus.micAvailable = voice.micAvailable
    LaunchedEffect(micFocusRequest) { topBarFocus.homeRequester(mic).requestFocusAfterFrame() }

    val loaded = (state.results as? ResultsState.Loaded)?.results
    // One router per result set, so every new search opens scrolled to the top.
    val resultsFocus = remember(loaded) { SearchResultsFocus(loaded?.let(::resultSections).orEmpty()) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 28.dp)) {
        SearchTopBar(
            query = query,
            // Typed text, a search or a recent chip clears the "voice unavailable" note.
            onQueryChange = {
                voice.clearVoiceNotice()
                viewModel.onQueryChange(it)
            },
            onSubmit = {
                voice.clearVoiceNotice()
                viewModel.submit()
            },
            onClearQuery = viewModel::clearQuery,
            voice = voice,
            micFocusRequester = mic,
            recentSearches = state.recentSearches,
            onRecentClick = {
                voice.clearVoiceNotice()
                viewModel.submit(it)
            },
            onClearRecents = viewModel::clearRecentSearches,
            onDownToContent = {
                // Results showing: always enter item #1 of the top row, never a row further down.
                resultsFocus.hasRows.also { if (it) scope.launch { resultsFocus.focusTopRow() } }
            },
            focus = topBarFocus,
        )
        Box(Modifier.padding(top = 16.dp)) {
            val results = state.results
            if (results == null) {
                // `Up` from the first category or a top-row poster: chip #1, else the top bar (PRD §7).
                val upToTopBar = Modifier.onDpadKeyDown(Key.DirectionUp) {
                    scope.launch { topBarFocus.enterFromContent(state.recentSearches.isNotEmpty(), query.isNotEmpty(), mic) }
                    true
                }
                CategoryBrowser(state, onSelect = viewModel::selectCategory, onOpenDetails = onOpenDetails, upToTopBar = upToTopBar)
            } else {
                SearchResultsContent(results, query, resultsFocus, onOpenDetails)
            }
        }
    }
}

@Composable
private fun CategoryBrowser(
    state: SearchUiState,
    onSelect: (SearchCategory) -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    upToTopBar: Modifier,
) {
    Row(Modifier.fillMaxSize()) {
        HoverSidebar(
            entries = state.categories,
            selected = state.selectedCategory,
            label = { it.title },
            onSelect = onSelect,
            entryModifier = { index, _ -> if (index == 0) upToTopBar else Modifier },
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
                    itemModifier = { index -> if (index < POSTER_GRID_COLUMNS) upToTopBar else Modifier },
                )
            }
        }
    }
}

private val SIDEBAR_WIDTH = 250.dp

/** Pulls the sidebar pills slightly into the gutter so their text lines up with the mic button. */
private val SIDEBAR_INSET = 8.dp
