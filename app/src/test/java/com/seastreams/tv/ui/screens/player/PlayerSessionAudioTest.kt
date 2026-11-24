package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.core.datastore.AudioSettings
import com.seastreams.tv.core.datastore.DialogueBoost
import com.seastreams.tv.core.datastore.PlaybackSpeed
import com.seastreams.tv.core.datastore.VolumeBoost
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

    @Test
    fun `starts from the profile's volume boost`() {
        assertEquals(VolumeBoost.PLUS_50, SessionAudio.from(AudioSettings(volumeBoost = VolumeBoost.PLUS_50)).volumeBoost)
    }

    @Test
    fun `a session starting at a non-Standard speed starts with passthrough off`() {
        val saved = AudioSettings(passthroughEnabled = true)
        assertFalse(SessionAudio.from(saved, PlaybackSpeed.PLUS_25).passthrough)
        assertFalse(SessionAudio.from(saved, PlaybackSpeed.MINUS_25).passthrough)
        assertTrue(SessionAudio.from(saved, PlaybackSpeed.STANDARD).passthrough)
    }

    @Test
    fun `a volume boost in the player switches passthrough off and it can go back on`() {
        val boosted = start.change { it.copy(volumeBoost = VolumeBoost.PLUS_100) }
        assertFalse(boosted.passthrough)
        val backOn = boosted.change { it.copy(passthrough = true) }
        assertTrue(backOn.passthrough)
        assertTrue(backOn.change { it.copy(volumeBoost = VolumeBoost.STANDARD) }.passthrough)
    }

    @Test
    fun `toDsp carries the gain and the speed`() {
        val dsp = start.copy(volumeBoost = VolumeBoost.PLUS_75, delayMs = 50).toDsp(PlaybackSpeed.PLUS_50)
        assertEquals(1.75f, dsp.volumeGain)
        assertEquals(1.5f, dsp.playbackSpeed)
        assertEquals(50, dsp.delayMs)
        assertEquals(1f, start.toDsp().playbackSpeed)
    }

    @Test
    fun `a HUD speed change switches session passthrough off once, and a manual re-enable survives`() {
        val playing = PlayerUiState(audio = start)
        val faster = playing.withPlaybackSpeed(PlaybackSpeed.PLUS_50)
        assertEquals(PlaybackSpeed.PLUS_50, faster.playbackSpeed)
        assertFalse(faster.audio.passthrough)
        val backOn = faster.copy(audio = faster.audio.copy(passthrough = true))
        // Re-picking the same speed or going back to Standard doesn't engage again; a new speed does.
        assertTrue(backOn.withPlaybackSpeed(PlaybackSpeed.PLUS_50).audio.passthrough)
        assertTrue(backOn.withPlaybackSpeed(PlaybackSpeed.STANDARD).audio.passthrough)
        assertFalse(backOn.withPlaybackSpeed(PlaybackSpeed.PLUS_100).audio.passthrough)
        assertTrue(playing.withPlaybackSpeed(PlaybackSpeed.STANDARD).audio.passthrough)
    }
}
