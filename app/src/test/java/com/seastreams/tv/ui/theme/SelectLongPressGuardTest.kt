package com.seastreams.tv.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Details `Play`/`Resume` long-press (PRD §1/§6). The guard replaces tv `Surface`'s own long-click
 * flag, which outlived the stream list taking focus and swallowed the first click back on `Play`.
 */
class SelectLongPressGuardTest {

    /** A guard on a control that has just gained focus. */
    private fun focusedGuard() = SelectLongPressGuard().apply { onFocusChanged() }

    @Test
    fun `a short press passes through and clicks`() {
        val guard = focusedGuard()
        assertEquals(LongPressAction.PASS, guard.onSelectKeyDown(0))
        assertFalse(guard.consumeClick())
    }

    @Test
    fun `the first press after a long-press opened the stream list and Back closed it clicks`() {
        val play = focusedGuard()
        // Hold OK on Play: the first repeat opens the stream list, which takes focus while OK is held.
        assertEquals(LongPressAction.PASS, play.onSelectKeyDown(0))
        assertEquals(LongPressAction.LONG_PRESS, play.onSelectKeyDown(1))
        play.onFocusChanged()
        // The release lands in the stream list (never on Play). Back closes the list; focus returns.
        play.onFocusChanged()
        // One fresh press on Play must click (auto-play top stream, or reopen the stream list).
        assertEquals(LongPressAction.PASS, play.onSelectKeyDown(0))
        assertFalse(play.consumeClick(), "the first click after dismissing the stream list must run")
    }

    @Test
    fun `a long-press after Back still opens the stream list again`() {
        val play = focusedGuard()
        play.onSelectKeyDown(0)
        play.onSelectKeyDown(1)
        play.onFocusChanged()
        play.onFocusChanged()

        assertEquals(LongPressAction.PASS, play.onSelectKeyDown(0))
        assertEquals(LongPressAction.LONG_PRESS, play.onSelectKeyDown(1))
    }

    @Test
    fun `a long-press that keeps focus suppresses only its own release`() {
        val guard = focusedGuard()
        guard.onSelectKeyDown(0)
        assertEquals(LongPressAction.LONG_PRESS, guard.onSelectKeyDown(1))
        assertTrue(guard.consumeClick(), "the long-press's own release must not click")

        assertEquals(LongPressAction.PASS, guard.onSelectKeyDown(0))
        assertFalse(guard.consumeClick(), "the next press clicks")
    }

    @Test
    fun `held repeats fire the long-press once and are all consumed`() {
        val guard = focusedGuard()
        guard.onSelectKeyDown(0)
        assertEquals(LongPressAction.LONG_PRESS, guard.onSelectKeyDown(1))
        (2..10).forEach { assertEquals(LongPressAction.CONSUME, guard.onSelectKeyDown(it), "repeat=$it") }
    }

    @Test
    fun `repeats of a press that started on another control never fire the long-press`() {
        val guard = focusedGuard()
        (1..5).forEach { assertEquals(LongPressAction.CONSUME, guard.onSelectKeyDown(it), "repeat=$it") }
        assertFalse(guard.consumeClick())
    }

    @Test
    fun `losing focus mid-press forgets the long-press`() {
        val guard = focusedGuard()
        guard.onSelectKeyDown(0)
        guard.onSelectKeyDown(1)
        guard.onFocusChanged()
        assertFalse(guard.consumeClick())
    }
}
