package com.seastreams.tv.ui.navigation

import com.seastreams.tv.ui.components.NavTab
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
}
