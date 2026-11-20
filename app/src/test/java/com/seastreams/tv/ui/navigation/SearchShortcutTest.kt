package com.seastreams.tv.ui.navigation

import com.seastreams.tv.domain.model.CatalogSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Long-press `Back` → Search keeps the page beneath, and never stacks or pops Search (PRD §1). */
class SearchShortcutTest {

    @Test
    fun `from a tab, Search is pushed`() {
        assertEquals(listOf(Overlay.Search), overlaysForSearch(emptyList()))
    }

    @Test
    fun `from View More, Search goes on top so Back returns to View More`() {
        val viewMore = Overlay.ViewMore("Popular", SOURCE)
        assertEquals(listOf(viewMore, Overlay.Search), overlaysForSearch(listOf(viewMore)))
    }

    @Test
    fun `already on Search, the stack is unchanged`() {
        val stack = listOf(Overlay.ViewMore("Popular", SOURCE), Overlay.Search)
        assertEquals(stack, overlaysForSearch(stack))
    }

    private companion object {
        val SOURCE: CatalogSource = CatalogSource.MdbList("https://mdblist.com/lists/example/popular")
    }
}
