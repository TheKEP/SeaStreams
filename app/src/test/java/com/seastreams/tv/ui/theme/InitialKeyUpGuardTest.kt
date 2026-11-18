package com.seastreams.tv.ui.theme

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InitialKeyUpGuardTest {

    private fun InitialKeyUpGuard.down(repeat: Int = 0) = shouldConsume(isSelectKey = true, isKeyDown = true, repeatCount = repeat)
    private fun InitialKeyUpGuard.up() = shouldConsume(isSelectKey = true, isKeyDown = false, repeatCount = 0)

    /** A guard on a control that has just gained focus. */
    private fun focusedGuard() = InitialKeyUpGuard().apply { onFocusChanged() }

    @Test
    fun `a press that starts and ends on the control clicks`() {
        val guard = focusedGuard()
        assertFalse(guard.down())
        assertFalse(guard.up())
    }

    @Test
    fun `a release without a press seen while focused is consumed`() {
        assertTrue(focusedGuard().up())
    }

    @Test
    fun `held repeats of a press that started elsewhere are consumed`() {
        val guard = focusedGuard()
        (1..10).forEach { assertTrue(guard.down(repeat = it), "repeat=$it") }
        assertTrue(guard.up())
    }

    @Test
    fun `held repeats of the control's own press pass through`() {
        val guard = focusedGuard()
        assertFalse(guard.down())
        assertFalse(guard.down(repeat = 1))
        assertFalse(guard.up())
    }

    @Test
    fun `the next fresh press after a swallowed release clicks`() {
        val guard = focusedGuard()
        assertTrue(guard.up())
        assertFalse(guard.down())
        assertFalse(guard.up())
    }

    @Test
    fun `a release only clicks once per press`() {
        val guard = focusedGuard()
        guard.down()
        assertFalse(guard.up())
        assertTrue(guard.up())
    }

    @Test
    fun `focus changes forget a press`() {
        val guard = focusedGuard()
        assertFalse(guard.down())
        guard.onFocusChanged() // lost
        guard.onFocusChanged() // regained
        assertTrue(guard.up())
    }

    @Test
    fun `non-select keys are never touched`() {
        val guard = focusedGuard()
        assertFalse(guard.shouldConsume(isSelectKey = false, isKeyDown = false, repeatCount = 0))
        assertFalse(guard.shouldConsume(isSelectKey = false, isKeyDown = true, repeatCount = 3))
    }
}
