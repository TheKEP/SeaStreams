package com.seastreams.tv.ui.components

import androidx.compose.ui.focus.FocusDirection
import com.seastreams.tv.ui.components.NavTab.HOME
import com.seastreams.tv.ui.components.NavTab.LIBRARY
import com.seastreams.tv.ui.components.NavTab.MOVIES
import com.seastreams.tv.ui.components.NavTab.SEARCH
import com.seastreams.tv.ui.components.NavTab.SETTINGS
import com.seastreams.tv.ui.components.NavTab.TV_SHOWS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Top navigation bar pills (PRD §1). The full bar loop is Avatar → pills: the avatar carries the
 * `Left` wrap edge (to Settings) and Settings, which must stay the last pill for every profile type,
 * carries the `Right` wrap edge (back to the avatar). Search is the pill next to the avatar.
 *
 * Focus entry ([navBarEntry]) follows the Settings repro: open Settings → `Back` (focus restored to the
 * gear, a programmatic `Enter` request) → `Down` into the content → `Back` (a programmatic `Enter`
 * request on the active pill). Neither request may be redirected to a remembered pill, so the
 * second `Back` lands on Home / Movies / TV Shows / Library and never on the Settings gear.
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

    @Test
    fun `closing Settings keeps the request that restores focus to the gear`() {
        assertEquals(NavBarEntry.KEEP_REQUESTED, navBarEntry(FocusDirection.Enter))
    }

    @Test
    fun `Back from content after closing Settings keeps the active pill request instead of the stale gear`() {
        // Settings → Back (gear focused) → Down into content → Back requests the active pill directly.
        listOf(HOME, MOVIES, TV_SHOWS, LIBRARY).forEach { active ->
            assertEquals(NavBarEntry.KEEP_REQUESTED, navBarEntry(FocusDirection.Enter), "active=$active")
        }
    }

    @Test
    fun `D-pad move into the bar from content always lands on the active pill`() {
        assertEquals(NavBarEntry.ACTIVE_PILL, navBarEntry(FocusDirection.Up))
        assertEquals(NavBarEntry.ACTIVE_PILL, navBarEntry(FocusDirection.Down))
    }

    @Test
    fun `sideways and exit moves keep their target so the Avatar-Settings wrap is untouched`() {
        listOf(FocusDirection.Left, FocusDirection.Right, FocusDirection.Exit, FocusDirection.Next, FocusDirection.Previous)
            .forEach { assertEquals(NavBarEntry.KEEP_REQUESTED, navBarEntry(it), "direction=$it") }
    }

    @Test
    fun `every top-level screen's active pill is rendered for the profiles that can open it`() {
        listOf(HOME, MOVIES, TV_SHOWS, LIBRARY).forEach { tab ->
            assertTrue(tab in NavTab.visibleFor(isKids = false), "adult $tab")
        }
        listOf(HOME, LIBRARY).forEach { tab -> assertTrue(tab in NavTab.visibleFor(isKids = true), "kids $tab") }
    }
}
