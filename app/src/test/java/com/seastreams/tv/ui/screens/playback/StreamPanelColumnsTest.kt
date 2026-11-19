package com.seastreams.tv.ui.screens.playback

import androidx.compose.ui.input.key.Key
import com.seastreams.tv.ui.theme.verticalEdgeKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** PRD §6 Stream Panel D-Pad Map: `Addons` ⇄ `Choose a stream` columns, each wrapping on its own (§1). */
class StreamPanelColumnsTest {

    @Test
    fun `right on an addon card moves to its first stream`() {
        assertEquals(ColumnMove.TO_FIRST_STREAM, columnSwitch(PanelColumn.ADDONS, Key.DirectionRight, addonHasStreams = true))
    }

    @Test
    fun `right on an addon that returned nothing keeps focus on its card`() {
        assertEquals(ColumnMove.HOLD, columnSwitch(PanelColumn.ADDONS, Key.DirectionRight, addonHasStreams = false))
    }

    @Test
    fun `left in the addons column is consumed so focus never leaves the panel`() {
        assertEquals(ColumnMove.HOLD, columnSwitch(PanelColumn.ADDONS, Key.DirectionLeft, addonHasStreams = true))
        assertEquals(ColumnMove.HOLD, columnSwitch(PanelColumn.ADDONS, Key.DirectionLeft, addonHasStreams = false))
    }

    @Test
    fun `left on a stream card returns to the selected addon card`() {
        assertEquals(ColumnMove.TO_ACTIVE_ADDON, columnSwitch(PanelColumn.STREAMS, Key.DirectionLeft, addonHasStreams = true))
    }

    @Test
    fun `right in the stream column is consumed`() {
        assertEquals(ColumnMove.HOLD, columnSwitch(PanelColumn.STREAMS, Key.DirectionRight, addonHasStreams = true))
    }

    @Test
    fun `up, down and OK are not column switches`() {
        for (column in PanelColumn.entries) {
            for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter)) {
                assertEquals(ColumnMove.PASS, columnSwitch(column, key, addonHasStreams = true), "$column $key")
            }
        }
    }

    @Test
    fun `each column owns its own vertical wrap edges`() {
        // Addons column with 4 cards / stream column with 3 cards: Up on the first wraps, Down on the last wraps.
        assertEquals(setOf(Key.DirectionUp), verticalEdgeKeys(index = 0, lastIndex = 3))
        assertEquals(setOf(Key.DirectionDown), verticalEdgeKeys(index = 3, lastIndex = 3))
        assertEquals(emptySet<Key>(), verticalEdgeKeys(index = 1, lastIndex = 2))
        // A single addon or a single stream consumes both directions and keeps focus.
        assertEquals(setOf(Key.DirectionUp, Key.DirectionDown), verticalEdgeKeys(index = 0, lastIndex = 0))
    }
}
