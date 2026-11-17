package com.seastreams.tv.ui.screens.home

import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.testutil.testMedia
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContinueWatchingRemovalFocusTest {

    private fun cw(id: Int, season: Int = 0, episode: Int = 0) =
        ContinueWatchingItem(testMedia(id, if (season == 0) MediaType.MOVIE else MediaType.TV), season, episode, 0.5f)

    private fun removalOf(item: ContinueWatchingItem, index: Int) = ContinueWatchingRemoval(item.continueWatchingKey, index)

    private fun spec(id: String) = CatalogRowSpec(id, id, "https://mdblist.com/lists/a/$id")

    @Test
    fun `focus moves to the card that shifted in from the right`() {
        assertEquals(1, indexAfterRemoval(removedIndex = 1, remainingCount = 3))
        assertEquals(0, indexAfterRemoval(removedIndex = 0, remainingCount = 1))
    }

    @Test
    fun `removing the rightmost card moves focus one to the left`() {
        assertEquals(2, indexAfterRemoval(removedIndex = 3, remainingCount = 3))
        assertEquals(0, indexAfterRemoval(removedIndex = 1, remainingCount = 1))
    }

    @Test
    fun `nothing moves until the removed card has left the row`() {
        val items = listOf(cw(1), cw(2), cw(3))
        val sections = listOf(HomeSection.Hero, HomeSection.ContinueWatching(items))

        assertNull(focusAfterRemoval(removalOf(items[1], 1), sections))
    }

    @Test
    fun `middle removal resolves to index and rightmost removal to index - 1`() {
        val items = listOf(cw(1), cw(2), cw(3))
        val afterMiddle = listOf(HomeSection.Hero, HomeSection.ContinueWatching(listOf(items[0], items[2])))
        val afterLast = listOf(HomeSection.Hero, HomeSection.ContinueWatching(listOf(items[0], items[1])))

        assertEquals(RemovalFocus.Card(1), focusAfterRemoval(removalOf(items[1], 1), afterMiddle))
        assertEquals(RemovalFocus.Card(1), focusAfterRemoval(removalOf(items[2], 2), afterLast))
    }

    @Test
    fun `episodes of the same show are told apart`() {
        val e3 = cw(7, season = 1, episode = 3)
        val e2 = cw(7, season = 1, episode = 2)
        // Clearing S1E3 can surface the show's older in-progress S1E2 on the row.
        val sections = listOf(HomeSection.Hero, HomeSection.ContinueWatching(listOf(cw(1), e2)))

        assertEquals(RemovalFocus.Card(1), focusAfterRemoval(removalOf(e3, 1), sections))
    }

    @Test
    fun `an emptied row hands focus to the loaded row below`() {
        val row = HomeSection.Catalog(spec("trending"), RowContent.Loaded(CatalogRow(listOf(testMedia(5)), hasMore = false)))

        assertEquals(RemovalFocus.RowBelow(row.key), focusAfterRemoval(removalOf(cw(1), 0), listOf(HomeSection.Hero, row)))
    }

    @Test
    fun `an emptied row with nothing focusable below goes to the top`() {
        val loading = HomeSection.Catalog(spec("trending"), RowContent.Loading)

        assertEquals(RemovalFocus.Top, focusAfterRemoval(removalOf(cw(1), 0), listOf(HomeSection.Hero, loading)))
        assertEquals(RemovalFocus.Top, focusAfterRemoval(removalOf(cw(1), 0), listOf(HomeSection.Hero)))
    }
}
