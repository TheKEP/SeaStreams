package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActivityGridTest {

    private fun bits(grid: ActivityGrid) = grid.active.joinToString("") { if (it) "1" else "0" }

    @Test
    fun `a cue fills every cell whose centre it covers`() {
        // 0.20 s – 0.50 s covers the centres of cells 2, 3, 4 (0.25, 0.35, 0.45).
        val grid = ActivityGrid.fromCues(listOf(TimedCue(200_000, 500_000, "a")), startCell = 0, size = 8)
        assertEquals("00111000", bits(grid))
        // 0.26 s – 0.44 s misses the centre of cell 2 and of cell 4.
        assertEquals("00010000", bits(ActivityGrid.fromCues(listOf(TimedCue(260_000, 440_000, "b")), 0, 8)))
    }

    @Test
    fun `overlapping cues, offsets and cues outside the window`() {
        val cues = listOf(TimedCue(1_000_000, 1_300_000, "a"), TimedCue(1_200_000, 1_500_000, "b"), TimedCue(9_000_000, 9_900_000, "far"))
        val grid = ActivityGrid.fromCues(cues, startCell = 9, size = 8)
        assertEquals("01111100", bits(grid))
        assertTrue(grid.covered.all { it })
    }

    @Test
    fun `observed spans limit coverage`() {
        val grid = ActivityGrid.fromCues(emptyList(), startCell = 0, size = 10, spans = listOf(ObservedSpan(200_000, 500_000)))
        assertArrayEquals(BooleanArray(10) { it in 2..4 }, grid.covered)
        assertEquals(3, grid.coveredCount)
    }

    @Test
    fun `voice activity is loud voice-band energy above the noise floor, with a hangover`() {
        // Quiet floor at -60 dB; cells 10-14 speech (-40 dB, voice ratio 0.8); cells 30-34 loud music (-35 dB, ratio 0.1).
        val size = 50
        val db = FloatArray(size) { -60f }
        val ratio = FloatArray(size) { 0.5f }
        for (i in 10..14) { db[i] = -40f; ratio[i] = 0.8f }
        for (i in 30..34) { db[i] = -35f; ratio[i] = 0.1f }
        val grid = ActivityGrid.fromSpeech(SpeechSnapshot(100, db, ratio, BooleanArray(size) { it != 40 }))

        assertEquals(100L, grid.startCell)
        assertTrue((10..15).all { grid.active[it] }, "speech plus one hangover cell")
        assertFalse(grid.active[9])
        assertFalse(grid.active[16])
        assertTrue((30..34).none { grid.active[it] }, "music has little voice-band share")
        assertFalse(grid.covered[40])
        assertEquals(49, grid.coveredCount)
    }

    @Test
    fun `cell helpers floor negative times`() {
        assertEquals(-1L, cellOf(-1))
        assertEquals(0L, cellOf(99_999))
        assertEquals(1L, cellOf(100_000))
        assertEquals(300_000L, cellStartUs(3))
    }
}
