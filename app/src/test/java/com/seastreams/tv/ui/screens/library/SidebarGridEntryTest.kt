package com.seastreams.tv.ui.screens.library

import com.seastreams.tv.domain.usecase.LibraryFilter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `Right` from a Library filter whose grid switch is still settling (PRD §7). */
class SidebarGridEntryTest {

    @Test
    fun `with nothing pending Right is left to ordinary focus search`() {
        val entry = SidebarGridEntry()

        assertFalse(entry.onRight(LibraryFilter.MOVIES) { false })
        assertFalse(entry.awaiting(LibraryFilter.MOVIES))
    }

    @Test
    fun `a Right that applied a pending switch waits for that filter's grid, once`() {
        val entry = SidebarGridEntry()

        assertTrue(entry.onRight(LibraryFilter.TV_SHOWS) { true })
        assertFalse(entry.awaiting(LibraryFilter.ALL), "the grid being replaced is never focused")
        assertTrue(entry.awaiting(LibraryFilter.TV_SHOWS))
        assertFalse(entry.awaiting(LibraryFilter.TV_SHOWS))
    }
}
