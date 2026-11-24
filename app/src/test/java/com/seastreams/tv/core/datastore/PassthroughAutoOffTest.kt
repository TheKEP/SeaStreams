package com.seastreams.tv.core.datastore

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PRD §9: turning an audio effect on switches passthrough off; the user can switch it back on. */
class PassthroughAutoOffTest {

    private val passthrough = AudioSettings(passthroughEnabled = true)

    private fun AudioSettings.change(transform: (AudioSettings) -> AudioSettings) = transform(this).withPassthroughAutoOff(this)

    @Test
    fun `turning on any effect switches passthrough off`() {
        assertFalse(passthrough.change { it.copy(dialogueBoost = DialogueBoost.PLUS_3) }.passthroughEnabled)
        assertFalse(passthrough.change { it.copy(nightMode = true) }.passthroughEnabled)
        assertFalse(passthrough.change { it.copy(audioDelayMs = 25) }.passthroughEnabled)
        assertFalse(passthrough.change { it.copy(audioDelayMs = -25) }.passthroughEnabled)
    }

    @Test
    fun `passthrough switched back on stays on while effects stay set`() {
        val boosted = passthrough.change { it.copy(dialogueBoost = DialogueBoost.PLUS_6, nightMode = true, audioDelayMs = 100) }
        val backOn = boosted.change { it.copy(passthroughEnabled = true) }
        assertTrue(backOn.passthroughEnabled)
        assertTrue(backOn.change { it.copy(preferredAudioLanguage = "de") }.passthroughEnabled)
        // A further effect change engages again.
        assertFalse(backOn.change { it.copy(dialogueBoost = DialogueBoost.PLUS_9) }.passthroughEnabled)
        assertFalse(backOn.change { it.copy(audioDelayMs = 125) }.passthroughEnabled)
    }

    @Test
    fun `turning effects off or resetting the delay leaves passthrough alone`() {
        val effects = AudioSettings(passthroughEnabled = true, dialogueBoost = DialogueBoost.PLUS_3, nightMode = true, audioDelayMs = 50)
        assertTrue(effects.change { it.copy(dialogueBoost = DialogueBoost.OFF) }.passthroughEnabled)
        assertTrue(effects.change { it.copy(nightMode = false) }.passthroughEnabled)
        assertTrue(effects.change { it.copy(audioDelayMs = 0) }.passthroughEnabled)
        assertTrue(passthrough.change { it.copy(preferredAudioLanguage = "fr") }.passthroughEnabled)
    }

    @Test
    fun `a delay step past the limit changes nothing, so passthrough stays`() {
        val atLimit = AudioSettings(passthroughEnabled = true, audioDelayMs = AudioSettings.AUDIO_DELAY_LIMIT_MS)
        assertTrue(atLimit.change { it.copy(audioDelayMs = it.audioDelayMs + AudioSettings.AUDIO_DELAY_STEP_MS) }.passthroughEnabled)
    }

    @Test
    fun `with passthrough already off nothing changes`() {
        val off = AudioSettings(passthroughEnabled = false)
        assertFalse(off.change { it.copy(nightMode = true) }.passthroughEnabled)
        assertFalse(off.change { it.copy(passthroughEnabled = false) }.passthroughEnabled)
    }

    @Test
    fun `engagesDsp only counts effects being turned on`() {
        val off = DspLevels(DialogueBoost.OFF, nightMode = false, delayMs = 0, volumeBoost = VolumeBoost.STANDARD)
        val on = DspLevels(DialogueBoost.PLUS_3, nightMode = true, delayMs = 75, volumeBoost = VolumeBoost.PLUS_50)
        assertTrue(engagesDsp(off, off.copy(dialogueBoost = DialogueBoost.PLUS_12)))
        assertTrue(engagesDsp(on, on.copy(dialogueBoost = DialogueBoost.PLUS_6)))
        assertTrue(engagesDsp(off, off.copy(volumeBoost = VolumeBoost.PLUS_25)))
        assertTrue(engagesDsp(on, on.copy(volumeBoost = VolumeBoost.PLUS_100)))
        assertFalse(engagesDsp(on, on))
        assertFalse(engagesDsp(on, off))
        assertFalse(engagesDsp(on, on.copy(volumeBoost = VolumeBoost.STANDARD)))
    }

    @Test
    fun `a volume boost switches passthrough off and it can go back on`() {
        val boosted = passthrough.change { it.copy(volumeBoost = VolumeBoost.PLUS_25) }
        assertFalse(boosted.passthroughEnabled)
        val backOn = boosted.change { it.copy(passthroughEnabled = true) }
        assertTrue(backOn.passthroughEnabled)
        assertTrue(backOn.change { it.copy(volumeBoost = VolumeBoost.STANDARD) }.passthroughEnabled)
        assertFalse(backOn.change { it.copy(volumeBoost = VolumeBoost.PLUS_75) }.passthroughEnabled)
    }
}
