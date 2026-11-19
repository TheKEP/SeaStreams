package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.core.datastore.AudioSettings
import com.seastreams.tv.core.datastore.DialogueBoost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** In-player (session-only) audio: effects switch passthrough off for this video; it can go back on. */
class PlayerSessionAudioTest {

    private val start = SessionAudio.from(AudioSettings(passthroughEnabled = true))

    private fun SessionAudio.change(transform: (SessionAudio) -> SessionAudio) = transform(this).withPassthroughAutoOff(this)

    @Test
    fun `starts from General Settings, passthrough included`() {
        assertTrue(start.passthrough)
        assertFalse(SessionAudio.from(AudioSettings(passthroughEnabled = false)).passthrough)
    }

    @Test
    fun `turning an effect on in the player switches passthrough off for this video`() {
        assertFalse(start.change { it.copy(dialogueBoost = DialogueBoost.PLUS_3) }.passthrough)
        assertFalse(start.change { it.copy(nightMode = true) }.passthrough)
        assertFalse(start.change { it.copy(delayMs = -50) }.passthrough)
    }

    @Test
    fun `the session passthrough toggle turns it back on and it stays on`() {
        val boosted = start.change { it.copy(dialogueBoost = DialogueBoost.PLUS_9) }
        val backOn = boosted.change { it.copy(passthrough = true) }
        assertTrue(backOn.passthrough)
        assertEquals(DialogueBoost.PLUS_9, backOn.dialogueBoost)
        assertTrue(backOn.change { it.copy(nightMode = false) }.passthrough)
        assertTrue(backOn.change { it.copy(delayMs = 0) }.passthrough)
    }

    @Test
    fun `the passthrough row explains when effects apply`() {
        val playing = PlayerUiState(audio = start, dspAvailable = false)
        assertTrue(passthroughDetail(playing).startsWith("On: sent to your receiver untouched"))
        assertEquals("On: this track plays as PCM, effects apply", passthroughDetail(playing.copy(dspAvailable = true)))
        assertEquals("Off: effects below apply", passthroughDetail(playing.copy(audio = start.copy(passthrough = false))))
    }
}
