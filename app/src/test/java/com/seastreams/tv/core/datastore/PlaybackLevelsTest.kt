package com.seastreams.tv.core.datastore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Playback speed and volume boost steps (PRD §9/§11). */
class PlaybackLevelsTest {

    @Test
    fun `speed options are -25 percent, Standard and four faster steps`() {
        assertEquals(listOf(-25, 0, 25, 50, 75, 100), PlaybackSpeed.entries.map { it.percent })
        assertEquals(listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f), PlaybackSpeed.entries.map { it.multiplier })
        assertEquals(PlaybackSpeed.STANDARD, PlaybackSpeed.DEFAULT)
    }

    @Test
    fun `every speed is 1 plus its percentage and a valid positive Media3 speed`() {
        PlaybackSpeed.entries.forEach { speed ->
            assertEquals(1f + speed.percent / 100f, speed.multiplier, 1e-6f, speed.name)
            assertTrue(speed.multiplier > 0f && speed.multiplier <= 2f, speed.name)
        }
    }

    @Test
    fun `speed labels show the step and the real speed`() {
        assertEquals(
            listOf("-25% (0.75x)", "Standard (1.0x)", "+25% (1.25x)", "+50% (1.5x)", "+75% (1.75x)", "+100% (2.0x)"),
            PlaybackSpeed.entries.map { it.label },
        )
    }

    @Test
    fun `volume boost gains run from x1 to x2`() {
        assertEquals(listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f), VolumeBoost.entries.map { it.gain })
        assertEquals(listOf("Standard", "+25%", "+50%", "+75%", "+100%"), VolumeBoost.entries.map { it.label })
        assertEquals(VolumeBoost.STANDARD, VolumeBoost.DEFAULT)
    }

    @Test
    fun `defaults are Standard speed and Standard volume`() {
        assertEquals(PlaybackSpeed.STANDARD, PlayerSettings().defaultPlaybackSpeed)
        assertEquals(VolumeBoost.STANDARD, AudioSettings().volumeBoost)
    }
}
