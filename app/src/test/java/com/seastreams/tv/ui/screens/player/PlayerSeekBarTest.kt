package com.seastreams.tv.ui.screens.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Seek bar tier clamping (PRD §9 Seek Bar Styling): `0 <= played <= buffered <= 1`. */
class PlayerSeekBarTest {

    @Test
    fun `played and buffered are fractions of the duration`() {
        assertEquals(SeekBarFractions(0.25f, 0.5f), seekBarFractions(positionMs = 25_000, bufferedMs = 50_000, durationMs = 100_000))
    }

    @Test
    fun `buffered behind the playhead collapses onto it`() {
        assertEquals(SeekBarFractions(0.6f, 0.6f), seekBarFractions(positionMs = 60_000, bufferedMs = 10_000, durationMs = 100_000))
    }

    @Test
    fun `buffered past the end clamps to the full bar`() {
        assertEquals(SeekBarFractions(0.5f, 1f), seekBarFractions(positionMs = 50_000, bufferedMs = 150_000, durationMs = 100_000))
    }

    @Test
    fun `position past the end fills the bar`() {
        assertEquals(SeekBarFractions(1f, 1f), seekBarFractions(positionMs = 120_000, bufferedMs = 90_000, durationMs = 100_000))
    }

    @Test
    fun `unknown duration leaves only the track`() {
        assertEquals(SeekBarFractions(0f, 0f), seekBarFractions(positionMs = 5_000, bufferedMs = 30_000, durationMs = 0))
        assertEquals(SeekBarFractions(0f, 0f), seekBarFractions(positionMs = 5_000, bufferedMs = 30_000, durationMs = -1))
    }

    @Test
    fun `negative inputs coerce to zero`() {
        assertEquals(SeekBarFractions(0f, 0f), seekBarFractions(positionMs = -5_000, bufferedMs = -1, durationMs = 100_000))
        assertEquals(SeekBarFractions(0f, 0.2f), seekBarFractions(positionMs = -5_000, bufferedMs = 20_000, durationMs = 100_000))
    }

    @Test
    fun `scrub marker behind the playhead starts the buffered tier at the marker`() {
        assertEquals(SeekBarFractions(0.1f, 0.4f), seekBarFractions(positionMs = 10_000, bufferedMs = 40_000, durationMs = 100_000))
    }
}
