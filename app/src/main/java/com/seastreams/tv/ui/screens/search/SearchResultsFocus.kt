package com.seastreams.tv.ui.screens.search

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.usecase.SearchResults
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

/** One results row on `search-results.jpg`. */
data class ResultSection(val title: String, val items: List<MediaItem>)

/** The non-empty results rows in display order: `Movies`, then `TV Shows`. */
fun resultSections(results: SearchResults): List<ResultSection> =
    listOf(ResultSection(MOVIES_TITLE, results.movies), ResultSection(TV_SHOWS_TITLE, results.shows))
        .filter { it.items.isNotEmpty() }

/** Scroll and focus handles for one results row. */
class ResultRowFocus(val section: ResultSection) {
    val listState = LazyListState()
    val firstItem = FocusRequester()
}

/**
 * Deterministic focus routing into the Search results (PRD §1, §7). Both ways into the results land
 * on item #1 of the top row (`Movies`, else `TV Shows`), with the page and that row scrolled back to
 * their starts:
 * - `Down` out of the search bar / recent chips, so spatial search can't skip a partly scrolled row;
 * - a fresh `Down` on the bottom row (edge-stop-then-wrap).
 *
 * One instance per result set, so a new search opens at the top.
 */
@Stable
class SearchResultsFocus(
    sections: List<ResultSection>,
    private val scrollToStart: suspend (LazyListState) -> Unit = { it.scrollToItem(0) },
    private val requestFocus: suspend (FocusRequester) -> Boolean = { it.requestFocusAfterFrame() },
) {
    /** Vertical list holding the rows. */
    val columnState = LazyListState()
    val rows: List<ResultRowFocus> = sections.map(::ResultRowFocus)

    /** Whether there is a row to route focus into. */
    val hasRows: Boolean get() = rows.isNotEmpty()

    /**
     * Scrolls the page to the top and the top row to item #1, then focuses that item. Returns
     * whether focus moved (`false` when there are no rows).
     */
    suspend fun focusTopRow(): Boolean {
        val top = rows.firstOrNull() ?: return false
        scrollToStart(columnState)
        scrollToStart(top.listState)
        return requestFocus(top.firstItem)
    }
}

const val MOVIES_TITLE = "Movies"
const val TV_SHOWS_TITLE = "TV Shows"
