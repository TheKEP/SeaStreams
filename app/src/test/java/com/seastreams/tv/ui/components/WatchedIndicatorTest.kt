package com.seastreams.tv.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.seastreams.tv.ui.theme.ArtworkProgressBorder
import com.seastreams.tv.ui.theme.ArtworkProgressTrack
import com.seastreams.tv.ui.theme.ArtworkProgressWatched
import com.seastreams.tv.ui.theme.FocusBorderWidth
import com.seastreams.tv.ui.theme.Obsidian
import com.seastreams.tv.ui.theme.SeekBuffered
import com.seastreams.tv.ui.theme.SeekPlayed
import com.seastreams.tv.ui.theme.SeekTrack
import com.seastreams.tv.ui.theme.SeekTrackBorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `the bar flips to the checkmark exactly at 90 percent for both overloads`() {
        assertTrue(artworkProgress(positionMs = 8_999, durationMs = 10_000) is ArtworkProgress.InProgress)
        assertEquals(ArtworkProgress.Watched, artworkProgress(positionMs = 9_000, durationMs = 10_000))
        assertTrue(artworkProgress(0.899f) is ArtworkProgress.InProgress)
        assertEquals(ArtworkProgress.Watched, artworkProgress(0.90f))
    }

    @Test
    fun `out-of-range fractions and positions clamp instead of drawing a broken bar`() {
        assertEquals(ArtworkProgress.None, artworkProgress(-0.2f))
        assertEquals(ArtworkProgress.Watched, artworkProgress(1.5f))
        assertEquals(ArtworkProgress.None, artworkProgress(positionMs = -500, durationMs = 1_000))
        assertEquals(ArtworkProgress.Watched, artworkProgress(positionMs = 1_500, durationMs = 1_000))

        val sliver = artworkProgress(0.0001f)
        assertTrue(sliver is ArtworkProgress.InProgress, "a just-started title still gets its bar")
        val nearlyDone = artworkProgress(0.8999f) as ArtworkProgress.InProgress
        assertEquals(0.8999f, nearlyDone.fraction, 1e-4f)
        assertTrue(nearlyDone.fraction in 0f..1f)
    }

    @Test
    fun `the bar sits fully clear of the focused card's 3dp ring`() {
        assertTrue(progressBarClearsFocusRing(CardCorner, FocusBorderWidth, ProgressBarBottomInset, ProgressBarSideInset))
        assertTrue(ProgressBarBottomInset > FocusBorderWidth)
        assertEquals(ProgressBarBottomInset + ProgressBarHeight, ArtworkProgressClearance)
    }

    @Test
    fun `a flush or corner-hugging bar would be covered by the ring`() {
        assertFalse(progressBarClearsFocusRing(CardCorner, FocusBorderWidth, bottomInset = 0.dp, sideInset = 0.dp))
        assertFalse(progressBarClearsFocusRing(CardCorner, FocusBorderWidth, bottomInset = 3.dp, sideInset = 10.dp))
        // Above the ring's straight edge but out in the corner, where the ring curves up over it.
        assertFalse(progressBarClearsFocusRing(CardCorner, FocusBorderWidth, bottomInset = 5.dp, sideInset = 4.dp))
    }

    @Test
    fun `artwork bar has a white watched fill over a lighter slate track`() {
        assertEquals(Color.White, ArtworkProgressWatched)
        assertEquals(Color(0xFF4A5263), ArtworkProgressTrack)
        assertEquals(Obsidian, ArtworkProgressBorder)
        assertTrue(ArtworkProgressTrack.luminance() > Color(0xFF1E2430).luminance(), "lighter than the old #1E2430 track")
        assertTrue(contrast(ArtworkProgressTrack, ArtworkProgressBorder) >= 2.0, "track separates from the Obsidian backing")
        assertTrue(contrast(ArtworkProgressWatched, ArtworkProgressTrack) >= 4.5, "white fill separates from the track")
    }

    @Test
    fun `the player seek bar keeps its slate, blue buffered and white played tiers`() {
        assertEquals(Color(0xFF1E2430), SeekTrack)
        assertEquals(Color(0xFF2B6CB0), SeekBuffered)
        assertEquals(Color.White, SeekPlayed)
        assertEquals(Obsidian, SeekTrackBorder)
    }

    /** WCAG 2 contrast ratio. */
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private companion object {
        val CardCorner = 12.dp
    }
}
