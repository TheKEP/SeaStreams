package com.seastreams.tv.ui.theme

import androidx.compose.foundation.lazy.LazyListState
import com.seastreams.tv.domain.streams.FormatterId
import com.seastreams.tv.ui.screens.settings.streams.formatterEntryIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        assertEquals(PendingMove("b", -1, 0), focus.pending)
        focus.onMove("a", -1, listOf("a", "b", "c"))
        assertEquals(PendingMove("a", -1, 0), focus.pending)
        focus.onMove("z", 1, listOf("a"))
        assertEquals(PendingMove("a", -1, 0), focus.pending)
        focus.clear()
        assertNull(focus.pending)
    }
}
