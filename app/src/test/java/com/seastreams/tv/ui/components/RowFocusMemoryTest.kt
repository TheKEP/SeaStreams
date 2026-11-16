package com.seastreams.tv.ui.components

import androidx.compose.ui.focus.FocusDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RowFocusMemoryTest {

    @Test
    fun `an unvisited row defaults to card 0`() {
        assertEquals(0, RowFocusMemory().lastFocused("row-a"))
    }

    @Test
    fun `a row keeps the same state across recompositions (and disposal) of the row`() {
        val memory = RowFocusMemory()
        val first = memory.stateFor("row-a")
        first.intValue = 4

        assertSame(first, memory.stateFor("row-a"))
        assertEquals(4, memory.lastFocused("row-a"))
    }

    @Test
    fun `vertical entry restores the last card, scrolling first when it is off screen`() {
        assertEquals(RowEntry.FOCUS_LAST, rowEntry(FocusDirection.Up, targetVisible = true))
        assertEquals(RowEntry.FOCUS_LAST, rowEntry(FocusDirection.Down, targetVisible = true))
        assertEquals(RowEntry.SCROLL_THEN_FOCUS_LAST, rowEntry(FocusDirection.Up, targetVisible = false))
    }

    @Test
    fun `programmatic and horizontal entries keep their own target`() {
        assertEquals(RowEntry.DEFAULT, rowEntry(FocusDirection.Enter, targetVisible = true))
        assertEquals(RowEntry.DEFAULT, rowEntry(FocusDirection.Left, targetVisible = false))
        assertEquals(RowEntry.DEFAULT, rowEntry(FocusDirection.Right, targetVisible = true))
    }

    @Test
    fun `the restored card is clamped to a row that shrank`() {
        assertEquals(5, restoreIndex(lastFocused = 9, lastIndex = 5))
        assertEquals(3, restoreIndex(lastFocused = 3, lastIndex = 5))
        assertEquals(0, restoreIndex(lastFocused = 2, lastIndex = -1))
    }
}
