package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.ui.theme.InitialKeyUpGuard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Replays a long-press on Details `Play`/`Resume`. The modal opens on the first auto-repeat, so `OK`
 * is still held. Focus moves to the "Finding streams…" entry and then to the 1st stream when results
 * arrive, and only after that does the user let go. Each entry is guarded by [InitialKeyUpGuard], as
 * `Modifier.ignoreInitialKeyUp()` does in [StreamPickerModal].
 */
class StreamPickerKeyUpTest {

    private class Picker {
        var open = false
        var chosen = 0
        private var focused: InitialKeyUpGuard? = null
        private val playButton = InitialKeyUpGuard().apply { onFocusChanged() }

        fun focus(guard: InitialKeyUpGuard) {
            focused?.onFocusChanged()
            guard.onFocusChanged()
            focused = guard
        }

        /** Routes an `OK` event to whatever has focus, the way tv-material `Surface` handles it. */
        fun ok(isKeyDown: Boolean, repeatCount: Int = 0, onEntryClick: () -> Unit = { chosen++ }) {
            val target = focused
            if (target == null) {
                if (playButton.shouldConsume(true, isKeyDown, repeatCount)) return
                // Surface: the first repeat is the long-click; a release after it is not a click.
                if (isKeyDown && repeatCount == 1) {
                    open = true
                }
                return
            }
            if (target.shouldConsume(true, isKeyDown, repeatCount)) return
            if (!isKeyDown) onEntryClick()
        }
    }

    @Test
    fun `long-pressing Play and releasing opens the stream list without choosing the first stream`() {
        val picker = Picker()
        picker.ok(isKeyDown = true)
        picker.ok(isKeyDown = true, repeatCount = 1)
        assertTrue(picker.open)

        val loading = InitialKeyUpGuard()
        picker.focus(loading)
        (2..6).forEach { picker.ok(isKeyDown = true, repeatCount = it) }
        val firstStream = InitialKeyUpGuard()
        picker.focus(firstStream)
        (7..9).forEach { picker.ok(isKeyDown = true, repeatCount = it) }

        picker.ok(isKeyDown = false)
        assertEquals(0, picker.chosen, "releasing OK must not pick the 1st stream")
    }

    @Test
    fun `release lands on the loading entry before streams arrive and is swallowed there`() {
        val picker = Picker()
        picker.ok(isKeyDown = true)
        picker.ok(isKeyDown = true, repeatCount = 1)
        picker.focus(InitialKeyUpGuard())
        picker.ok(isKeyDown = false)
        picker.focus(InitialKeyUpGuard())
        assertEquals(0, picker.chosen)
    }

    @Test
    fun `a fresh OK on the first stream after the release chooses it once`() {
        val picker = Picker()
        picker.ok(isKeyDown = true)
        picker.ok(isKeyDown = true, repeatCount = 1)
        picker.focus(InitialKeyUpGuard())
        picker.ok(isKeyDown = false)

        picker.ok(isKeyDown = true)
        picker.ok(isKeyDown = false)
        assertEquals(1, picker.chosen)
    }

    @Test
    fun `focus jumping to the LAST USED card mid-press never chooses it on release`() {
        val picker = Picker()
        picker.ok(isKeyDown = true)
        picker.ok(isKeyDown = true, repeatCount = 1)
        picker.focus(InitialKeyUpGuard())
        // Results arrive: focus goes straight to the LAST USED card (not #1) while OK is still held.
        val lastUsedCard = InitialKeyUpGuard()
        picker.focus(lastUsedCard)
        (2..5).forEach { picker.ok(isKeyDown = true, repeatCount = it) }
        picker.ok(isKeyDown = false)
        assertEquals(0, picker.chosen, "releasing OK must not pick the auto-focused LAST USED stream")

        picker.ok(isKeyDown = true)
        picker.ok(isKeyDown = false)
        assertEquals(1, picker.chosen)
    }
}
