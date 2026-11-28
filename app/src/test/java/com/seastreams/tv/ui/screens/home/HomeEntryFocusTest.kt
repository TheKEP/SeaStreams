package com.seastreams.tv.ui.screens.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Home entry focus (PRD §3): the `Home` pill on first load and tab return, never the hero. */
class HomeEntryFocusTest {

    @Test
    fun `first load and tab return focus the Home pill`() {
        assertEquals(HomeEntryFocus.HOME_PILL, homeEntryFocus(returnRowId = null))
    }

    @Test
    fun `back from a row's View More restores that row instead`() {
        assertEquals(HomeEntryFocus.RETURN_ROW, homeEntryFocus(returnRowId = "trending"))
    }

    @Test
    fun `the hero is never an entry target`() {
        assertEquals(setOf(HomeEntryFocus.HOME_PILL, HomeEntryFocus.RETURN_ROW), HomeEntryFocus.entries.toSet())
    }
}
