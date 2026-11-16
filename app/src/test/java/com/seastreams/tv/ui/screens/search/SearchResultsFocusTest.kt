package com.seastreams.tv.ui.screens.search

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.usecase.SearchResults
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Search results focus routing: the bottom-row `Down` wrap and `Down` out of the search bar /
 * recent chips both land on item #1 of the top results row, with the page scrolled to the top.
 */
class SearchResultsFocusTest {

    private val focused = mutableListOf<FocusRequester>()

    /** A router whose scroll applies on the spot (no layout pass in unit tests) and records focus requests. */
    private fun router(results: SearchResults) = SearchResultsFocus(
        sections = resultSections(results),
        scrollToStart = { it.requestScrollToItem(0) },
        requestFocus = { focused += it; true },
    )

    /** The state after browsing: page scrolled down to `TV Shows`, both rows scrolled sideways. */
    private fun SearchResultsFocus.drift() {
        columnState.requestScrollToItem(index = 1, scrollOffset = 40)
        rows.forEach { it.listState.requestScrollToItem(index = 6, scrollOffset = 12) }
    }

    private fun LazyListState.assertAtStart() {
        assertEquals(0, firstVisibleItemIndex)
        assertEquals(0, firstVisibleItemScrollOffset)
    }

    @Test
    fun `rows are Movies then TV Shows and empty rows are dropped`() {
        assertEquals(listOf(MOVIES_TITLE, TV_SHOWS_TITLE), resultSections(both).map { it.title })
        assertEquals(listOf(TV_SHOWS_TITLE), resultSections(SearchResults(emptyList(), shows)).map { it.title })
        assertEquals(listOf(MOVIES_TITLE), resultSections(SearchResults(movies, emptyList())).map { it.title })
        assertTrue(resultSections(SearchResults(emptyList(), emptyList())).isEmpty())
    }

    @Test
    fun `Down wrap from the bottom row focuses Movies item 1 with the page scrolled to the top`() = runTest {
        val focus = router(both)
        focus.drift()

        // Fresh `Down` on the bottom (`TV Shows`) row runs the edge wrap.
        assertTrue(focus.focusTopRow())

        val (moviesRow, showsRow) = focus.rows
        assertEquals(MOVIES_TITLE, moviesRow.section.title)
        assertEquals(listOf(moviesRow.firstItem), focused)
        focus.columnState.assertAtStart()
        moviesRow.listState.assertAtStart()
        // The row left behind keeps its place for when the user goes back down.
        assertEquals(6, showsRow.listState.firstVisibleItemIndex)
    }

    @Test
    fun `Down from the search bar enters Movies item 1 instead of skipping to TV Shows`() = runTest {
        val focus = router(both)
        focus.drift() // page left scrolled down at `TV Shows`, Movies partly or fully off screen

        assertTrue(focus.hasRows) // so the top bar consumes `Down` and hands it to the router
        assertTrue(focus.focusTopRow())

        val target = focus.rows.first()
        assertEquals(MOVIES_TITLE, target.section.title)
        assertSame(target.firstItem, focused.single())
        focus.columnState.assertAtStart()
        target.listState.assertAtStart()
    }

    @Test
    fun `without Movies results, both entries land on TV Shows item 1`() = runTest {
        val focus = router(SearchResults(emptyList(), shows))
        focus.drift()

        assertTrue(focus.focusTopRow())

        val target = focus.rows.single()
        assertEquals(TV_SHOWS_TITLE, target.section.title)
        assertSame(target.firstItem, focused.single())
        focus.columnState.assertAtStart()
        target.listState.assertAtStart()
    }

    @Test
    fun `with no results the top bar keeps its default Down and nothing is focused`() = runTest {
        val focus = router(SearchResults(emptyList(), emptyList()))

        assertFalse(focus.hasRows)
        assertFalse(focus.focusTopRow())
        assertTrue(focused.isEmpty())
    }

    private companion object {
        fun item(id: Int, type: MediaType) = MediaItem(
            tmdbId = id,
            mediaType = type,
            title = "Item $id",
            overview = "",
            posterUrl = null,
            backdropUrl = null,
            heroBackdropUrl = null,
            logoUrl = null,
            year = null,
            ratingPercent = null,
            genres = emptyList(),
            runtimeMinutes = null,
            certification = null,
            isAdultContent = false,
        )

        val movies = (1..10).map { item(it, MediaType.MOVIE) }
        val shows = (1..10).map { item(100 + it, MediaType.TV) }
        val both = SearchResults(movies, shows)
    }
}
