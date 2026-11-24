package com.seastreams.tv.ui.screens.player

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PRD §9: the Subtitles, Audio, Speed and Info cards share one height (a clean gap above the HUD's seek row) and one
 * overflow scrollbar.
 */
class PlayerPanelScrollbarTest {

    // ---- Card height ------------------------------------------------------------------------------

    @Test
    fun `the cards reach lower than Streams and Episodes, yet stay above the seek row`() {
        assertTrue(CARD_BOTTOM_INSET < PANEL_BOTTOM_INSET, "cards extend below Streams/Episodes")
        // Bottom padding + action bar + spacing + seek row: a card never covers the remaining-time text.
        assertTrue(CARD_BOTTOM_INSET >= HUD_BOTTOM_PADDING + CIRCLE_SIZE + 14.dp + 24.dp + CARD_SEEK_GAP)
        assertEquals(CARD_BOTTOM_INSET, cardBottomInset(null))
    }

    @Test
    fun `a measured HUD block places the cards a clean gap above its seek row`() {
        // 24 padding + 20 seek row + 14 + 48 bar + 24 padding.
        assertEquals(130.dp - HUD_BOTTOM_PADDING + CARD_SEEK_GAP, cardBottomInset(130.dp))
        // A larger font scale (taller time label) lifts the cards with it.
        assertTrue(cardBottomInset(160.dp) > cardBottomInset(130.dp))
        assertEquals(CARD_BOTTOM_INSET, cardBottomInset(0.dp), "an unmeasured (zero) block falls back")
    }

    @Test
    fun `subtitles stay left of every extended card`() {
        listOf(PlayerPanel.SUBTITLES, PlayerPanel.AUDIO, PlayerPanel.SPEED, PlayerPanel.INFO).forEach { panel ->
            assertTrue(cueEndPadding(panel) > PanelWidth, "$panel")
        }
    }

    // ---- Scrollbar thumb --------------------------------------------------------------------------

    @Test
    fun `the scrollbar thumb is proportional and follows the scroll position`() {
        assertNull(scrollbarThumb(0, maxValue = 0, viewport = 400, trackPx = 400f, minThumbPx = 24f), "no overflow, no scrollbar")
        val top = scrollbarThumb(0, maxValue = 400, viewport = 400, trackPx = 400f, minThumbPx = 24f)
        assertEquals(ThumbSpan(0f, 200f), top, "half the content visible: half-length thumb at the top")
        assertEquals(ThumbSpan(100f, 200f), scrollbarThumb(200, 400, 400, 400f, 24f))
        assertEquals(ThumbSpan(200f, 200f), scrollbarThumb(400, 400, 400, 400f, 24f))
        val long = scrollbarThumb(0, maxValue = 100_000, viewport = 400, trackPx = 400f, minThumbPx = 24f)
        assertEquals(24f, long?.length, "a very long list keeps a visible thumb")
    }

    // ---- Lazy card metrics ------------------------------------------------------------------------

    /** Ten 100 px rows with 10 px spacing in a 500 px viewport: 1 090 px of content, 590 px of scroll. */
    private fun metrics(firstIndex: Int, firstOffset: Int, back: Boolean, forward: Boolean) = lazyScrollMetrics(
        firstIndex = firstIndex,
        firstOffset = firstOffset,
        visibleSizes = List(5) { 100 },
        totalCount = 10,
        viewport = 500,
        spacing = 10,
        canScrollBackward = back,
        canScrollForward = forward,
    )

    @Test
    fun `a card whose rows all fit has no scrollbar`() {
        assertNull(metrics(0, 0, back = false, forward = false))
        assertNull(lazyScrollMetrics(0, 0, emptyList(), 0, 500, 10, canScrollBackward = false, canScrollForward = true))
    }

    @Test
    fun `an overflowing card starts at the top, tracks the scroll, and ends exactly at the bottom`() {
        assertEquals(ScrollMetrics(0, 590, 500), metrics(0, 0, back = false, forward = true))
        assertEquals(ScrollMetrics(350, 590, 500), metrics(3, 20, back = true, forward = true))
        assertEquals(ScrollMetrics(590, 590, 500), metrics(5, 40, back = true, forward = false))
    }

    @Test
    fun `mid-list never reports the end, so the thumb only touches the bottom once the list does`() {
        val nearEnd = metrics(9, 90, back = true, forward = true)
        assertTrue((nearEnd?.value ?: 0) < (nearEnd?.maxValue ?: 0))
    }
}
