package com.seastreams.tv.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PRD §1 Watched Artwork Indicator: checkmark for finished artwork, bottom bar only while in progress. */
class WatchedIndicatorTest {

    @Test
    fun `untouched or unknown-duration artwork shows nothing`() {
        assertEquals(ArtworkProgress.None, artworkProgress(positionMs = 0, durationMs = 1_000))
        assertEquals(ArtworkProgress.None, artworkProgress(positionMs = 500, durationMs = 0))
        assertEquals(ArtworkProgress.None, artworkProgress(0f))
    }

    @Test
    fun `in-progress artwork under 90 percent keeps its partial bottom bar and no checkmark`() {
        val half = artworkProgress(positionMs = 500, durationMs = 1_000)
        assertEquals(ArtworkProgress.InProgress(0.5f), half)

        val almost = artworkProgress(positionMs = 899, durationMs = 1_000)
        assertTrue(almost is ArtworkProgress.InProgress)
        assertEquals(0.899f, (almost as ArtworkProgress.InProgress).fraction, 1e-6f)

        assertEquals(ArtworkProgress.InProgress(0.42f), artworkProgress(0.42f))
    }

    @Test
    fun `artwork at or past 90 percent shows the checkmark instead of a full bottom line`() {
        assertEquals(ArtworkProgress.Watched, artworkProgress(positionMs = 900, durationMs = 1_000))
        assertEquals(ArtworkProgress.Watched, artworkProgress(positionMs = 990, durationMs = 1_000))
        assertEquals(ArtworkProgress.Watched, artworkProgress(0.9f))
    }

    @Test
    fun `manually marked watched (position equals duration) shows the checkmark`() {
        assertEquals(ArtworkProgress.Watched, artworkProgress(positionMs = 2_700_000, durationMs = 2_700_000))
        assertEquals(ArtworkProgress.Watched, artworkProgress(1f))
    }
}
