package com.seastreams.tv.ui.screens.catalog

import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscoverFocusTest {

    private val item = MediaItem(
        tmdbId = 1, mediaType = MediaType.MOVIE, title = "Film", overview = "", posterUrl = null, backdropUrl = null,
        heroBackdropUrl = null, logoUrl = null, year = null, ratingPercent = null, genres = emptyList(),
        runtimeMinutes = null, certification = null, isAdultContent = false,
    )
    private val loaded = RowContent.Loaded(CatalogRow(listOf(item), hasMore = false))
    private val empty = RowContent.Loaded(CatalogRow(emptyList(), hasMore = false))

    private fun row(id: String, content: RowContent) = CatalogRowState(CatalogRowSpec(id, id, "https://mdblist.com/lists/a/$id"), content)

    @Test
    fun `the first visible row skips a Popular row that loaded empty`() {
        val rows = visibleRows(listOf(row("popular", empty), row("netflix", loaded), row("hulu", RowContent.Loading)))

        assertEquals(listOf("netflix", "hulu"), rows.map { it.spec.id })
    }

    @Test
    fun `a fresh entry starts pinned to the top with focus on the nav pill`() {
        val entry = TopEntryState(freshEntry = true)

        assertTrue(entry.pinned)
        assertFalse(entry.pillSettled)
    }

    @Test
    fun `stray content focus before the pill has focus does not unpin the page`() {
        assertTrue(stillPinned(pinned = true, pillSettled = false, contentFocused = true))
        val entry = TopEntryState(freshEntry = true)
        entry.onContentFocused()
        assertTrue(entry.pinned)
    }

    @Test
    fun `the page unpins once the user moves focus down from the nav pill into it`() {
        val entry = TopEntryState(freshEntry = true)
        entry.onPillSettled()
        entry.onContentFocused()

        assertFalse(entry.pinned)
        assertFalse(stillPinned(pinned = true, pillSettled = true, contentFocused = true))
        assertTrue(stillPinned(pinned = true, pillSettled = true, contentFocused = false))
    }

    @Test
    fun `a return from View More is never pinned to the top`() {
        val entry = TopEntryState(freshEntry = false)

        assertFalse(entry.pinned)
        assertTrue(entry.pillSettled)
    }

    @Test
    fun `the streaming services bar can't take focus until entry focus settles on the pill`() {
        assertFalse(providerBarFocusable(pillSettled = false, returnPending = false))
        assertFalse(providerBarFocusable(pillSettled = true, returnPending = true))
        assertTrue(providerBarFocusable(pillSettled = true, returnPending = false))
    }

    @Test
    fun `Down into the streaming services bar lands on the first chip`() {
        assertEquals(ProviderBarEntry.FOCUS_FIRST, providerBarEntry(focusable = true, fromAbove = true, firstChipVisible = true))
    }

    @Test
    fun `Down into a bar scrolled away from its start scrolls back and lands on the first chip`() {
        assertEquals(
            ProviderBarEntry.SCROLL_THEN_FOCUS_FIRST,
            providerBarEntry(focusable = true, fromAbove = true, firstChipVisible = false),
        )
    }

    @Test
    fun `the bar refuses focus while the page's entry focus is pending`() {
        assertEquals(ProviderBarEntry.BLOCK, providerBarEntry(focusable = false, fromAbove = true, firstChipVisible = true))
        assertEquals(ProviderBarEntry.BLOCK, providerBarEntry(focusable = false, fromAbove = false, firstChipVisible = false))
    }

    @Test
    fun `non-Down entries into the bar keep their own target`() {
        assertEquals(ProviderBarEntry.DEFAULT, providerBarEntry(focusable = true, fromAbove = false, firstChipVisible = true))
    }
}
