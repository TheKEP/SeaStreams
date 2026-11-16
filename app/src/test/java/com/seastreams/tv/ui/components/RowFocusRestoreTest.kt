package com.seastreams.tv.ui.components

import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.ui.screens.catalog.discoverRowRestore
import com.seastreams.tv.ui.screens.home.HomeUiState
import com.seastreams.tv.ui.screens.home.buildHomeSections
import com.seastreams.tv.ui.screens.home.homeRowRestore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RowFocusRestoreTest {

    private val item = MediaItem(
        tmdbId = 1, mediaType = MediaType.MOVIE, title = "Film", overview = "", posterUrl = null, backdropUrl = null,
        heroBackdropUrl = null, logoUrl = null, year = null, ratingPercent = null, genres = emptyList(),
        runtimeMinutes = null, certification = null, isAdultContent = false,
    )
    private val loaded = RowContent.Loaded(CatalogRow(listOf(item), hasMore = true))

    private fun row(id: String, content: RowContent) = CatalogRowState(CatalogRowSpec(id, id, "https://mdblist.com/lists/a/$id"), content)

    @Test
    fun `the recorded origin row only applies to the tab it was recorded on`() {
        val target = RowReturnTarget(NavTab.MOVIES, "netflix")

        assertEquals("netflix", target.rowIdFor(NavTab.MOVIES))
        assertNull(target.rowIdFor(NavTab.TV_SHOWS))
        assertNull(target.rowIdFor(NavTab.HOME))
        assertNull((null as RowReturnTarget?).rowIdFor(NavTab.MOVIES))
    }

    @Test
    fun `returning from View More lands on the first card of the row`() {
        assertEquals(0, RETURN_CARD_INDEX)
    }

    @Test
    fun `only a loaded row on the page can take focus back`() {
        assertEquals(RowRestoreStep.ScrollTo(3), resolveRowRestore(3, loaded))
        assertEquals(RowRestoreStep.Wait, resolveRowRestore(3, RowContent.Loading))
        assertEquals(RowRestoreStep.Abandon, resolveRowRestore(3, RowContent.Failed))
        assertEquals(RowRestoreStep.Abandon, resolveRowRestore(-1, null))
    }

    @Test
    fun `Home scrolls to the origin row past the hero and Continue Watching`() {
        val state = HomeUiState(
            continueWatching = listOf(ContinueWatchingItem(item, seasonNumber = 0, episodeNumber = 0, progress = 0.3f)),
            rows = listOf(
                row("trending", loaded),
                row("empty", RowContent.Loaded(CatalogRow(emptyList(), hasMore = false))),
                row("top-rated", loaded),
                row("loading", RowContent.Loading),
            ),
        )
        val sections = buildHomeSections(state)

        // hero, continue-watching, trending, top-rated ("empty" is dropped), loading
        assertEquals(RowRestoreStep.ScrollTo(2), homeRowRestore(sections, "trending"))
        assertEquals(RowRestoreStep.ScrollTo(3), homeRowRestore(sections, "top-rated"))
        assertEquals(RowRestoreStep.Wait, homeRowRestore(sections, "loading"))
        assertEquals(RowRestoreStep.Abandon, homeRowRestore(sections, "empty"))
        assertEquals(RowRestoreStep.Abandon, homeRowRestore(sections, "gone"))
    }

    @Test
    fun `Movies and TV Shows scroll to the origin row with Popular first and the provider bar last`() {
        val rows = listOf(row("popular", loaded), row("netflix", loaded), row("hulu", RowContent.Failed))

        assertEquals(RowRestoreStep.ScrollTo(0), discoverRowRestore(rows, "popular"))
        assertEquals(RowRestoreStep.ScrollTo(1), discoverRowRestore(rows, "netflix"))
        assertEquals(RowRestoreStep.Abandon, discoverRowRestore(rows, "hulu"))
        assertEquals(RowRestoreStep.Abandon, discoverRowRestore(rows, "gone"))
    }
}
