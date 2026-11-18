package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.ui.theme.InitialKeyUpGuard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Replays the `Enter` key events that the player sees: the root preview pass ([PlayerSelectKeyGate]),
 * then the root's own handler while the HUD is hidden, or the focused `Play/Pause` button (guarded by
 * [InitialKeyUpGuard]) once the HUD is up.
 */
class PlayerSelectKeyGateTest {

    private class FakePlayer(var playing: Boolean) {
        var hudVisible = false
        var toggles = 0
        val gate = PlayerSelectKeyGate()
        val playButton = InitialKeyUpGuard()

        fun select(isKeyDown: Boolean, repeatCount: Int = 0) {
            if (gate.consumeHeldPress(isKeyDown, repeatCount)) return
            if (!hudVisible) {
                if (!isKeyDown) return
                when (gate.onHiddenHudSelectDown(repeatCount, playing)) {
                    HiddenHudSelect.PAUSE_AND_SHOW -> {
                        playing = false
                        showHud()
                    }
                    HiddenHudSelect.SHOW -> showHud()
                    null -> Unit
                }
                return
            }
            if (playButton.shouldConsume(isSelectKey = true, isKeyDown = isKeyDown, repeatCount = repeatCount)) return
            // tv-material Surface clicks on release.
            if (!isKeyDown) {
                toggles++
                playing = !playing
            }
        }

        private fun showHud() {
            hudVisible = true
            playButton.onFocusChanged()
        }
    }

    @Test
    fun `pressing and releasing Enter during playback pauses and stays paused with the HUD open`() {
        val player = FakePlayer(playing = true)
        player.select(isKeyDown = true)
        assertFalse(player.playing)
        assertTrue(player.hudVisible)

        player.select(isKeyDown = false)
        assertFalse(player.playing, "releasing Enter must not un-pause")
        assertEquals(0, player.toggles)
    }

    @Test
    fun `holding Enter while playing pauses once and the release is still swallowed`() {
        val player = FakePlayer(playing = true)
        player.select(isKeyDown = true)
        (1..8).forEach { player.select(isKeyDown = true, repeatCount = it) }
        player.select(isKeyDown = false)
        assertFalse(player.playing)
        assertEquals(0, player.toggles)
    }

    @Test
    fun `Enter while paused opens the HUD without resuming`() {
        val player = FakePlayer(playing = false)
        player.select(isKeyDown = true)
        player.select(isKeyDown = false)
        assertTrue(player.hudVisible)
        assertFalse(player.playing)
        assertEquals(0, player.toggles)
    }

    @Test
    fun `a fresh Enter on the focused Play button resumes`() {
        val player = FakePlayer(playing = true)
        player.select(isKeyDown = true)
        player.select(isKeyDown = false)

        player.select(isKeyDown = true)
        player.select(isKeyDown = false)
        assertTrue(player.playing)
        assertEquals(1, player.toggles)
    }

    @Test
    fun `Play button guard alone still swallows the release if the root misses it`() {
        val guard = InitialKeyUpGuard().apply { onFocusChanged() }
        assertTrue(guard.shouldConsume(isSelectKey = true, isKeyDown = false, repeatCount = 0))
    }

    @Test
    fun `a fresh press forgets an opening press whose release never arrived`() {
        val gate = PlayerSelectKeyGate()
        gate.onHiddenHudSelectDown(repeatCount = 0, playing = true)
        assertFalse(gate.consumeHeldPress(isKeyDown = true, repeatCount = 0))
        assertFalse(gate.consumeHeldPress(isKeyDown = false, repeatCount = 0))
    }

    @Test
    fun `held repeats with the HUD hidden never re-trigger`() {
        assertNull(PlayerSelectKeyGate().onHiddenHudSelectDown(repeatCount = 2, playing = true))
    }
}
