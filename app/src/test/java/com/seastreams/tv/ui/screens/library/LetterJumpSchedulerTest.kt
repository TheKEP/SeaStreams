package com.seastreams.tv.ui.screens.library

import com.seastreams.tv.domain.usecase.LibraryQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Debounced A–Z scrubbing (PRD §7): letter focus scrolls the grid once after a pause; OK jumps at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class LetterJumpSchedulerTest {

    private val scrolls = mutableListOf<Int>()

    private fun TestScope.scheduler() = LetterJumpScheduler(backgroundScope) { scrolls += it }

    @Test
    fun `a single preview scrolls once after the debounce`() = runTest {
        val jumps = scheduler()
        jumps.preview(12)
        advanceTimeBy(LETTER_PREVIEW_DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals(emptyList<Int>(), scrolls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(12), scrolls)
    }

    @Test
    fun `scrubbing across letters scrolls only to the letter it stops on`() = runTest {
        val jumps = scheduler()
        listOf(0, 4, 9, 15).forEach { index ->
            jumps.preview(index)
            advanceTimeBy(30)
            runCurrent()
        }
        assertEquals(emptyList<Int>(), scrolls)

        advanceTimeBy(LETTER_PREVIEW_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf(15), scrolls)
    }

    @Test
    fun `a jump scrolls immediately, runs its follow-up and drops the pending preview`() = runTest {
        val jumps = scheduler()
        var focused = false
        jumps.preview(3)
        advanceTimeBy(50)
        jumps.jump(20) { focused = true }
        runCurrent()
        assertEquals(listOf(20), scrolls)
        assertEquals(true, focused)

        advanceTimeBy(LETTER_PREVIEW_DEBOUNCE_MILLIS * 2)
        runCurrent()
        assertEquals(listOf(20), scrolls)
    }

    @Test
    fun `Left on a letter during a pending preview cancels it and focuses its target at once`() = runTest {
        val jumps = scheduler()
        val focused = mutableListOf<Int>()
        val letterIndex = mapOf('#' to 0, 'M' to 14)
        jumps.preview(0) // focus rested on # for < 100ms...
        advanceTimeBy(LETTER_PREVIEW_DEBOUNCE_MILLIS - 10)
        val target = letterLeftTarget(letterIndex, LibraryQuery.LETTERS.indexOf('M'))
        target?.let { jumps.jump(it) { focused += it } } // ...then Left on M
        runCurrent()
        assertEquals(listOf(14), scrolls)
        assertEquals(listOf(14), focused)

        advanceTimeBy(LETTER_PREVIEW_DEBOUNCE_MILLIS * 2)
        runCurrent()
        assertEquals(listOf(14), scrolls)
    }

    @Test
    fun `a preview after a jump is debounced as usual`() = runTest {
        val jumps = scheduler()
        jumps.jump(20) {}
        runCurrent()
        jumps.preview(7)
        runCurrent()
        assertEquals(listOf(20), scrolls)

        advanceTimeBy(LETTER_PREVIEW_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf(20, 7), scrolls)
    }

    @Test
    fun `cancel drops a pending preview`() = runTest {
        val jumps = scheduler()
        jumps.preview(5)
        jumps.cancel()
        advanceTimeBy(LETTER_PREVIEW_DEBOUNCE_MILLIS * 2)
        runCurrent()
        assertEquals(emptyList<Int>(), scrolls)
    }

    @Test
    fun `the same letter focused twice in a row scrolls once`() = runTest {
        val jumps = scheduler()
        jumps.preview(9)
        advanceTimeBy(40)
        jumps.preview(9)
        advanceTimeBy(LETTER_PREVIEW_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf(9), scrolls)
    }
}
