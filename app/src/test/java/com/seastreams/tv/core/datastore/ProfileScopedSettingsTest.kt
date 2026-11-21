package com.seastreams.tv.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.seastreams.tv.domain.model.StreamingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** General Settings are stored per profile (PRD §11): isolation, switching, deletion, migration. */
class ProfileScopedSettingsTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var raw: DataStore<Preferences>
    private lateinit var settings: SettingsDataStore

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        raw = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "scoped.preferences_pb") }
        settings = SettingsDataStore(raw)
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `changes on profile A never leak into profile B`() = runTest {
        settings.setActiveProfileId(A)
        customise()

        settings.setActiveProfileId(B)
        assertEquals(AppSettings(), settings.settings.first())
    }

    @Test
    fun `switching profiles emits each profile's own values`() = runTest {
        settings.setActiveProfileId(A)
        customise()
        settings.setActiveProfileId(B)
        settings.setBackgroundColor(TEAL)
        settings.updatePlayer { it.copy(playbackBufferProfile = PlaybackBufferProfile.STANDARD) }

        settings.settings.test {
            assertB(awaitItem())
            settings.setActiveProfileId(A)
            assertA(awaitItem())
            settings.setActiveProfileId(B)
            assertB(awaitItem())
        }
    }

    @Test
    fun `a new profile starts from the documented defaults`() = runTest {
        settings.setActiveProfileId(A)
        customise()
        settings.setActiveProfileId(NEW)

        val fresh = settings.settings.first()
        assertEquals(BackgroundSwatches.DEFAULT, fresh.appearance.backgroundArgb)
        assertEquals(PlaybackBufferProfile.LARGE, fresh.player.playbackBufferProfile)
        assertEquals(4, fresh.player.parallelStreamConnections)
        assertTrue(fresh.player.autoPlayNextEpisode)
        assertTrue(fresh.player.convertDvProfile7ToHdr)
        assertTrue(fresh.player.autoPlayTrailers)
        assertTrue(fresh.player.muteAutoPlayTrailers)
        assertEquals(2, fresh.player.trailerAutoPlayDelaySeconds)
        assertTrue(fresh.subtitles.autoSyncExternal)
    }

    @Test
    fun `deleting a profile clears only its settings and its active selection`() = runTest {
        settings.setTmdbApiKey("key")
        settings.setActiveProfileId(TEN) // `p10/` must survive clearing `p1/`.
        settings.setBackgroundColor(TEAL)
        settings.setActiveProfileId(A)
        customise()

        settings.clearProfile(A)

        assertNull(settings.activeProfileId.first())
        assertTrue(raw.data.first().asMap().keys.none { it.name.startsWith("p$A/") })
        settings.setActiveProfileId(A)
        assertEquals(AppSettings(), settings.settings.first())
        settings.setActiveProfileId(TEN)
        assertEquals(TEAL, settings.settings.first().appearance.backgroundArgb)
        assertEquals("key", settings.currentApiKey())
    }

    @Test
    fun `auto-sync external subtitles defaults on and is stored per profile`() = runTest {
        settings.setActiveProfileId(A)
        assertTrue(settings.settings.first().subtitles.autoSyncExternal)

        settings.updateSubtitles { it.copy(autoSyncExternal = false) }
        assertFalse(settings.settings.first().subtitles.autoSyncExternal)
        assertEquals(false, raw.data.first()[booleanPreferencesKey("p$A/subs_auto_sync")])

        settings.setActiveProfileId(B)
        assertTrue(settings.settings.first().subtitles.autoSyncExternal, "profile B keeps the default")
        settings.setActiveProfileId(A)
        assertFalse(SettingsDataStore(raw).settings.first().subtitles.autoSyncExternal, "re-read from disk")

        settings.clearProfile(A)
        assertNull(raw.data.first()[booleanPreferencesKey("p$A/subs_auto_sync")])
        settings.setActiveProfileId(A)
        assertTrue(settings.settings.first().subtitles.autoSyncExternal)
    }

    @Test
    fun `turning an effect on in Settings switches that profile's passthrough off, and it can go back on`() = runTest {
        settings.setActiveProfileId(A)
        assertTrue(settings.settings.first().audio.passthroughEnabled)

        settings.updateAudio { it.copy(dialogueBoost = DialogueBoost.PLUS_6) }
        assertFalse(settings.settings.first().audio.passthroughEnabled)

        settings.updateAudio { it.copy(passthroughEnabled = true) }
        assertTrue(settings.settings.first().audio.passthroughEnabled, "manual choice sticks")
        settings.updateAudio { it.copy(nightMode = true) }
        assertFalse(settings.settings.first().audio.passthroughEnabled)

        settings.setActiveProfileId(B)
        assertTrue(settings.settings.first().audio.passthroughEnabled, "other profiles untouched")
    }

    @Test
    fun `deleting an inactive profile keeps the active selection`() = runTest {
        settings.setActiveProfileId(B)
        settings.clearProfile(A)
        assertEquals(B, settings.activeProfileId.first())
    }

    @Test
    fun `automatic stream failover defaults on and is stored per profile`() = runTest {
        val key = booleanPreferencesKey("p$A/player_auto_stream_failover")
        settings.setActiveProfileId(A)
        assertTrue(settings.settings.first().player.autoStreamFailover)

        settings.updatePlayer { it.copy(autoStreamFailover = false) }
        assertFalse(settings.settings.first().player.autoStreamFailover)
        assertEquals(false, raw.data.first()[key])

        settings.setActiveProfileId(B)
        assertTrue(settings.settings.first().player.autoStreamFailover, "profile B keeps the default")
        settings.setActiveProfileId(A)
        assertFalse(SettingsDataStore(raw).settings.first().player.autoStreamFailover, "re-read from disk")

        settings.clearProfile(A)
        assertNull(raw.data.first()[key])
        settings.setActiveProfileId(A)
        assertTrue(settings.settings.first().player.autoStreamFailover)
    }

    @Test
    fun `a legacy app-wide failover value migrates into each profile without one`() = runTest {
        settings.setActiveProfileId(B)
        settings.updatePlayer { it.copy(autoStreamFailover = true) } // B already chose: never overwritten.
        raw.edit { it[booleanPreferencesKey("player_auto_stream_failover")] = false }

        settings.migrateLegacySettings(listOf(A, B))

        assertNull(raw.data.first()[booleanPreferencesKey("player_auto_stream_failover")])
        assertTrue(settings.settings.first().player.autoStreamFailover)
        settings.setActiveProfileId(A)
        assertFalse(settings.settings.first().player.autoStreamFailover)
    }

    @Test
    fun `legacy app-wide values are copied to every existing profile, once`() = runTest {
        writeLegacy()
        settings.setActiveProfileId(B)
        settings.setBackgroundColor(TEAL) // B already has its own colour: never overwritten.

        settings.migrateLegacySettings(listOf(A, B))

        val keys = raw.data.first().asMap().keys.map { it.name }
        assertFalse("ui_background_color" in keys)
        assertFalse("player_buffer_profile" in keys)
        assertEquals(TEAL, settings.settings.first().appearance.backgroundArgb)
        assertEquals(PlaybackBufferProfile.MAX, settings.settings.first().player.playbackBufferProfile)
        settings.setActiveProfileId(A)
        assertEquals(PLUM, settings.settings.first().appearance.backgroundArgb)
        assertFalse(settings.settings.first().audio.passthroughEnabled)

        writeLegacy(background = TEAL) // A second run is a no-op even if legacy keys reappear.
        settings.migrateLegacySettings(listOf(A))
        assertEquals(PLUM, settings.settings.first().appearance.backgroundArgb)
    }

    @Test
    fun `migration is marked done even when there are no profiles`() = runTest {
        writeLegacy()
        settings.migrateLegacySettings(emptyList())

        assertTrue(raw.data.first()[booleanPreferencesKey("legacy_settings_migrated_v1")] == true)
        assertNull(raw.data.first()[intPreferencesKey("ui_background_color")])
        settings.setActiveProfileId(A)
        assertEquals(AppSettings(), settings.settings.first())
    }

    @Test
    fun `retired scroll speed values are purged from every profile and the legacy key`() = runTest {
        raw.edit { prefs ->
            prefs[stringPreferencesKey(RETIRED)] = "FAST"
            prefs[stringPreferencesKey("p$A/$RETIRED")] = "MEDIUM"
            prefs[stringPreferencesKey("p$TEN/$RETIRED")] = "FAST"
        }
        settings.setActiveProfileId(A)
        customise()

        settings.purgeRetiredSettings()
        settings.purgeRetiredSettings() // Idempotent.

        val keys = raw.data.first().asMap().keys.map { it.name }
        assertTrue(keys.none { it.endsWith(RETIRED) }, "$keys")
        assertA(settings.settings.first())
        assertEquals(A, settings.activeProfileId.first())
    }

    @Test
    fun `only exact retired names are treated as retired`() {
        assertTrue(isRetiredSettingKey(RETIRED))
        assertTrue(isRetiredSettingKey("p12/$RETIRED"))
        assertFalse(isRetiredSettingKey("p1/ui_background_color"))
        assertFalse(isRetiredSettingKey("x1/$RETIRED"))
        assertFalse(isRetiredSettingKey("p1/${RETIRED}_v2"))
    }

    @Test
    fun `before migration a profile still sees the legacy values`() = runTest {
        writeLegacy()
        settings.setActiveProfileId(A)

        assertEquals(PLUM, settings.settings.first().appearance.backgroundArgb)
        assertEquals(PlaybackBufferProfile.MAX, settings.settings.first().player.playbackBufferProfile)
    }

    @Test
    fun `writes with no active profile are ignored`() = runTest {
        settings.setBackgroundColor(PLUM)
        settings.updateAudio { it.copy(nightMode = true) }

        assertEquals(AppSettings(), settings.settings.first())
        settings.setActiveProfileId(A)
        assertEquals(AppSettings(), settings.settings.first())
    }

    @Test
    fun `TMDB key and provider rows stay app-wide across a switch`() = runTest {
        settings.setActiveProfileId(A)
        settings.setTmdbApiKey("shared")
        settings.setProviderEnabled(StreamingProvider.entries.first(), enabled = false)

        settings.setActiveProfileId(B)
        assertEquals("shared", settings.currentApiKey())
        assertFalse(StreamingProvider.entries.first() in settings.enabledProviders.first())
    }

    private suspend fun customise() {
        settings.setBackgroundColor(PLUM)
        settings.updatePlayer {
            it.copy(
                playbackBufferProfile = PlaybackBufferProfile.MAX,
                parallelStreamConnections = 1,
                trailerAutoPlayDelaySeconds = 6,
                autoPlayNextEpisode = false,
                playerChoice = PlayerChoice.SystemChooser,
            )
        }
        settings.updateAudio { it.copy(passthroughEnabled = false, dialogueBoost = DialogueBoost.PLUS_6, audioDelayMs = 100) }
        settings.updateSubtitles {
            it.copy(mode = SubtitleMode.ALWAYS_ON, cleanSdh = false, style = it.style.copy(fontScale = 1.5f, bold = false))
        }
    }

    private fun assertA(s: AppSettings) {
        assertEquals(PLUM, s.appearance.backgroundArgb)
        assertEquals(PlaybackBufferProfile.MAX, s.player.playbackBufferProfile)
        assertEquals(1, s.player.parallelStreamConnections)
        assertEquals(6, s.player.trailerAutoPlayDelaySeconds)
        assertFalse(s.player.autoPlayNextEpisode)
        assertEquals(PlayerChoice.SystemChooser, s.player.playerChoice)
        assertFalse(s.audio.passthroughEnabled)
        assertEquals(DialogueBoost.PLUS_6, s.audio.dialogueBoost)
        assertEquals(100, s.audio.audioDelayMs)
        assertEquals(SubtitleMode.ALWAYS_ON, s.subtitles.mode)
        assertFalse(s.subtitles.cleanSdh)
        assertEquals(1.5f, s.subtitles.style.fontScale)
        assertFalse(s.subtitles.style.bold)
    }

    private fun assertB(s: AppSettings) {
        assertEquals(AppSettings().copy(
            appearance = AppearanceSettings(backgroundArgb = TEAL),
            player = PlayerSettings(playbackBufferProfile = PlaybackBufferProfile.STANDARD),
        ), s)
    }

    private suspend fun writeLegacy(background: Int = PLUM) = raw.edit {
        it[intPreferencesKey("ui_background_color")] = background
        it[stringPreferencesKey("player_buffer_profile")] = PlaybackBufferProfile.MAX.name
        it[booleanPreferencesKey("audio_passthrough")] = false
    }

    private companion object {
        const val A = 1L
        const val B = 2L
        const val NEW = 3L
        const val TEN = 10L
        val PLUM = BackgroundSwatches.ALL[2]
        val TEAL = BackgroundSwatches.ALL[4]
        const val RETIRED = "ui_catalog_row_scroll_speed"
    }
}
