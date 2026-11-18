package com.seastreams.tv.ui.components

import com.seastreams.tv.ui.components.NavTab.HOME
import com.seastreams.tv.ui.components.NavTab.LIBRARY
import com.seastreams.tv.ui.components.NavTab.MOVIES
import com.seastreams.tv.ui.components.NavTab.SEARCH
import com.seastreams.tv.ui.components.NavTab.SETTINGS
import com.seastreams.tv.ui.components.NavTab.TV_SHOWS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Top navigation bar pills (PRD §1). The full bar loop is Avatar → pills: the avatar carries the
 * `Left` wrap edge (to Settings) and Settings, which must stay the last pill for every profile type,
 * carries the `Right` wrap edge (back to the avatar). Search is the pill next to the avatar.
 */
class TopNavBarTest {

    @Test
    fun `adult profile bar is Search, Home, Movies, TV Shows, Library, Settings`() {
        assertEquals(listOf(SEARCH, HOME, MOVIES, TV_SHOWS, LIBRARY, SETTINGS), NavTab.visibleFor(isKids = false))
    }

    @Test
    fun `kids profile bar hides Movies and TV Shows`() {
        assertEquals(listOf(SEARCH, HOME, LIBRARY, SETTINGS), NavTab.visibleFor(isKids = true))
    }

    @Test
    fun `Settings is the right wrap edge and Search sits next to the avatar for every profile type`() {
        listOf(false, true).forEach { isKids ->
            val pills = NavTab.visibleFor(isKids)
            assertEquals(SETTINGS, pills.last(), "kids=$isKids")
            assertEquals(SEARCH, pills.first(), "kids=$isKids")
        }
    }
}
