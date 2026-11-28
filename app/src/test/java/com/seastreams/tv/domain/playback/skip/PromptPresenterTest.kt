package com.seastreams.tv.domain.playback.skip

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Milestone 14: the prompts' tick-by-tick state machine (as `PlayerPrompts` drives it every 500 ms), including
 * `Back` dismissal with the HUD hidden and visible.
 */
class PromptPresenterTest {

    private val intro = Prompt(PromptKind.SKIP_INTRO, 60_000, 150_000)
    private val next = Prompt(PromptKind.NEXT_EPISODE, 2_330_000, null, countdown = true)

    private fun PromptPresenter.tick(prompt: Prompt?, nowMs: Long, hud: Boolean = false, playing: Boolean = true, panel: Boolean = false, countdown: Boolean = true) =
        step(prompt, hudVisible = hud, playing = playing, panelOpen = panel, countdownAllowed = countdown, nowMs = nowMs)

    @Test
    fun `Back with the HUD hidden dismisses the prompt and the next ticks don't bring it back`() {
        val presenter = PromptPresenter()
        assertEquals(PromptStep.Show(intro), presenter.tick(intro, 0))

        assertTrue(presenter.dismiss())

        assertNull(presenter.current)
        // Still inside the intro: hidden on every following tick, and again when the HUD opens.
        assertEquals(PromptStep.Show(null), presenter.tick(intro, 500))
        assertEquals(PromptStep.Show(null), presenter.tick(intro, 1_000, hud = true))
        assertEquals(PromptStep.Show(null), presenter.tick(intro, 60_000))
    }

    @Test
    fun `Back with the HUD visible dismisses the prompt while the HUD stays`() {
        val presenter = PromptPresenter()
        presenter.tick(intro, 0)
        assertEquals(PromptStep.Show(intro), presenter.tick(intro, 20_000, hud = true))

        assertTrue(presenter.dismiss())

        assertEquals(PromptStep.Show(null), presenter.tick(intro, 20_500, hud = true))
        assertEquals(PromptStep.Show(null), presenter.tick(intro, 21_000, hud = false))
    }

    @Test
    fun `dismissing a Next episode countdown stops it, so it never plays on its own`() {
        val presenter = PromptPresenter()
        assertEquals(PromptStep.Show(next, 10_000), presenter.tick(next, 0))
        presenter.tick(next, 4_000)

        presenter.dismiss()

        // Far past the 10 s: no Act, nothing shown.
        assertEquals(PromptStep.Show(null), presenter.tick(next, 20_000))
        assertEquals(PromptStep.Show(null), presenter.tick(next, 20_500, hud = true))
    }

    @Test
    fun `a later stretch shows normally after a dismissal`() {
        val presenter = PromptPresenter()
        presenter.tick(intro, 0)
        presenter.dismiss()

        assertEquals(PromptStep.Show(next, 10_000), presenter.tick(next, 1_000))
    }

    @Test
    fun `nothing to dismiss when no prompt shows`() {
        val presenter = PromptPresenter()
        assertFalse(presenter.dismiss())
        presenter.tick(intro, 0, panel = true)
        assertFalse(presenter.dismiss())
    }

    @Test
    fun `a new title forgets dismissals`() {
        val presenter = PromptPresenter()
        presenter.tick(intro, 0)
        presenter.dismiss()

        presenter.reset()

        assertEquals(PromptStep.Show(intro), presenter.tick(intro, 1_000))
    }

    @Test
    fun `countdown runs out into an Act only when Auto-Play Next Episode applies`() {
        val presenter = PromptPresenter()
        presenter.tick(next, 0)
        assertEquals(PromptStep.Act(next.copy(auto = true)), presenter.tick(next, 10_000))

        val off = PromptPresenter()
        off.tick(next, 0, countdown = false)
        assertFalse(off.tick(next, 10_000, countdown = false) is PromptStep.Act)
    }

    @Test
    fun `auto-skip acts once, then the stretch is a plain button`() {
        val presenter = PromptPresenter()
        val auto = intro.copy(auto = true)

        assertEquals(PromptStep.Act(auto), presenter.tick(auto, 0))
        assertEquals(PromptStep.Show(intro), presenter.tick(auto, 500))
    }
}
