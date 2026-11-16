package com.seastreams.tv.ui.screens.catalog

import androidx.compose.foundation.lazy.LazyListState
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.domain.model.StreamingProvider
import com.seastreams.tv.domain.usecase.DiscoverCatalogSources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Opening Movies / TV Shows from the nav bar: focus on the tab's pill, list at `(0, 0)`. */
class DiscoverEntryTest {

    private fun loadingRows(type: MediaType): List<CatalogRowState> =
        DiscoverCatalogSources.rowSpecs(type, StreamingProvider.entries, emptyList()).map { CatalogRowState(it, RowContent.Loading) }

    /** A list that has drifted, e.g. anchored to the bottom bar when the rows were inserted above it. */
    private fun driftedList() = LazyListState(firstVisibleItemIndex = 7, firstVisibleItemScrollOffset = 120)

    @ParameterizedTest
    @EnumSource(MediaType::class)
    fun `initial navigation keeps the nav pill focused and the viewport at the top`(type: MediaType) {
        val entry = TopEntryState(freshEntry = entryFocusFor(returnRowId = null) == EntryFocus.TAB_PILL)
        val list = driftedList()

        // First emission for the profile: no rows yet, so the bottom bar must not be composed alone.
        assertFalse(providerBarReady(emptyList()))
        entry.holdTop(list)
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)

        // The rows arrive (all loading): `Popular` is item 0, above the bar; the page stays at the top.
        val rows = loadingRows(type)
        assertTrue(providerBarReady(rows))
        assertEquals("${type.name.lowercase()}-popular", visibleRows(rows).first().spec.id)
        list.requestScrollToItem(index = 5) // the list re-anchoring after the insertion
        entry.holdTop(list)
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)

        // Nothing has moved focus off the pill: stray content focus before it settles keeps the pin.
        entry.onContentFocused()
        assertTrue(entry.pinned)
        assertFalse(providerBarFocusable(entry.pillSettled, returnPending = false))
    }

    @Test
    fun `a return from View More targets its row and leaves the list where the row restore puts it`() {
        assertEquals(EntryFocus.RETURN_ROW, entryFocusFor(returnRowId = "movie-netflix"))
        val entry = TopEntryState(freshEntry = false)
        val list = LazyListState(firstVisibleItemIndex = 3)

        entry.holdTop(list)

        assertEquals(3, list.firstVisibleItemIndex)
    }

    @Test
    fun `once the user moves down from the pill the page scrolls freely`() {
        val entry = TopEntryState(freshEntry = true)
        entry.onPillSettled()
        entry.onContentFocused()
        val list = LazyListState(firstVisibleItemIndex = 2)

        entry.holdTop(list)

        assertEquals(2, list.firstVisibleItemIndex)
    }

    @Test
    fun `Down from the nav bar lands on card 1 of the first row`() {
        val loaded = RowContent.Loaded(CatalogRow(emptyList(), hasMore = false))
        assertEquals(NavDownEntry.FIRST_CARD, navDownEntry(true, loaded, firstRowVisible = true, barShown = true))
        assertEquals(NavDownEntry.SCROLL_TOP_THEN_FIRST_CARD, navDownEntry(true, loaded, firstRowVisible = false, barShown = true))
    }

    @Test
    fun `Down from the nav bar stays on the pill while the first row is loading or absent`() {
        assertEquals(NavDownEntry.STAY_ON_NAV, navDownEntry(true, RowContent.Loading, firstRowVisible = true, barShown = true))
        assertEquals(NavDownEntry.STAY_ON_NAV, navDownEntry(true, firstRow = null, firstRowVisible = false, barShown = false))
    }

    @Test
    fun `Down falls back to default focus search when the first row can't take focus`() {
        assertEquals(NavDownEntry.DEFAULT, navDownEntry(true, RowContent.Failed, firstRowVisible = true, barShown = true))
        // Every row loaded empty: only the bar is left, and it must stay reachable.
        assertEquals(NavDownEntry.DEFAULT, navDownEntry(true, firstRow = null, firstRowVisible = false, barShown = true))
        assertEquals(NavDownEntry.DEFAULT, navDownEntry(false, RowContent.Loading, firstRowVisible = true, barShown = true))
    }
}
