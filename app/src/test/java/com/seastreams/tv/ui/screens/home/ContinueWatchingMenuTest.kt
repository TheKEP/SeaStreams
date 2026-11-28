package com.seastreams.tv.ui.screens.home

import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.NextUpMarker
import com.seastreams.tv.testutil.testMedia
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** PRD §3: the Continue Watching long-press menu. */
class ContinueWatchingMenuTest {

    private val item = ContinueWatchingItem(testMedia(9, MediaType.TV), seasonNumber = 7, episodeNumber = 10, progress = 0.5f)
    private val card = FocusRequester()
    private val calls = mutableListOf<String>()
    private val opened = mutableListOf<HomeDestination>()

    private fun menu() = continueWatchingMenu(
        item = item,
        returnFocus = card,
        onViewDetails = { calls += "details"; opened += it },
        onRemove = { calls += "remove" },
        onClearProgress = { calls += "clear" },
    )

    @Test
    fun `offers View details, Remove and Clear progress in that order`() {
        val request = menu()

        assertEquals(listOf("View details", "Remove", "Clear progress"), request.actions.map { it.label })
        assertEquals(item.media.title, request.title)
        assertEquals("S7:E10", request.subtitle)
        assertSame(card, request.returnFocus)
    }

    @Test
    fun `View details opens Details for the card's title and nothing else`() {
        menu().actions.first { it.label == CONTINUE_WATCHING_VIEW_DETAILS }.onClick()

        assertEquals(listOf("details"), calls)
        assertEquals(listOf<HomeDestination>(HomeDestination.Details(item.media)), opened)
    }

    @Test
    fun `View details opens Details even with Auto-play from Continue Watching on`() {
        // A plain click would play straight away…
        assertEquals(HomeDestination.Play(item), continueWatchingDestination(item, autoPlay = true))
        // …but the menu entry always leads to Details.
        menu().actions.first { it.label == CONTINUE_WATCHING_VIEW_DETAILS }.onClick()
        assertEquals(listOf<HomeDestination>(HomeDestination.Details(item.media)), opened)
    }

    @Test
    fun `Remove and Clear progress keep their own actions`() {
        val actions = menu().actions.associateBy { it.label }
        actions.getValue(CONTINUE_WATCHING_REMOVE).onClick()
        actions.getValue(CONTINUE_WATCHING_CLEAR_PROGRESS).onClick()

        assertEquals(listOf("remove", "clear"), calls)
    }

    @Test
    fun `a Next Up card offers View details and Remove only`() {
        val nextUp = item.copy(progress = 0f, nextUp = NextUpMarker(null))
        val request = continueWatchingMenu(nextUp, card, onViewDetails = {}, onRemove = { calls += "remove" }, onClearProgress = { calls += "clear" })

        assertEquals(listOf(CONTINUE_WATCHING_VIEW_DETAILS, CONTINUE_WATCHING_REMOVE), request.actions.map { it.label })
        assertEquals("S7:E10", request.subtitle)
    }
}
