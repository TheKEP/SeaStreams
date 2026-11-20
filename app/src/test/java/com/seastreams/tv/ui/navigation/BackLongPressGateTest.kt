package com.seastreams.tv.ui.navigation

import com.seastreams.tv.domain.model.PlaybackArtwork
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.ui.screens.playback.PlaybackUiState
import com.seastreams.tv.ui.screens.playback.StreamPickerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Long-press `Back` → Search (PRD §1): short vs held presses, exclusions, trailing `KeyUp`. */
class BackLongPressGateTest {

    private val gate = BackLongPressGate()
    private var fired = 0

    private fun key(
        down: Boolean,
        repeat: Int = 0,
        longPress: Boolean = false,
        canceled: Boolean = false,
        enabled: Boolean = true,
        back: Boolean = true,
    ) = gate.onKey(back, down, repeat, longPress, canceled, enabled) { fired++ }

    @Test
    fun `a short press is deferred then replayed with its release`() {
        assertEquals(BackKeyDecision.DEFER, key(down = true))
        assertEquals(BackKeyDecision.REPLAY, key(down = false))
        assertEquals(0, fired)
        assertFalse(gate.isPending)
    }

    @Test
    fun `the timeout fires once and the held repeats and trailing KeyUp are consumed`() {
        assertEquals(BackKeyDecision.DEFER, key(down = true))
        assertTrue(gate.onTimeout(enabled = true))
        assertEquals(BackKeyDecision.CONSUME, key(down = true, repeat = 1))
        assertEquals(BackKeyDecision.CONSUME, key(down = true, repeat = 2, longPress = true))
        assertEquals(BackKeyDecision.CONSUME, key(down = false))
        assertEquals(0, fired, "the timeout path fires through onTimeout, not onFire")
        // Back to idle: the next press is a new short press.
        assertEquals(BackKeyDecision.DEFER, key(down = true))
        assertEquals(BackKeyDecision.REPLAY, key(down = false))
    }

    @Test
    fun `a repeat flagged as a long press fires once and a late timeout does not fire again`() {
        key(down = true)
        assertEquals(BackKeyDecision.CONSUME, key(down = true, repeat = 1))
        assertEquals(0, fired)
        assertEquals(BackKeyDecision.CONSUME, key(down = true, repeat = 2, longPress = true))
        assertEquals(1, fired)
        assertFalse(gate.onTimeout(enabled = true))
        key(down = true, repeat = 3, longPress = true)
        assertEquals(BackKeyDecision.CONSUME, key(down = false))
        assertEquals(1, fired)
    }

    @Test
    fun `with the shortcut off every Back event passes through untouched`() {
        assertEquals(BackKeyDecision.PASS, key(down = true, enabled = false))
        assertEquals(BackKeyDecision.PASS, key(down = true, repeat = 1, longPress = true, enabled = false))
        assertEquals(BackKeyDecision.PASS, key(down = false, enabled = false))
        assertFalse(gate.onTimeout(enabled = false))
        assertEquals(0, fired)
    }

    @Test
    fun `the tail of a press that was never deferred passes through`() {
        // e.g. Back pressed in the player, which exited on KeyDown; the hold continues on the page below.
        assertEquals(BackKeyDecision.PASS, key(down = true, repeat = 3))
        assertEquals(BackKeyDecision.PASS, key(down = false))
    }

    @Test
    fun `a canceled release is dropped without replay`() {
        key(down = true)
        assertEquals(BackKeyDecision.CONSUME, key(down = false, canceled = true))
        assertFalse(gate.isPending)
    }

    @Test
    fun `reset forgets a pending press`() {
        key(down = true)
        gate.reset()
        assertFalse(gate.isPending)
        assertFalse(gate.onTimeout(enabled = true))
        assertEquals(BackKeyDecision.PASS, key(down = false))
    }

    @Test
    fun `a timeout after the shortcut turned off keeps the press short`() {
        key(down = true)
        assertFalse(gate.onTimeout(enabled = false))
        assertTrue(gate.isPending)
        assertEquals(BackKeyDecision.REPLAY, key(down = false))
        assertEquals(0, fired)
    }

    @Test
    fun `other keys always pass, even while Back is pending`() {
        assertEquals(BackKeyDecision.PASS, key(down = true, back = false))
        key(down = true)
        assertEquals(BackKeyDecision.PASS, key(down = true, back = false))
        assertTrue(gate.isPending)
    }

    @Test
    fun `the router is enabled only while an action is registered`() {
        val router = BackLongPressRouter()
        assertFalse(router.enabled)
        router.onLongPress = {}
        assertTrue(router.enabled)
    }

    @Test
    fun `playback, trailers, stream lookup and the stream modal turn the shortcut off`() {
        val idle = PlaybackUiState()
        assertTrue(backLongPressAllowed(idle))
        val trailer = PlaybackRequest.Trailer(1, "T", TrailerStream(TrailerSource("u"), null, 1080))
        assertFalse(backLongPressAllowed(idle.copy(player = trailer)))
        assertFalse(backLongPressAllowed(idle.copy(loading = PlaybackArtwork(null, null, "T"))))
        assertFalse(backLongPressAllowed(idle.copy(picker = StreamPickerState("T", null, loading = true))))
        assertFalse(backLongPressAllowed(idle.copy(busyLabel = "Finding streams…")))
    }
}
