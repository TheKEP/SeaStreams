package com.seastreams.tv.ui.screens.home

import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.ui.components.MetadataFormat
import com.seastreams.tv.ui.components.NavTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HomeSectionsTest {

    private val item = MediaItem(
        tmdbId = 1, mediaType = MediaType.TV, title = "Show", overview = "", posterUrl = null, backdropUrl = null,
        heroBackdropUrl = null, logoUrl = null, year = null, ratingPercent = null, genres = emptyList(),
        runtimeMinutes = null, certification = null, isAdultContent = false,
    )
    private val resume = ContinueWatchingItem(item, seasonNumber = 7, episodeNumber = 10, progress = 0.5f)

    private fun row(id: String, content: RowContent) = CatalogRowState(CatalogRowSpec(id, id, "https://mdblist.com/lists/a/$id"), content)

    @Test
    fun `sections are hero, continue watching, then rows, skipping empty ones`() {
        val state = HomeUiState(
            continueWatching = listOf(resume),
            rows = listOf(
                row("loaded", RowContent.Loaded(CatalogRow(listOf(item), hasMore = false))),
                row("empty", RowContent.Loaded(CatalogRow(emptyList(), hasMore = false))),
                row("loading", RowContent.Loading),
                row("failed", RowContent.Failed),
            ),
        )

        val sections = buildHomeSections(state)

        assertEquals(listOf("hero", "continue-watching", "row-loaded", "row-loading", "row-failed"), sections.map { it.key })
        assertEquals(listOf(true, true, true, false, false), sections.map { it.focusable })
    }

    @Test
    fun `continue watching is omitted when there is nothing to resume`() {
        assertEquals(listOf("hero"), buildHomeSections(HomeUiState()).map { it.key })
    }

    @Test
    fun `continue watching honours the auto-play toggle`() {
        assertEquals(HomeDestination.Play(resume), continueWatchingDestination(resume, autoPlay = true))
        assertEquals(HomeDestination.Details(item), continueWatchingDestination(resume, autoPlay = false))
    }

    @Test
    fun `kids profiles never get Movies or TV Shows tabs`() {
        val kidsTabs = NavTab.visibleFor(isKids = true)
        assertFalse(NavTab.MOVIES in kidsTabs)
        assertFalse(NavTab.TV_SHOWS in kidsTabs)
        assertEquals(listOf(NavTab.SEARCH, NavTab.HOME, NavTab.LIBRARY, NavTab.SETTINGS), kidsTabs)
        assertEquals(NavTab.entries.toList(), NavTab.visibleFor(isKids = false))
    }

    @Test
    fun `metadata formatting`() {
        assertEquals("1h 39m", MetadataFormat.runtime(99))
        assertEquals("2h", MetadataFormat.runtime(120))
        assertEquals("45m", MetadataFormat.runtime(45))
        assertNull(MetadataFormat.runtime(0))
        assertEquals("S7:E10", MetadataFormat.episodeBadge(7, 10))
        assertNull(MetadataFormat.episodeBadge(0, 0))
    }
}
