package com.seastreams.tv.ui.screens.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.PlaybackSpeed
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.datastore.VolumeBoost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * PRD §9: HUD speed and volume changes are session-only. They act on [PlayerUiState] alone, never on
 * [SettingsDataStore], so the next playback starts from the profile's General Settings defaults again.
 */
class PlayerSessionIsolationTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var raw: DataStore<Preferences>
    private lateinit var settings: SettingsDataStore

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        raw = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "session.preferences_pb") }
        settings = SettingsDataStore(raw)
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    /** The state a media session opens with (mirrors `PlayerViewModel.open`). */
    private fun sessionFrom(saved: AppSettings): PlayerUiState {
        val speed = saved.player.defaultPlaybackSpeed
        return PlayerUiState(audio = SessionAudio.from(saved.audio, speed), playbackSpeed = speed)
    }

    @Test
    fun `HUD changes never touch the stored defaults, and the next session starts from them`() = runTest {
        settings.setActiveProfileId(1L)
        settings.updatePlayer { it.copy(defaultPlaybackSpeed = PlaybackSpeed.PLUS_50) }
        settings.updateAudio { it.copy(volumeBoost = VolumeBoost.PLUS_25) }
        val saved = settings.settings.first()

        val opened = sessionFrom(saved)
        assertEquals(PlaybackSpeed.PLUS_50, opened.playbackSpeed)
        assertEquals(VolumeBoost.PLUS_25, opened.audio.volumeBoost)

        val previous = opened.audio
        val edited = opened.withPlaybackSpeed(PlaybackSpeed.PLUS_100)
            .let { it.copy(audio = it.audio.copy(volumeBoost = VolumeBoost.STANDARD).withPassthroughAutoOff(previous)) }
        assertEquals(PlaybackSpeed.PLUS_100, edited.playbackSpeed)
        assertEquals(VolumeBoost.STANDARD, edited.audio.volumeBoost)

        assertEquals(saved, settings.settings.first(), "stored settings unchanged")
        assertEquals(saved, SettingsDataStore(raw).settings.first(), "unchanged on disk")
        val next = sessionFrom(settings.settings.first())
        assertEquals(PlaybackSpeed.PLUS_50, next.playbackSpeed)
        assertEquals(VolumeBoost.PLUS_25, next.audio.volumeBoost)
    }

    @Test
    fun `a closed player resets to Standard until the next session reads settings`() {
        val fresh = PlayerUiState()
        assertEquals(PlaybackSpeed.STANDARD, fresh.playbackSpeed)
        assertEquals(VolumeBoost.STANDARD, fresh.audio.volumeBoost)
    }
}
