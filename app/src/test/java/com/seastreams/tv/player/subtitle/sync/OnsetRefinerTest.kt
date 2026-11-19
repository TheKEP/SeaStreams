package com.seastreams.tv.player.subtitle.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Fine onset alignment around the coarse lag (50 ms steps, ±1.5 s), rounded to the 0.1 s grid. */
class OnsetRefinerTest {

    private val random = Random(5)
    private val onsets = (0 until 40).map { 60_000_000L + it * 3_100_000L + random.nextLong(0, 900_000) }
    private val window = 0L to 300_000_000L

    /** External cue starts running [offsetMs] late, each off by up to ±[jitterMs]. */
    private fun starts(offsetMs: Long, jitterMs: Long = 0) = onsets.map {
        it + offsetMs * 1_000 + if (jitterMs > 0) random.nextLong(-jitterMs * 1_000, jitterMs * 1_000) else 0
    }

    private fun refine(starts: List<Long>, coarseMs: Long) = OnsetRefiner.refine(onsets, starts, coarseMs, window.first, window.second)

    @Test
    fun `a coarse lag biased by lingering lines snaps to where dialogue starts`() {
        val fit = requireNotNull(refine(starts(5_230, jitterMs = 80), coarseMs = 4_600))
        assertEquals(5_200L, fit.offsetMs)
        assertTrue(fit.matchedFraction >= 0.9, "matched ${fit.matchedFraction}")
        assertTrue(fit.meanErrorMs < 80, "mean error ${fit.meanErrorMs}")
    }

    @Test
    fun `the result is rounded to the HUD's 0_1 s grid`() {
        assertEquals(2_300L, refine(starts(2_260), coarseMs = 2_000)?.offsetMs)
        assertEquals(-1_200L, refine(starts(-1_240), coarseMs = -900)?.offsetMs)
    }

    @Test
    fun `it searches only 1_5 s either side of the coarse lag`() {
        assertEquals(6_000L, refine(starts(6_000), coarseMs = 4_600)?.offsetMs)
        assertNull(refine(starts(6_400), coarseMs = 4_600), "1.8 s away: the coarse lag stands")
    }

    @Test
    fun `unrelated cue starts don't refine anything`() {
        val other = (0 until 40).map { 60_000_000L + random.nextLong(0, 130_000_000) }
        assertNull(refine(other, coarseMs = 3_000))
        assertNull(OnsetRefiner.refine(emptyList(), starts(0), 0, window.first, window.second))
        assertNull(refine(emptyList(), coarseMs = 0))
    }

    @Test
    fun `only cue starts inside the observed window count`() {
        val inside = starts(1_000).take(10)
        val outside = (0 until 30).map { 900_000_000L + it * 1_000_000L }
        assertEquals(1_000L, refine(inside + outside, coarseMs = 900)?.offsetMs)
    }
}
