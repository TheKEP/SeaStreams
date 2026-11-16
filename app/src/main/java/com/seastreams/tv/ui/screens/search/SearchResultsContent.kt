package com.seastreams.tv.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.components.PosterCard
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/**
 * `search-results.jpg`: a `Movies` row then a `TV Shows` row of standard 2:3 posters. Holding `Down`
 * on the bottom row stops there; a fresh `Down` returns to item #1 of the top row with the page
 * scrolled back to the top (PRD §1), via [focus].
 */
@Composable
fun SearchResultsContent(state: ResultsState, query: String, focus: SearchResultsFocus, onOpenDetails: (MediaItem) -> Unit) {
    when (state) {
        ResultsState.Loading -> CenteredNote("Searching…")
        ResultsState.Failed -> CenteredNote("Search failed. Check your connection and try again.")
        is ResultsState.Loaded -> {
            if (state.results.isEmpty) {
                CenteredNote("No matches for “${query.trim()}”")
            } else {
                val scope = rememberCoroutineScope()
                val wrapToTopRow: () -> Unit = { scope.launch { focus.focusTopRow() } }
                LazyColumn(
                    state = focus.columnState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    focus.rows.forEachIndexed { index, row ->
                        item(key = row.section.title) {
                            val isBottom = index == focus.rows.lastIndex
                            Box(if (isBottom) Modifier.onDpadEdgeWrap(Key.DirectionDown, wrapToTopRow) else Modifier) {
                                PosterRow(row, onOpenDetails)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Horizontal row of fixed-size 2:3 posters; `Right` on the last poster stops, then wraps (PRD §1). */
@Composable
private fun PosterRow(row: ResultRowFocus, onOpenDetails: (MediaItem) -> Unit) {
    val listState = row.listState
    val first = row.firstItem
    val items = row.section.items
    val scope = rememberCoroutineScope()
    val wrapToFirst: () -> Unit = {
        scope.launch {
            listState.scrollToItem(0)
            first.requestFocusAfterFrame()
        }
    }
    Column {
        Text(
            row.section.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = RowGutter, bottom = 10.dp),
        )
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(RESULT_POSTER_HEIGHT).focusRestorer(first),
            contentPadding = PaddingValues(horizontal = RowGutter),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                PosterCard(
                    item = item,
                    onClick = { onOpenDetails(item) },
                    modifier = Modifier
                        .width(RESULT_POSTER_WIDTH)
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                        .then(if (index == items.lastIndex) Modifier.onDpadEdgeWrap(Key.DirectionRight, wrapToFirst) else Modifier),
                )
            }
        }
    }
}

@Composable
internal fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}

private val RESULT_POSTER_WIDTH = 150.dp
private val RESULT_POSTER_HEIGHT = 225.dp
