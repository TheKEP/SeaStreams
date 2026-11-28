package com.seastreams.tv.domain.playback.skip

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Milestone 14: prompt visibility window, auto-skip once, Next episode countdown (virtual time). */
class PromptTimersTest {

    private val intro = Prompt(PromptKind.SKIP_INTRO, 60_000, 150_000)
    private val next = Prompt(PromptKind.NEXT_EPISODE, 2_330_000, null, countdown = true)

    @Test
    fun `a prompt hides after 8 s of hidden-HUD playback and shows again with the HUD`() {
        val window = PromptWindow()
        assertTrue(window.visible(intro, hudVisible = false, playing = true, countingDown = false, nowMs = 0))
        assertTrue(window.visible(intro, false, true, false, 7_999))
        assertFalse(window.visible(intro, false, true, false, 8_000))
        assertTrue(window.visible(intro, hudVisible = true, playing = true, countingDown = false, nowMs = 9_000))
        assertFalse(window.visible(intro, false, true, false, 9_500))
    }

    @Test
    fun `the window pauses while the HUD is up or playback is paused`() {
        val window = PromptWindow()
        window.visible(intro, false, true, false, 0)
        window.visible(intro, true, true, false, 5_000)
        window.visible(intro, false, false, false, 20_000)
        // Only the last 7 s count: the HUD and pause stretches are left out.
        assertTrue(window.visible(intro, false, true, false, 27_000))
    }

    @Test
    fun `a new stretch restarts the window`() {
        val window = PromptWindow()
        window.visible(intro, false, true, false, 0)
        assertFalse(window.visible(intro, false, true, false, 9_000))
        assertTrue(window.visible(next.copy(countdown = false), false, true, false, 9_500))
    }

    @Test
    fun `auto-skip acts once per stretch, a rewind doesn't skip again`() {
        val tracker = AutoSkipTracker()
        val auto = intro.copy(auto = true)
        assertTrue(tracker.shouldAct(auto))
        assertFalse(tracker.shouldAct(auto))
        assertFalse(tracker.shouldAct(intro))
        tracker.reset()
        assertTrue(tracker.shouldAct(auto))
    }

    @Test
    fun `the countdown runs 10 s of playback, pauses, then fires once`() {
        val countdown = PromptCountdown()
        assertEquals(10_000L, countdown.update(next, playing = true, nowMs = 0))
        assertEquals(6_000L, countdown.update(next, true, 4_000))
        assertEquals(6_000L, countdown.update(next, false, 9_000))
        assertEquals(1_000L, countdown.update(next, true, 14_000))
        assertEquals(0L, countdown.update(next, true, 15_000))
        assertNull(countdown.update(next, true, 16_000))
    }

    @Test
    fun `a key press cancels the countdown, prompts without one never count`() {
        val countdown = PromptCountdown()
        countdown.update(next, true, 0)
        assertTrue(countdown.counting)
        countdown.cancel()
        assertFalse(countdown.counting)
        assertNull(countdown.update(next, true, 20_000))
        assertNull(PromptCountdown().update(next.copy(countdown = false), true, 0))
    }

    @Test
    fun `a prompt dismissed with Back stays hidden for its stretch, HUD or not, until a new title`() {
        val window = PromptWindow()
        assertTrue(window.visible(intro, false, true, false, 0))
        window.dismiss(intro)

        assertFalse(window.visible(intro, hudVisible = false, playing = true, countingDown = false, nowMs = 500))
        assertFalse(window.visible(intro, hudVisible = true, playing = true, countingDown = false, nowMs = 1_000))
        assertFalse(window.visible(intro, hudVisible = false, playing = true, countingDown = true, nowMs = 1_500))
        // Another stretch is unaffected; a new title (reset) forgets the dismissal.
        assertTrue(window.visible(next, false, true, true, 2_000))
        window.reset()
        assertTrue(window.visible(intro, false, true, false, 3_000))
    }
}
