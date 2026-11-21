package com.seastreams.tv.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every catalog row's stationary 16:9 showcase slot shows its last-focused media item (`0` by
 * default) and draws the focus ring only while a media poster of that row holds D-pad focus.
 */
class RowShowcaseSlotTest {

    private val rowA = List(10) { "MOVIE-a$it" }
    private val rowB = List(10) { "MOVIE-b$it" }

    private fun shown(memory: RowFocusMemory, rowKey: String, keys: List<String>): Int =
        slotIndex(memory.stateFor(rowKey, keys).intValue, keys.size)

    @Test
    fun `a newly loaded row shows item 0 in its slot without the ring`() {
        val memory = RowFocusMemory()

        assertEquals(0, shown(memory, "a", rowA))
        assertFalse(slotFocusRing(rowFocused = false, viewMoreFocused = false))
    }

    @Test
    fun `leaving row A from card 3 keeps card 3 in its slot without the focus ring`() {
        val memory = RowFocusMemory()
        // Focus card 3 of row A (the poster's onFocused writes the row's state).
        memory.stateFor("a", rowA).intValue = 3
        assertEquals(3, shown(memory, "a", rowA))
        assertTrue(slotFocusRing(rowFocused = true, viewMoreFocused = false))

        // `Down` to row B: its card 0 takes focus and the ring; row A's slot keeps card 3, ring off.
        memory.stateFor("b", rowB).intValue = 0
        assertEquals(0, shown(memory, "b", rowB))
        assertEquals(3, shown(memory, "a", rowA))
        assertFalse(slotFocusRing(rowFocused = false, viewMoreFocused = false))
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
    fun `a long-press menu opened from the row keeps the long-pressed card's info panel and slot`() {
        val memory = RowFocusMemory()
        // Long-press card 3: it held focus, then the menu takes focus out of the row.
        memory.stateFor("cw", rowA).intValue = 3

        assertTrue(rowInfoVisible(rowFocused = false, holdInfo = true))
        assertEquals(3, shown(memory, "cw", rowA))
        assertFalse(slotFocusRing(rowFocused = false, viewMoreFocused = false))

        // `Back`: focus returns to card 3 before the hold is released, so the panel never blinks out.
        assertTrue(rowInfoVisible(rowFocused = true, holdInfo = true))
        assertTrue(rowInfoVisible(rowFocused = true, holdInfo = false))
        assertTrue(slotFocusRing(rowFocused = true, viewMoreFocused = false))
    }

    @Test
    fun `View More draws its own ring and leaves the slot on the last media item`() {
        // Focus on View More (index == items.size): the slot keeps the last media item, ring off.
        assertEquals(19, slotIndex(lastFocused = 20, itemCount = 20))
        assertFalse(slotFocusRing(rowFocused = true, viewMoreFocused = true))
        assertEquals(0, slotIndex(lastFocused = -2, itemCount = 20))
        assertEquals(NO_SLOT_ITEM, slotIndex(lastFocused = 0, itemCount = 0))
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
