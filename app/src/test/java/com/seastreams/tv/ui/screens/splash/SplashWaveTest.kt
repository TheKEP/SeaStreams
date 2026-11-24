package com.seastreams.tv.ui.screens.splash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SplashWaveTest {

    private val lastIndex = "seastreams".lastIndex
    private val letters = 0..lastIndex

    private fun state(ms: Float, index: Int) = letterWaveState(ms, index, lastIndex)

    private fun startOf(index: Int): Float = index * STAGGER_IN_MS

    private fun LetterWaveState.isAtRest() = alpha == 1f && scale == 1f && offsetYDp == 0f && rotationDeg == 0f

    /** Peak magnitudes of successive same-sign lobes of [values] (one per oscillation half-cycle). */
    private fun lobePeaks(values: List<Float>): List<Float> {
        val peaks = mutableListOf<Float>()
        var current = 0f
        var sign = 0
        for (v in values) {
            val s = if (v > 0f) 1 else if (v < 0f) -1 else 0
            if (s == 0) continue
            if (s != sign && sign != 0) {
                peaks += current
                current = 0f
            }
            sign = s
            current = maxOf(current, abs(v))
        }
        if (sign != 0) peaks += current
        return peaks
    }

    @Test
    fun `timings add up to the 2840ms animation`() {
        assertEquals(110f, STAGGER_IN_MS)
        assertEquals(1850f, LETTER_LIFE_MS)
        assertEquals(2840f, ANIMATION_DURATION_MS)
        assertEquals(ANIMATION_DURATION_MS, lastIndex * STAGGER_IN_MS + LETTER_LIFE_MS)
    }

    @Test
    fun `both wave and lightweight fade settle before the minimum hold ends`() {
        assertEquals(420L, START_DELAY_MS)
        assertEquals(3350L, MIN_HOLD_MS)
        assertTrue(START_DELAY_MS + ANIMATION_DURATION_MS.toLong() <= MIN_HOLD_MS)
        assertTrue(START_DELAY_MS + LOCKUP_FADE_MS <= MIN_HOLD_MS)
    }

    @Test
    fun `each letter is invisible before its start time`() {
        for (index in letters) {
            assertTrue(state(startOf(index) - 1f, index).alpha == 0f, "index=$index")
            assertTrue(state(-100f, index).alpha == 0f, "index=$index before the animation")
        }
    }

    @Test
    fun `letters appear left to right`() {
        assertTrue(startOf(0) < startOf(lastIndex))
        val sweep = 4 * STAGGER_IN_MS + 1f
        for (index in letters) {
            assertEquals(index <= 4, state(sweep, index).alpha > 0f, "index=$index at ${sweep}ms")
        }
    }

    @Test
    fun `pop-in flows into the bobbing without a jump`() {
        val boundary = POP_IN_FRACTION * LETTER_LIFE_MS
        val before = state(boundary - 0.5f, 0)
        val after = state(boundary + 0.5f, 0)
        assertTrue(abs(before.offsetYDp - after.offsetYDp) < 0.1f, "offset $before -> $after")
        assertTrue(abs(before.rotationDeg - after.rotationDeg) < 0.1f, "rotation $before -> $after")
        assertTrue(abs(before.scale - after.scale) < 0.01f, "scale $before -> $after")
    }

    /** Sweep time for [index] at bobbing progress [b] (0..1 across the post-pop-in window). */
    private fun atBob(index: Int, b: Float): Float =
        startOf(index) + (POP_IN_FRACTION + (1f - POP_IN_FRACTION) * b) * LETTER_LIFE_MS

    @Test
    fun `bobbing is exaggerated - dips well below then rises back above the baseline`() {
        val index = 5
        val dip = state(atBob(index, 0.40f), index).offsetYDp
        val rise = state(atBob(index, 0.80f), index).offsetYDp
        assertTrue(dip > 5f, "should dip >5dp below baseline at b=0.4: $dip")
        assertTrue(rise < -2f, "should bob >2dp back up at b=0.8: $rise")
        assertTrue(state(startOf(index) + LETTER_LIFE_MS, index).isAtRest())
    }

    @Test
    fun `after appearing, a letter bobs above and below rest with shrinking amplitude`() {
        val index = 3
        assertTrue(state(startOf(index) + 750f, index).alpha == 1f)

        // Sample from where bobbing begins (the end of the pop-in), so the first lobe is whole.
        val bobStart = (POP_IN_FRACTION * LETTER_LIFE_MS).toInt() + 1
        val bobbing = (bobStart..LETTER_LIFE_MS.toInt() - 1 step 5).map { state(startOf(index) + it, index) }
        assertTrue(bobbing.all { it.alpha == 1f })
        for ((name, values) in listOf("offset" to bobbing.map { it.offsetYDp }, "rotation" to bobbing.map { it.rotationDeg })) {
            assertTrue(values.any { it > 0f } && values.any { it < 0f }, "$name should cross rest")
            val peaks = lobePeaks(values)
            assertTrue(peaks.size >= 2, "$name should oscillate: $peaks")
            assertTrue(peaks.zipWithNext().all { (a, b) -> b < a }, "$name amplitude should shrink: $peaks")
        }
    }

    @Test
    fun `everything is completely still from 2840ms`() {
        for (ms in 2840..3300 step 5) {
            for (index in letters) {
                val s = state(ms.toFloat(), index)
                assertTrue(s.isAtRest(), "ms=$ms index=$index state=$s")
            }
        }
    }
}
