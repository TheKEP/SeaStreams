package com.seastreams.tv.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/** Milestone 6 playback settings: buffer profile, parallel connections and inline trailers. */
class PlaybackSettingsDataStoreTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var raw: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var settings: SettingsDataStore

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        raw = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "playback.preferences_pb") }
        settings = SettingsDataStore(raw)
        runBlocking { settings.setActiveProfileId(PROFILE_ID) }
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `defaults - trailers on and muted after 2s, Large buffer, 4 connections`() = runTest {
        val player = settings.settings.first().player
        assertTrue(player.autoPlayTrailers)
        assertTrue(player.muteAutoPlayTrailers)
        assertEquals(2, player.trailerAutoPlayDelaySeconds)
        assertEquals(PlaybackBufferProfile.LARGE, player.playbackBufferProfile)
        assertEquals(4, player.parallelStreamConnections)
        assertTrue(player.convertDvProfile7ToHdr)
        assertTrue(player.autoPlayNextEpisode)
        assertEquals(10, player.fallbackTimeoutSeconds)
        assertTrue(player.autoStreamFailover)
    }

    @ParameterizedTest
    @CsvSource("0, 1", "1, 1", "4, 4", "7, 7", "8, 7", "-3, 1")
    fun `trailer delay is clamped to 1s to 7s`(written: Int, stored: Int) = runTest {
        settings.updatePlayer { it.copy(trailerAutoPlayDelaySeconds = written) }
        assertEquals(stored, settings.settings.first().player.trailerAutoPlayDelaySeconds)
    }

    @Test
    fun `an out of range delay already on disk is clamped when read`() = runTest {
        raw.edit { it[intPreferencesKey("${SCOPE}player_trailer_delay_s")] = 30 }
        assertEquals(7, settings.settings.first().player.trailerAutoPlayDelaySeconds)
    }

    @ParameterizedTest
    @CsvSource("0, 1", "1, 1", "2, 2", "4, 4", "9, 4")
    fun `parallel connections are clamped to 1 to 4`(written: Int, stored: Int) = runTest {
        settings.updatePlayer { it.copy(parallelStreamConnections = written) }
        assertEquals(stored, settings.settings.first().player.parallelStreamConnections)
    }

    @Test
    fun `trailer toggles, buffer profile and connections round-trip`() = runTest {
        settings.updatePlayer {
            it.copy(
                autoPlayTrailers = false,
                muteAutoPlayTrailers = false,
                playbackBufferProfile = PlaybackBufferProfile.MAX,
                parallelStreamConnections = 1,
            )
        }
        val player = SettingsDataStore(raw).settings.first().player
        assertFalse(player.autoPlayTrailers)
        assertFalse(player.muteAutoPlayTrailers)
        assertEquals(PlaybackBufferProfile.MAX, player.playbackBufferProfile)
        assertEquals(1, player.parallelStreamConnections)
    }

    @Test
    fun `an unknown stored buffer profile falls back to Large`() = runTest {
        raw.edit { it[stringPreferencesKey("${SCOPE}player_buffer_profile")] = "HUGE" }
        assertEquals(PlaybackBufferProfile.LARGE, settings.settings.first().player.playbackBufferProfile)
    }

    @Test
    fun `Dolby Vision playback defaults to Auto and leaves the Profile 7 toggle on`() = runTest {
        val player = settings.settings.first().player
        assertEquals(DolbyVisionPlayback.AUTO, player.dolbyVisionPlayback)
        assertTrue(player.convertDvProfile7ToHdr)
        assertTrue(player.hdrWhiteDimming)
    }

    @ParameterizedTest
    @CsvSource("NATIVE", "HDR10_BASE_LAYER", "AUTO")
    fun `Dolby Vision playback round-trips under the profile's key`(mode: DolbyVisionPlayback) = runTest {
        settings.updatePlayer { it.copy(dolbyVisionPlayback = mode, hdrWhiteDimming = false) }
        val player = SettingsDataStore(raw).settings.first().player
        assertEquals(mode, player.dolbyVisionPlayback)
        assertFalse(player.hdrWhiteDimming)
        assertEquals(mode.name, raw.data.first()[stringPreferencesKey("${SCOPE}player_dolby_vision_playback")])
    }

    @Test
    fun `an unknown stored Dolby Vision mode falls back to Auto`() = runTest {
        raw.edit { it[stringPreferencesKey("${SCOPE}player_dolby_vision_playback")] = "PROFILE_9" }
        assertEquals(DolbyVisionPlayback.AUTO, settings.settings.first().player.dolbyVisionPlayback)
    }

    private companion object {
        const val PROFILE_ID = 1L
        const val SCOPE = "p$PROFILE_ID/"
    }
}
