package com.seastreams.tv.ui.navigation

import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.testMedia
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.screens.home.HomeDestination
import com.seastreams.tv.ui.screens.home.continueWatchingDestination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProfileShellTest {

    @Test
    fun `content pills switch tabs while Search and Settings do not`() {
        listOf(NavTab.HOME, NavTab.MOVIES, NavTab.TV_SHOWS, NavTab.LIBRARY).forEach { assertEquals(it, tabFor(it)) }
        assertNull(tabFor(NavTab.SEARCH))
        assertNull(tabFor(NavTab.SETTINGS))
    }

    private val card = ContinueWatchingItem(testMedia(9, MediaType.TV), seasonNumber = 2, episodeNumber = 4, progress = 0.5f)
    private val calls = mutableListOf<String>()

    private fun route(destination: HomeDestination) = routeHomeDestination(
        destination = destination,
        openDetails = { calls += "details:${it.key}" },
        viewMore = { calls += "viewMore:${it.id}" },
        play = { calls += "play:${it.media.key}" },
    )

    @Test
    fun `Continue Watching with auto-play on plays directly and never opens Details`() {
        route(continueWatchingDestination(card, autoPlay = true))

        assertEquals(listOf("play:${card.media.key}"), calls)
    }

    @Test
    fun `Continue Watching with auto-play off opens Details`() {
        route(continueWatchingDestination(card, autoPlay = false))

        assertEquals(listOf("details:${card.media.key}"), calls)
    }

    @Test
    fun `View More opens the row`() {
        route(HomeDestination.ViewMore(CatalogRowSpec("row", "Row", "https://mdblist.com/lists/a/row")))

        assertEquals(listOf("viewMore:row"), calls)
    }
}
