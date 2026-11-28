package com.seastreams.tv.ui.components

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The fixed-slot strip: the next poster sits right next to the slot, the previous one peeks in left of it. */
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
    fun `no strip poster up to the focused one ever shows beside the slot`() {
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

    @Test
    fun `poster and backdrop share one height`() {
        assertEquals(CardHeight, PosterSize.height)
        assertEquals(CardHeight, ExpandedSize.height)
    }

    @Test
    fun `the strip position is fractional between items`() {
        assertEquals(0f, stripPosition(firstIndex = 0, firstOffsetPx = 0, pitchPx = 100f))
        assertEquals(3.25f, stripPosition(firstIndex = 3, firstOffsetPx = 25, pitchPx = 100f))
        assertEquals(2f, stripPosition(firstIndex = 2, firstOffsetPx = 40, pitchPx = 0f))
    }

    @Test
    fun `the peek draws the previous poster and the one behind the slot`() {
        assertEquals(0..0, peekItems(base = 0, itemCount = 20))
        assertEquals(4..5, peekItems(base = 5, itemCount = 20))
        // View More focused: the last media card (19) is under the slot, 18 peeks.
        assertEquals(18..19, peekItems(base = 19, itemCount = 20))
        assertEquals(0..0, peekItems(base = 0, itemCount = 1))
        assertTrue(peekItems(base = 3, itemCount = 0).isEmpty())
        assertTrue(peekItems(base = -1, itemCount = 20).isEmpty())
    }

    @Test
    fun `at rest the previous poster bleeds off the left edge and stops a gap short of the slot`() {
        listOf(1, 6, 19).forEach { focused ->
            val previous = peekPosterX(focused - 1, focused.toFloat())
            assertEquals(PeekRestX, previous)
            assertTrue(previous < 0.dp, "it must bleed off the screen edge")
            assertEquals(PeekVisibleWidth, previous + PosterWidth)
            assertEquals(RowGutter - CardSpacing, previous + PosterWidth)
            // The focused poster's peek copy sits at the slot's left edge, wholly clipped away.
            assertEquals(RowGutter, peekPosterX(focused, focused.toFloat()))
            assertTrue(peekPosterX(focused, focused.toFloat()) >= PeekClipRight)
        }
    }

    @Test
    fun `nothing peeks while the first item is under the slot`() {
        peekItems(base = 0, itemCount = 20).forEach { index ->
            assertTrue(peekPosterX(index, position = 0f) >= PeekClipRight)
        }
    }

    @Test
    fun `mid-glide the peek moves one pitch per item and never reaches the slot`() {
        val half = peekPosterX(index = 4, position = 4.5f)
        assertEquals(PeekRestX + PosterPitch * 0.5f, half)
        assertEquals(PosterPitch, peekPosterX(4, 4f) - peekPosterX(4, 5f))
        // The clip edge leaves the 3dp ring and the slot's rounded corner untouched.
        assertEquals(RowGutter - CardSpacing, PeekClipRight)
        assertTrue(PeekClipRight + CardSpacing <= RowGutter)
    }
}
