package com.seastreams.tv.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** `Default playback speed` and `Default volume level` are stored per profile under `p{id}/` (PRD §11). */
class PlaybackLevelsSettingsTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var raw: DataStore<Preferences>
    private lateinit var settings: SettingsDataStore

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        raw = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "levels.preferences_pb") }
        settings = SettingsDataStore(raw)
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `a new profile starts at Standard speed and Standard volume`() = runTest {
        settings.setActiveProfileId(A)
        val current = settings.settings.first()
        assertEquals(PlaybackSpeed.STANDARD, current.player.defaultPlaybackSpeed)
        assertEquals(VolumeBoost.STANDARD, current.audio.volumeBoost)
    }

    @Test
    fun `each profile keeps its own speed and volume`() = runTest {
        settings.setActiveProfileId(A)
        settings.updatePlayer { it.copy(defaultPlaybackSpeed = PlaybackSpeed.PLUS_50) }
        settings.updateAudio { it.copy(volumeBoost = VolumeBoost.PLUS_75) }

        settings.setActiveProfileId(B)
        assertEquals(PlaybackSpeed.STANDARD, settings.settings.first().player.defaultPlaybackSpeed)
        assertEquals(VolumeBoost.STANDARD, settings.settings.first().audio.volumeBoost)
        settings.updatePlayer { it.copy(defaultPlaybackSpeed = PlaybackSpeed.MINUS_25) }

        settings.setActiveProfileId(A)
        val reread = SettingsDataStore(raw).settings.first()
        assertEquals(PlaybackSpeed.PLUS_50, reread.player.defaultPlaybackSpeed, "re-read from disk")
        assertEquals(VolumeBoost.PLUS_75, reread.audio.volumeBoost)
        val prefs = raw.data.first()
        assertEquals("PLUS_50", prefs[stringPreferencesKey("p$A/player_default_speed")])
        assertEquals("PLUS_75", prefs[stringPreferencesKey("p$A/audio_volume_boost")])
        assertEquals("MINUS_25", prefs[stringPreferencesKey("p$B/player_default_speed")])
    }

    @Test
    fun `an unknown stored value reads as Standard`() = runTest {
        settings.setActiveProfileId(A)
        raw.edit {
            it[stringPreferencesKey("p$A/player_default_speed")] = "WARP_9"
            it[stringPreferencesKey("p$A/audio_volume_boost")] = "ELEVEN"
        }
        val current = settings.settings.first()
        assertEquals(PlaybackSpeed.STANDARD, current.player.defaultPlaybackSpeed)
        assertEquals(VolumeBoost.STANDARD, current.audio.volumeBoost)
    }

    @Test
    fun `both keys are per-profile keys, so deleting a profile clears them`() = runTest {
        val names = ProfileSettingsKeys.ALL.map { it.name }
        assertTrue("player_default_speed" in names)
        assertTrue("audio_volume_boost" in names)

        settings.setActiveProfileId(A)
        settings.updatePlayer { it.copy(defaultPlaybackSpeed = PlaybackSpeed.PLUS_100) }
        settings.clearProfile(A)
        assertFalse(raw.data.first().asMap().keys.any { it.name.startsWith("p$A/") })
    }

    @Test
    fun `a default volume boost switches the stored passthrough off, and it can go back on`() = runTest {
        settings.setActiveProfileId(A)
        assertTrue(settings.settings.first().audio.passthroughEnabled)
        settings.updateAudio { it.copy(volumeBoost = VolumeBoost.PLUS_25) }
        assertFalse(settings.settings.first().audio.passthroughEnabled)
        settings.updateAudio { it.copy(passthroughEnabled = true) }
        assertTrue(settings.settings.first().audio.passthroughEnabled)
        assertEquals(VolumeBoost.PLUS_25, settings.settings.first().audio.volumeBoost)
    }

    @Test
    fun `a default speed never changes the stored passthrough`() = runTest {
        settings.setActiveProfileId(A)
        settings.updatePlayer { it.copy(defaultPlaybackSpeed = PlaybackSpeed.PLUS_25) }
        assertTrue(settings.settings.first().audio.passthroughEnabled)
    }

    private companion object {
        const val A = 1L
        const val B = 2L
    }
}
