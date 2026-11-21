package com.seastreams.tv.ui.screens.library

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Library A–Z jumps focus only their own target poster (PRD §7). Regression: with one shared
 * requester, the previous jump's poster (still composed nearby) took the new jump's focus.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LetterJumpFocusTest {

    private val targets = JumpFocusTargets()

    /** Fake grid: which indices are composed (requesters attached), and what got focus. */
    private val composed = mutableSetOf<Int>()
    private val focused = mutableListOf<Int>()

    /** Succeeds only when [requester] belongs to a composed poster, like an attached FocusRequester. */
    private fun tryFocus(requester: FocusRequester): Boolean {
        val index = composed.firstOrNull { targets.requesterFor(it) === requester } ?: return false
        focused += index
        return true
    }

    @Test
    fun `a nearby jump never lands on the previous letter's still composed poster`() = runTest {
        composed += 42 // letter A's first poster, focused by the previous jump, still on screen
        var frame = 0
        val result = targets.focus(index = 55, maxFrames = 5, awaitFrame = { if (++frame == 2) composed += 55 }) {
            tryFocus(it)
        }
        assertEquals(true, result)
        assertEquals(listOf(55), focused)
        assertEquals(2, frame, "frame 1 failed (55 not attached) instead of focusing 42")
    }

    @Test
    fun `consecutive jumps between nearby letters each land on their own poster`() = runTest {
        composed += listOf(40, 42, 48, 55)
        listOf(42, 55, 42, 48).forEach { index -> targets.focus(index, maxFrames = 3, awaitFrame = {}) { tryFocus(it) } }
        assertEquals(listOf(42, 55, 42, 48), focused)
    }

    @Test
    fun `requesters are stable per index and distinct across indices`() {
        assertSame(targets.requesterFor(42), targets.requesterFor(42))
        assertNotSame(targets.requesterFor(42), targets.requesterFor(55))
        assertNotSame(targets.requesterFor(42), JumpFocusTargets().requesterFor(42), "new entries get new requesters")
    }

    @Test
    fun `a target that never attaches gives up without focusing another poster`() = runTest {
        composed += 42
        var frames = 0
        val result = targets.focus(index = 55, maxFrames = 4, awaitFrame = { frames++ }) { tryFocus(it) }
        assertEquals(false, result)
        assertEquals(4, frames)
        assertEquals(emptyList<Int>(), focused, "focus stays on the letter")
    }

    @Test
    fun `a second jump during the first one's focus retry focuses only the new target`() = runTest {
        val scrolls = mutableListOf<Int>()
        val jumps = LetterJumpScheduler(backgroundScope) { scrolls += it }
        val frame: suspend () -> Unit = { delay(16) }
        composed += 42
        jumps.jump(30) { targets.focus(30, maxFrames = 10, awaitFrame = frame) { tryFocus(it) } }
        advanceTimeBy(20) // 30 has not attached yet: still retrying
        jumps.jump(55) { targets.focus(55, maxFrames = 10, awaitFrame = frame) { tryFocus(it) } }
        runCurrent()
        composed += listOf(30, 55)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(30, 55), scrolls)
        assertEquals(listOf(55), focused)
    }
}
