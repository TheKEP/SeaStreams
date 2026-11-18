package com.seastreams.tv.ui.screens.splash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SplashWaveTest {

    private val lastIndex = "seastreams".lastIndex

    private fun startOf(index: Int): Float = ((lastIndex - index) * STAGGER_MS).toFloat()

    @Test
    fun `letter rests before its window starts and after it ends`() {
        val index = 4
        assertEquals(0f, waveOffsetDp(startOf(index) - 1f, index, lastIndex))
        assertEquals(0f, waveOffsetDp(startOf(index) + LETTER_BOUNCE_MS + 1f, index, lastIndex))
    }

    @Test
    fun `wave runs right to left - last letter moves first`() {
        val sweep = 130f
        assertTrue(abs(waveOffsetDp(sweep, lastIndex, lastIndex)) > 0.5f)
        assertEquals(0f, waveOffsetDp(sweep, 0, lastIndex))
    }

    @Test
    fun `letter crests upward in the first half and dips in the second`() {
        val index = 2
        val quarter = startOf(index) + LETTER_BOUNCE_MS / 4f
        val threeQuarter = startOf(index) + LETTER_BOUNCE_MS * 3f / 4f
        assertTrue(waveOffsetDp(quarter, index, lastIndex) < 0f)
        assertTrue(waveOffsetDp(threeQuarter, index, lastIndex) > 0f)
    }

    @Test
    fun `offset never exceeds the 6dp amplitude across a full sweep`() {
        val totalSweep = LETTER_BOUNCE_MS + lastIndex * STAGGER_MS
        for (ms in 0..totalSweep step 5) {
            for (index in 0..lastIndex) {
                assertTrue(abs(waveOffsetDp(ms.toFloat(), index, lastIndex)) <= 6f)
            }
        }
    }
}
