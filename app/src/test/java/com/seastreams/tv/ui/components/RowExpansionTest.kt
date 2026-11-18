package com.seastreams.tv.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every catalog row keeps exactly one 16:9 wide card (its last-focused media card, `0` by default),
 * while only the card holding D-pad focus draws the focus ring.
 */
class RowExpansionTest {

    private val rowA = List(10) { "MOVIE-a$it" }
    private val rowB = List(10) { "MOVIE-b$it" }

    /** The appearance of card [index] in a row, the way `ExpandingCatalogRow` computes it. */
    private fun appearance(memory: RowFocusMemory, rowKey: String, keys: List<String>, index: Int, focusedCard: Int?): CardAppearance {
        val expanded = expandedCardIndex(memory.stateFor(rowKey, keys).intValue, keys.size, viewMoreFocused = false)
        return cardAppearance(wide = index == expanded, focused = index == focusedCard)
    }

    @Test
    fun `a newly loaded row renders card 0 wide and every other card as a poster`() {
        val memory = RowFocusMemory()

        assertEquals(CardAppearance(wide = true, focusRing = false), appearance(memory, "a", rowA, 0, focusedCard = null))
        (1 until rowA.size).forEach { index ->
            assertEquals(CardAppearance(wide = false, focusRing = false), appearance(memory, "a", rowA, index, focusedCard = null))
        }
    }

    @Test
    fun `leaving row A from card 3 keeps card 3 wide there without the focus ring`() {
        val memory = RowFocusMemory()
        // Focus card 3 of row A (the card's onFocused writes the row's state).
        memory.stateFor("a", rowA).intValue = 3
        assertEquals(CardAppearance(wide = true, focusRing = true), appearance(memory, "a", rowA, 3, focusedCard = 3))

        // `Down` to row B: its card 0 takes focus and the ring; row A has no focused card any more.
        memory.stateFor("b", rowB).intValue = 0
        assertEquals(CardAppearance(wide = true, focusRing = true), appearance(memory, "b", rowB, 0, focusedCard = 0))
        assertEquals(CardAppearance(wide = true, focusRing = false), appearance(memory, "a", rowA, 3, focusedCard = null))
        assertEquals(CardAppearance(wide = false, focusRing = false), appearance(memory, "a", rowA, 0, focusedCard = null))
    }

    @Test
    fun `a refreshed row starts over at card 0`() {
        val memory = RowFocusMemory()
        memory.stateFor("a", rowA).intValue = 4

        assertEquals(4, memory.stateFor("a", rowA).intValue)
        assertEquals(0, memory.stateFor("a", rowA.reversed()).intValue)
    }

    @Test
    fun `the info panel shows while the row holds focus and hides once focus leaves the row`() {
        assertTrue(rowInfoVisible(rowFocused = true, holdInfo = false))
        assertFalse(rowInfoVisible(rowFocused = false, holdInfo = false))
    }

    @Test
    fun `a long-press menu opened from the row keeps the long-pressed card's info panel and wide card`() {
        val memory = RowFocusMemory()
        // Long-press card 3: it held focus, then the menu takes focus out of the row.
        memory.stateFor("cw", rowA).intValue = 3

        assertTrue(rowInfoVisible(rowFocused = false, holdInfo = true))
        assertEquals(CardAppearance(wide = true, focusRing = false), appearance(memory, "cw", rowA, 3, focusedCard = null))

        // `Back`: focus returns to card 3 before the hold is released, so the panel never blinks out.
        assertTrue(rowInfoVisible(rowFocused = true, holdInfo = true))
        assertTrue(rowInfoVisible(rowFocused = true, holdInfo = false))
        assertEquals(CardAppearance(wide = true, focusRing = true), appearance(memory, "cw", rowA, 3, focusedCard = 3))
    }

    @Test
    fun `View More is only wide while focused and never leaves an empty wide card behind`() {
        // Focus on View More (index == items.size): it is the row's only wide card.
        assertEquals(NO_EXPANDED_CARD, expandedCardIndex(lastFocused = 20, itemCount = 20, viewMoreFocused = true))
        // Focus leaves the row from View More: the wide card falls back to the last media card.
        assertEquals(19, expandedCardIndex(lastFocused = 20, itemCount = 20, viewMoreFocused = false))
        assertEquals(0, expandedCardIndex(lastFocused = -2, itemCount = 20, viewMoreFocused = false))
        assertEquals(NO_EXPANDED_CARD, expandedCardIndex(lastFocused = 0, itemCount = 0, viewMoreFocused = false))
    }

    @Test
    fun `a capped row ends on View More at index 20 and a short row ends on its last card`() {
        // 20 media cards + View More: position 21 (index 20) is where Right wraps back to card 0.
        assertEquals(20, rowLastIndex(itemCount = 20, hasMore = true))
        // ≤20 cards and no View More: the last media card is the wrap point.
        assertEquals(19, rowLastIndex(itemCount = 20, hasMore = false))
        assertEquals(11, rowLastIndex(itemCount = 12, hasMore = false))
        assertEquals(20, restoreIndex(lastFocused = 25, lastIndex = rowLastIndex(20, hasMore = true)))
    }
}
