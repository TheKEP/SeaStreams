package com.seastreams.tv.ui.navigation

import androidx.compose.ui.focus.FocusRequester
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CoverStackTest {

    @Test
    fun `Back on Profiles opened from Settings returns to Settings on the Profiles row`() {
        val gear = FocusRequester()
        val profilesRow = FocusRequester()
        val settings = CoverStack.push(emptyList(), Cover.Settings, gear, id = 0)
        val withProfiles = CoverStack.push(settings, Cover.Profiles, profilesRow, id = 1)
        assertEquals(listOf(Cover.Settings, Cover.Profiles), withProfiles.map { it.cover })

        val back = CoverStack.pop(withProfiles)
        requireNotNull(back)
        assertEquals(listOf(Cover.Settings), back.remaining.map { it.cover })
        assertSame(profilesRow, back.refocus)

        // A second Back closes Settings onto the gear that opened it.
        val exit = CoverStack.pop(back.remaining)
        requireNotNull(exit)
        assertEquals(emptyList<CoverEntry>(), exit.remaining)
        assertSame(gear, exit.refocus)
    }

    @Test
    fun `popping with nothing open does nothing`() {
        assertNull(CoverStack.pop(emptyList()))
    }
}
