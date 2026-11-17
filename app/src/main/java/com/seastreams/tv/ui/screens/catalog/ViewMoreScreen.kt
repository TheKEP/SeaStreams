package com.seastreams.tv.ui.screens.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.components.PosterGrid
import com.seastreams.tv.ui.components.PosterGridPlaceholder
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

/**
 * View More (`view-more.jpg`, PRD §4): the row's title over a 5-column grid of up to 100 standard
 * 2:3 posters, with no 16:9 expansion. Focus starts on the first poster; `Back` is handled by the
 * caller (it returns to the row's tab).
 */
@Composable
fun ViewMoreScreen(
    profileId: Long,
    title: String,
    source: CatalogSource,
    viewModel: ViewMoreViewModel,
    onOpenDetails: (MediaItem) -> Unit,
    onProfileMissing: () -> Unit,
) {
    LaunchedEffect(profileId, source) { viewModel.show(profileId, source) }
    // Frame 1 draws the row's titles already in memory; the rest stream in below them.
    val initial = remember(profileId, source) { viewModel.initialState(profileId, source) }
    val latest by viewModel.uiState.collectAsState()
    val current = ViewMoreRequest(profileId, source)
    // Activity-scoped ViewModel: never show a grid that belongs to a previous View More.
    val state = if (latest.request == current) latest else initial
    LaunchedEffect(state) { if (state is ViewMoreUiState.ProfileMissing) onProfileMissing() }

    val firstPoster = remember { FocusRequester() }
    val loaded = state is ViewMoreUiState.Loaded && state.items.isNotEmpty()
    LaunchedEffect(loaded) { if (loaded) firstPoster.requestFocusAfterFrame() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 32.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = RowGutter, bottom = 16.dp),
        )
        val gridPadding = PaddingValues(start = RowGutter, end = RowGutter, bottom = 48.dp)
        when (state) {
            is ViewMoreUiState.Loaded ->
                if (state.items.isEmpty() && state.loadingMore) {
                    PosterGridPlaceholder(Modifier.fillMaxSize(), contentPadding = gridPadding)
                } else if (state.items.isEmpty()) {
                    Notice("Nothing to show here right now.")
                } else {
                    PosterGrid(
                        items = state.items,
                        onItemClick = onOpenDetails,
                        firstItemFocusRequester = firstPoster,
                        contentPadding = gridPadding,
                        modifier = Modifier.fillMaxSize(),
                        wrapAtBottom = !state.loadingMore,
                    )
                }
            is ViewMoreUiState.Failed -> Notice("Couldn't load these titles. Check your connection and try again.")
            is ViewMoreUiState.Loading, is ViewMoreUiState.ProfileMissing ->
                PosterGridPlaceholder(Modifier.fillMaxSize(), contentPadding = gridPadding)
        }
    }
}

@Composable
private fun Notice(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}
