package com.seastreams.tv.ui.components

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The fixed-slot strip: the next poster always sits right next to the slot, none ever to its left. */
class CatalogStripGeometryTest {

    @Test
    fun `the strip lead is the slot width minus one poster`() {
        assertEquals(243.dp, StripLead)
        assertEquals(157.dp, PosterPitch)
    }

    @Test
    fun `the next poster sits right next to the slot`() {
        listOf(0, 1, 7, 19).forEach { focused ->
            assertEquals(ExpandedCardWidth + CardSpacing, posterStartX(focused + 1, focused))
        }
    }

    @Test
    fun `the focused poster ends exactly under the slot's right edge`() {
        listOf(0, 5, 19).forEach { focused ->
            assertEquals(ExpandedCardWidth, posterStartX(focused, focused) + PosterWidth)
        }
    }

    @Test
    fun `no poster up to the focused one ever shows beside or left of the slot`() {
        val focused = 6
        (0..focused).forEach { index ->
            assertTrue(posterStartX(index, focused) + PosterWidth <= ExpandedCardWidth)
        }
        // Every poster after it starts past the slot and its gap, one pitch apart.
        (focused + 1..focused + 4).forEach { index ->
            assertTrue(posterStartX(index, focused) >= ExpandedCardWidth + CardSpacing)
        }
    }

    @Test
    fun `the strip scrolls the focused card under the slot`() {
        assertEquals(0, stripScrollIndex(focusedIndex = 0, itemCount = 20, viewMoreFocused = false))
        assertEquals(7, stripScrollIndex(focusedIndex = 7, itemCount = 20, viewMoreFocused = false))
        // A remembered View More position (index 20) clamps to the last media card.
        assertEquals(19, stripScrollIndex(focusedIndex = 20, itemCount = 20, viewMoreFocused = false))
        assertEquals(0, stripScrollIndex(focusedIndex = -3, itemCount = 20, viewMoreFocused = false))
        assertEquals(0, stripScrollIndex(focusedIndex = 4, itemCount = 0, viewMoreFocused = false))
    }

    @Test
    fun `with View More focused the last media card goes under the slot so View More shows beside it`() {
        val scroll = stripScrollIndex(focusedIndex = 20, itemCount = 20, viewMoreFocused = true)
        assertEquals(19, scroll)
        assertEquals(ExpandedCardWidth + CardSpacing, posterStartX(index = 20, scrollIndex = scroll))
    }

    @Test
    fun `the slot ring shows only while a media poster of the row holds focus`() {
        assertTrue(slotFocusRing(rowFocused = true, viewMoreFocused = false))
        assertEquals(false, slotFocusRing(rowFocused = true, viewMoreFocused = true))
        assertEquals(false, slotFocusRing(rowFocused = false, viewMoreFocused = false))
    }
}
