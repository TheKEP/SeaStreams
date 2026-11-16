package com.seastreams.tv.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeroAutoCycleTest {

    private val interval = HERO_AUTO_ADVANCE_MILLIS

    @Test
    fun `auto-advance interval is five seconds`() {
        assertEquals(5_000L, HERO_AUTO_ADVANCE_MILLIS)
    }

    @Test
    fun `advances once every interval, continuously`() = runTest {
        var ticks = 0
        val job = launch { runHeroAutoCycle(MutableStateFlow(0), interval) { ticks++ } }

        advanceTimeBy(interval - 1); runCurrent()
        assertEquals(0, ticks)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, ticks)
        advanceTimeBy(interval * 2); runCurrent()
        assertEquals(3, ticks)

        job.cancel()
    }

    @Test
    fun `a direct interaction restarts the full countdown`() = runTest {
        val resets = MutableStateFlow(0)
        var ticks = 0
        val job = launch { runHeroAutoCycle(resets, interval) { ticks++ } }

        advanceTimeBy(4_000); runCurrent()
        resets.value = 1 // user pressed Left/Right at t=4s

        advanceTimeBy(interval - 1); runCurrent() // t=8.999s: the old 5s deadline has passed
        assertEquals(0, ticks)
        advanceTimeBy(1); runCurrent() // t=9s: 5s after the interaction
        assertEquals(1, ticks)

        job.cancel()
    }

    @Test
    fun `cancelling the cycle (screen paused or left) stops advancing`() = runTest {
        var ticks = 0
        val job = launch { runHeroAutoCycle(MutableStateFlow(0), interval) { ticks++ } }

        advanceTimeBy(interval); runCurrent()
        job.cancel()
        advanceTimeBy(interval * 5); runCurrent()

        assertEquals(1, ticks)
    }

    @Test
    fun `ten-item carousel wraps from item 10 back to item 1 in both directions`() {
        assertEquals(1, heroIndexAfter(0, 1, 10))
        assertEquals(0, heroIndexAfter(9, 1, 10))
        assertEquals(9, heroIndexAfter(0, -1, 10))
        assertEquals(0, heroIndexAfter(3, 1, 0))
    }

    @Test
    fun `timer ticks walk the carousel state round the full cycle`() {
        val state = HeroCarouselState(itemCount = 10)

        repeat(9) { state.advance() }
        assertEquals(9, state.index)
        state.advance()
        assertEquals(0, state.index)

        state.userStep(-1)
        assertEquals(9, state.index)
        assertEquals(1, state.interactions)
        state.userInteracted()
        assertEquals(2, state.interactions)
    }
}
