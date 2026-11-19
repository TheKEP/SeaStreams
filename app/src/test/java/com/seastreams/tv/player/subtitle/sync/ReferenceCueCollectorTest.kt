package com.seastreams.tv.player.subtitle.sync

import android.graphics.Bitmap
import androidx.media3.common.text.Cue
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceCueCollectorTest {

    private fun ReferenceSnapshot.pairs() = intervals.map { it.startUs to it.endUs }

    @Test
    fun `cue transitions become intervals inside one observed span`() {
        val collector = ReferenceCueCollector().apply { setActive(true) }
        collector.onCues(1_000_000, nonEmpty = true, playbackPositionUs = 1_000_000)
        collector.onCues(3_000_000, nonEmpty = false, playbackPositionUs = 3_000_000)
        collector.onCues(4_000_000, nonEmpty = true, playbackPositionUs = 4_000_000)
        collector.onCues(5_000_000, nonEmpty = true, playbackPositionUs = 5_000_000) // next line straight away
        collector.onCues(6_500_000, nonEmpty = false, playbackPositionUs = 6_500_000)

        val snapshot = collector.snapshot()
        assertEquals(listOf(1_000_000L to 3_000_000L, 4_000_000L to 5_000_000L, 5_000_000L to 6_500_000L), snapshot.pairs())
        assertEquals(listOf(ObservedSpan(1_000_000, 6_500_000)), snapshot.spans)
    }

    @Test
    fun `a seek drops the open cue and starts a new span`() {
        val collector = ReferenceCueCollector().apply { setActive(true) }
        collector.onCues(1_000_000, nonEmpty = true, playbackPositionUs = 1_000_000)
        collector.onCues(2_000_000, nonEmpty = false, playbackPositionUs = 2_000_000)
        collector.onCues(3_000_000, nonEmpty = true, playbackPositionUs = 3_000_000)
        collector.onDiscontinuity()
        collector.onCues(60_000_000, nonEmpty = false, playbackPositionUs = 60_000_000)
        collector.onCues(61_000_000, nonEmpty = true, playbackPositionUs = 61_000_000)
        collector.onCues(62_000_000, nonEmpty = false, playbackPositionUs = 62_000_000)

        val snapshot = collector.snapshot()
        assertEquals(listOf(1_000_000L to 2_000_000L, 61_000_000L to 62_000_000L), snapshot.pairs())
        assertEquals(listOf(ObservedSpan(1_000_000, 3_000_000), ObservedSpan(60_000_000, 62_000_000)), snapshot.spans)
    }

    @Test
    fun `PGS bitmap cues count as shown, empty and blank cues don't`() {
        val bitmap = Cue.Builder().setBitmap(mockk<Bitmap>(relaxed = true)).build()
        assertTrue(hasVisibleCue(listOf(bitmap)))
        assertTrue(hasVisibleCue(listOf(Cue.Builder().setText("Hello").build())))
        assertFalse(hasVisibleCue(listOf(Cue.Builder().setText("  ").build())))
        assertFalse(hasVisibleCue(emptyList()))
    }

    @Test
    fun `cue events left over from before a seek are ignored`() {
        val collector = ReferenceCueCollector().apply { setActive(true) }
        collector.onCues(1_000_000, nonEmpty = true, playbackPositionUs = 1_000_000)
        collector.onCues(2_000_000, nonEmpty = false, playbackPositionUs = 2_000_000)
        collector.onDiscontinuity() // seek to 60 s
        // Posted before the seek, delivered after it: stamped 2.5 s while the playhead is at 60 s.
        collector.onCues(2_500_000, nonEmpty = true, playbackPositionUs = 60_000_000)
        collector.onCues(61_000_000, nonEmpty = true, playbackPositionUs = 61_050_000)
        collector.onCues(62_000_000, nonEmpty = false, playbackPositionUs = 62_000_000)

        val snapshot = collector.snapshot()
        assertEquals(listOf(1_000_000L to 2_000_000L, 61_000_000L to 62_000_000L), snapshot.pairs())
        assertEquals(listOf(ObservedSpan(1_000_000, 2_000_000), ObservedSpan(61_000_000, 62_000_000)), snapshot.spans, "no span across the seek")
    }

    @Test
    fun `time going backwards is treated as a seek`() {
        val collector = ReferenceCueCollector().apply { setActive(true) }
        collector.onCues(10_000_000, nonEmpty = true, playbackPositionUs = 10_000_000)
        collector.onCues(2_000_000, nonEmpty = false, playbackPositionUs = 2_000_000)
        assertTrue(collector.snapshot().intervals.isEmpty())
    }

    @Test
    fun `nothing is recorded while inactive and reactivating clears`() {
        val collector = ReferenceCueCollector()
        collector.onCues(1_000_000, nonEmpty = true, playbackPositionUs = 1_000_000)
        collector.onCues(2_000_000, nonEmpty = false, playbackPositionUs = 2_000_000)
        assertTrue(collector.snapshot().intervals.isEmpty())

        collector.setActive(true)
        collector.onCues(1_000_000, nonEmpty = true, playbackPositionUs = 1_000_000)
        collector.onCues(2_000_000, nonEmpty = false, playbackPositionUs = 2_000_000)
        collector.setActive(true)
        assertTrue(collector.snapshot().intervals.isEmpty())
    }
}
