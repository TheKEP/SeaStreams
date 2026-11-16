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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/**
 * Library (PRD §7): hover-activated `All` / `Movies` / `TV Shows` filter on the left, Sort (saved
 * per profile) and Genre dropdowns on top, a 5-column 2:3 poster grid, and the A–Z quick-jump strip
 * on the right edge.
 *
 * Opens with `All` selected and focused, the grid in the profile's last-used sort order.
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
    val state = if (latest.profile?.id == profileId) latest else LibraryUiState()
    LaunchedEffect(latest.missingProfileId) { if (latest.missingProfileId == profileId) onSwitchProfile() }

    // `All` is the sidebar's first entry: focusing it directly can't hover-select a stale filter.
    val allFilter = remember { FocusRequester() }
    LaunchedEffect(Unit) { allFilter.requestFocusAfterFrame() }

    TabScaffold(state.profile, NavTab.LIBRARY, onTabSelected, onSwitchProfile) { _ ->
        Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
            HoverSidebar(
                entries = LibraryFilter.entries,
                selected = state.filter,
                label = { it.label },
                onSelect = viewModel::selectFilter,
                firstEntry = allFilter,
                modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().padding(start = RowGutter - 8.dp, top = 52.dp),
            )
            LibraryBody(state, viewModel, onOpenDetails, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LibraryBody(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    onOpenDetails: (MediaItem) -> Unit,
    modifier: Modifier,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val jumpTarget = remember { FocusRequester() }
    var jumpIndex by remember { mutableIntStateOf(-1) }
    val hasEntries = state.entries.isNotEmpty()

    Row(modifier) {
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            LibrarySortAndGenre(
                sort = state.sort,
                onSort = viewModel::selectSort,
                genres = state.genreOptions,
                genreId = state.genreId,
                onGenre = viewModel::selectGenre,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            when {
                state.isLoading -> Unit
                hasEntries -> PosterGrid(
                    items = state.entries.map { it.media },
                    onItemClick = onOpenDetails,
                    state = gridState,
                    contentPadding = PaddingValues(top = 4.dp, end = 12.dp, bottom = 48.dp),
                    modifier = Modifier.fillMaxSize(),
                    itemModifier = { index -> if (index == jumpIndex) Modifier.focusRequester(jumpTarget) else Modifier },
                )
                state.genreId != null || state.filter != LibraryFilter.ALL -> EmptyNote("No titles match these filters.")
                else -> EmptyNote("Your library is empty. Titles you add from a movie or show page will appear here.")
            }
        }
        if (hasEntries) {
            LetterBar(
                letterIndex = state.letterIndex,
                onPreview = { index -> scope.launch { gridState.scrollToItem(index) } },
                onJump = { index ->
                    jumpIndex = index
                    scope.launch {
                        gridState.scrollToItem(index)
                        jumpTarget.requestFocusAfterFrame()
                    }
                },
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
