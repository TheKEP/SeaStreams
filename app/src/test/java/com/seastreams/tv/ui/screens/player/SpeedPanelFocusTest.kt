package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.core.datastore.PlaybackSpeed
import com.seastreams.tv.ui.theme.ModelList
import com.seastreams.tv.ui.theme.revealItem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PRD §9 Speed overlay: it opens with the active speed fully on screen (so its focus pill is visible), even when that
 * row starts below a short card's viewport. Drives the real [revealItem] that `focusItem` runs before focusing.
 */
class SpeedPanelFocusTest {

    /** The overlay's lazy rows: the "This video only" header, then one row per speed. */
    private val rows = listOf("h-speed") + PlaybackSpeed.entries.map { "speed-${it.name}" }

    /** A short card: 56 px rows, 8 px spacing, 4 px content padding, room for about four rows. */
    private fun shortCard() = ModelList(keys = rows, rowSize = 56, spacing = 8, viewport = 260, padBefore = 4, padAfter = 4)

    @Test
    fun `each speed's row follows the header in lazy order`() {
        PlaybackSpeed.entries.forEach { speed ->
            assertEquals("speed-${speed.name}", rows[speedFocusIndex(speed)], speed.name)
        }
        assertEquals(1, speedFocusIndex(PlaybackSpeed.MINUS_25))
        assertEquals(rows.lastIndex, speedFocusIndex(PlaybackSpeed.PLUS_100))
    }

    @Test
    fun `+100 percent starts below the viewport and is scrolled fully into view on open`() = runTest {
        val card = shortCard()
        val index = speedFocusIndex(PlaybackSpeed.PLUS_100)
        assertFalse(card.fullyVisible(index), "precondition: the last speed starts off-screen")
        assertTrue(card.revealItem(index, awaitFrame = {}))
        assertTrue(card.fullyVisible(index))
        // Smallest move: the row ends flush with the content end, above the bottom padding.
        assertEquals(card.contentEnd, card.span(index)?.let { it.offset + it.size })
    }

    @Test
    fun `every speed opens fully visible`() = runTest {
        PlaybackSpeed.entries.forEach { speed ->
            val card = shortCard()
            val index = speedFocusIndex(speed)
            assertTrue(card.revealItem(index, awaitFrame = {}), speed.name)
            assertTrue(card.fullyVisible(index), speed.name)
        }
    }
}
