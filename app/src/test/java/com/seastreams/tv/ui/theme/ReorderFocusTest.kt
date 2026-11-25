package com.seastreams.tv.ui.theme

import androidx.compose.foundation.lazy.LazyListState
import com.seastreams.tv.domain.streams.FormatterId
import com.seastreams.tv.ui.screens.settings.streams.formatterEntryIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReorderFocusTest {

    @Test
    fun `the formatter panel opens on the chosen option, even the last one`() {
        assertEquals(FormatterId.entries.lastIndex, formatterEntryIndex(FormatterId.entries, FormatterId.CUSTOM))
        assertEquals(0, formatterEntryIndex(FormatterId.entries, FormatterId.ORIGINAL))
    }

    @Test
    fun `focus stays on the pressed button, switching at the ends`() {
        assertEquals(MoveButton.DOWN, moveButtonAfter(-1, position = 0, count = 20))
        assertEquals(MoveButton.UP, moveButtonAfter(-1, position = 9, count = 20))
        assertEquals(MoveButton.DOWN, moveButtonAfter(1, position = 10, count = 20))
        assertEquals(MoveButton.UP, moveButtonAfter(1, position = 19, count = 20))
    }

    @Test
    fun `a move waits for the saved order and a no-op at the edge resolves at once`() {
        val focus = ReorderFocus<String>(LazyListState())
        focus.onMove("b", -1, listOf("a", "b", "c"))
        assertEquals(PendingMove("b", 0, FollowTarget.Button(-1, 3)), focus.pending)
        focus.onMove("a", -1, listOf("a", "b", "c"))
        assertEquals(PendingMove("a", 0, FollowTarget.Button(-1, 3)), focus.pending)
        focus.onMove("z", 1, listOf("a"))
        assertEquals(PendingMove("a", 0, FollowTarget.Button(-1, 3)), focus.pending)
        focus.clear()
        assertNull(focus.pending)
    }

    @Test
    fun `a re-sorted row is followed to its new position and focuses the row, not a move button`() {
        val focus = ReorderFocus<String>(LazyListState())
        focus.onReorder("4K", 2)
        assertEquals(PendingMove("4K", 2, FollowTarget.Row), focus.pending)
        assertNull(focusTarget(checkNotNull(focus.pending)))
        assertSame(focus.row("4K"), focus.requester("4K", null))
        focus.onReorder("x", -1)
        assertEquals(PendingMove("4K", 2, FollowTarget.Row), focus.pending)
    }

    @Test
    fun `the move button uses the reorderable count, not the whole list`() {
        // 3 chosen values lead a 20-row list: Move down to the third chosen slot is the end of the reorderable block.
        assertEquals(MoveButton.UP, focusTarget(PendingMove("a", 2, FollowTarget.Button(1, movableCount = 3))))
        assertEquals(MoveButton.DOWN, focusTarget(PendingMove("a", 1, FollowTarget.Button(1, movableCount = 3))))
        assertEquals(MoveButton.DOWN, focusTarget(PendingMove("a", 0, FollowTarget.Button(-1, movableCount = 3))))
    }
}
