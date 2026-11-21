package com.seastreams.tv.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

class SettingsDataStoreTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var rawStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var settings: SettingsDataStore

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        rawStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }
        settings = SettingsDataStore(rawStore)
        // General Settings are per profile: the round-trip tests below run inside one profile.
        runBlocking { settings.setActiveProfileId(PROFILE_ID) }
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `a fresh profile emits PRD defaults`() = runTest {
        val defaults = settings.settings.first()

        assertEquals(AppSettings(), defaults)
        assertEquals(10, defaults.player.fallbackTimeoutSeconds)
        assertEquals(true, defaults.player.autoStreamFailover)
        assertEquals(PlayerChoice.BuiltIn, defaults.player.playerChoice)
        assertEquals(DialogueBoost.OFF, defaults.audio.dialogueBoost)
        assertEquals(0, defaults.audio.audioDelayMs)
        assertNull(settings.tmdbApiKey.first())
        assertEquals(PROFILE_ID, settings.activeProfileId.first())
    }

    @Test
    fun `TMDB key is trimmed, observable and cleared by blank input`() = runTest {
        settings.tmdbApiKey.test {
            assertNull(awaitItem())

            settings.setTmdbApiKey("  abc123  ")
            assertEquals("abc123", awaitItem())
            assertEquals("abc123", settings.currentApiKey())

            settings.setTmdbApiKey("   ")
            assertNull(awaitItem())
        }
    }

    @Test
    fun `active profile id can be set and cleared`() = runTest {
        settings.activeProfileId.test {
            assertEquals(PROFILE_ID, awaitItem())
            settings.setActiveProfileId(3L)
            assertEquals(3L, awaitItem())
            settings.setActiveProfileId(null)
            assertNull(awaitItem())
        }
    }

    @ParameterizedTest(name = "{0}s -> {1}s")
    @CsvSource("0, 10", "1, 10", "5, 10", "10, 10", "12, 10", "13, 15", "15, 15", "17, 15", "18, 20", "20, 20", "42, 20", "-3, 10")
    fun `stream fallback timeout snaps to 10, 15 or 20 seconds`(input: Int, expected: Int) = runTest {
        settings.updatePlayer { it.copy(fallbackTimeoutSeconds = input) }
        assertEquals(expected, settings.settings.first().player.fallbackTimeoutSeconds)
    }

    @ParameterizedTest(name = "stored {0}s reads as {1}s")
    @CsvSource("1, 10", "5, 10", "7, 10", "10, 10", "15, 15", "25, 20")
    fun `a stored timeout from the old 1-10s range maps onto the new options`(stored: Int, expected: Int) = runTest {
        rawStore.edit { it[intPreferencesKey("${SCOPE}player_fallback_timeout_s")] = stored }
        assertEquals(expected, settings.settings.first().player.fallbackTimeoutSeconds)
    }

    @Test
    fun `turning automatic stream failover off keeps the fallback timeout`() = runTest {
        settings.updatePlayer { it.copy(fallbackTimeoutSeconds = 15) }
        settings.updatePlayer { it.copy(autoStreamFailover = false) }
        val player = settings.settings.first().player
        assertEquals(false, player.autoStreamFailover)
        assertEquals(15, player.fallbackTimeoutSeconds, "the hidden timeout is restored when failover goes back on")

        settings.updatePlayer { it.copy(autoStreamFailover = true) }
        assertEquals(true, settings.settings.first().player.autoStreamFailover)
    }

    @ParameterizedTest(name = "{0}ms -> {1}ms")
    @CsvSource("37, 25", "38, 50", "-12, 0", "-13, -25", "500, 500", "510, 500", "-900, -500", "250, 250")
    fun `audio delay snaps to 25ms steps within plus or minus 500ms`(input: Int, expected: Int) = runTest {
        settings.updateAudio { it.copy(audioDelayMs = input) }
        assertEquals(expected, settings.settings.first().audio.audioDelayMs)
    }

    @Test
    fun `audio and player updates persist and emit through the settings flow`() = runTest {
        settings.settings.test {
            assertEquals(AppSettings(), awaitItem())

            settings.updateAudio { it.copy(passthroughEnabled = false, dialogueBoost = DialogueBoost.PLUS_9, nightMode = true) }
            val audio = awaitItem().audio
            assertEquals(false, audio.passthroughEnabled)
            assertEquals(DialogueBoost.PLUS_9, audio.dialogueBoost)
            assertEquals(true, audio.nightMode)

            settings.updatePlayer { it.copy(playerChoice = PlayerChoice.App("org.videolan.vlc"), preferredAutoPlayAddonUrl = "https://a/manifest.json") }
            val player = awaitItem().player
            assertEquals(PlayerChoice.App("org.videolan.vlc"), player.playerChoice)
            assertEquals("https://a/manifest.json", player.preferredAutoPlayAddonUrl)

            settings.updatePlayer { it.copy(preferredAutoPlayAddonUrl = null) }
            assertNull(awaitItem().player.preferredAutoPlayAddonUrl)
        }
    }

    @Test
    fun `subtitle style values are clamped`() = runTest {
        settings.updateSubtitles {
            it.copy(
                mode = SubtitleMode.ALWAYS_ON,
                cleanSdh = true,
                style = it.style.copy(fontScale = 9f, backgroundOpacity = -1f, edgeType = SubtitleEdgeType.DROP_SHADOW),
            )
        }

        val subs = settings.settings.first().subtitles
        assertEquals(SubtitleMode.ALWAYS_ON, subs.mode)
        assertEquals(true, subs.cleanSdh)
        assertEquals(SubtitleStyle.MAX_FONT_SCALE, subs.style.fontScale)
        assertEquals(0f, subs.style.backgroundOpacity)
        assertEquals(SubtitleEdgeType.DROP_SHADOW, subs.style.edgeType)
    }

    @Test
    fun `unknown enum values written by an older or newer build fall back to defaults`() = runTest {
        rawStore.edit {
            it[stringPreferencesKey("${SCOPE}audio_dialogue_boost")] = "PLUS_99"
            it[stringPreferencesKey("${SCOPE}player_external")] = "WINAMP"
        }

        val loaded = settings.settings.first()
        assertEquals(DialogueBoost.OFF, loaded.audio.dialogueBoost)
        assertEquals(PlayerChoice.BuiltIn, loaded.player.playerChoice)
    }

    @Test
    fun `Convert Dolby Vision Profile 7 and Auto-Play Next Episode default to true`() = runTest {
        val player = settings.settings.first().player

        assertTrue(player.convertDvProfile7ToHdr)
        assertTrue(player.autoPlayNextEpisode)
        assertTrue(PlayerSettings().convertDvProfile7ToHdr)
        assertTrue(PlayerSettings().autoPlayNextEpisode)
    }

    @Test
    fun `Dolby Vision and auto-next toggles persist when switched off and back on`() = runTest {
        settings.updatePlayer { it.copy(convertDvProfile7ToHdr = false, autoPlayNextEpisode = false) }
        val off = SettingsDataStore(rawStore).settings.first().player
        assertFalse(off.convertDvProfile7ToHdr)
        assertFalse(off.autoPlayNextEpisode)

        settings.updatePlayer { it.copy(convertDvProfile7ToHdr = true, autoPlayNextEpisode = true) }
        val on = settings.settings.first().player
        assertTrue(on.convertDvProfile7ToHdr)
        assertTrue(on.autoPlayNextEpisode)
    }

    @Test
    fun `background defaults to pure black`() = runTest {
        assertEquals(0xFF000000.toInt(), settings.settings.first().appearance.backgroundArgb)
        assertEquals(BackgroundSwatches.DEFAULT, BackgroundSwatches.ALL.first())
    }

    @Test
    fun `chosen background colour persists across store instances`() = runTest {
        settings.setBackgroundColor(0xFF351529.toInt())

        assertEquals(0xFF351529.toInt(), SettingsDataStore(rawStore).settings.first().appearance.backgroundArgb)
    }

    @Test
    fun `every swatch can be saved, anything else is ignored`() = runTest {
        BackgroundSwatches.ALL.forEach { swatch ->
            settings.setBackgroundColor(swatch)
            assertEquals(swatch, settings.settings.first().appearance.backgroundArgb)
        }
        settings.setBackgroundColor(0xFF123456.toInt())
        assertEquals(BackgroundSwatches.ALL.last(), settings.settings.first().appearance.backgroundArgb)
    }

    @Test
    fun `a stored colour that is no longer a swatch falls back to black`() = runTest {
        rawStore.edit { it[intPreferencesKey("${SCOPE}ui_background_color")] = 0xFF00FF00.toInt() }

        assertEquals(BackgroundSwatches.DEFAULT, settings.settings.first().appearance.backgroundArgb)
    }

    @Test
    fun `external player choices round-trip, including the system chooser`() = runTest {
        listOf(PlayerChoice.SystemChooser, PlayerChoice.App("com.brouken.player"), PlayerChoice.BuiltIn).forEach { choice ->
            settings.updatePlayer { it.copy(playerChoice = choice) }
            assertEquals(choice, settings.settings.first().player.playerChoice)
        }
    }

    @Test
    fun `Milestone 1 external player values map to their packages`() = runTest {
        rawStore.edit { it[stringPreferencesKey("${SCOPE}player_external")] = "MX_PLAYER" }
        assertEquals(PlayerChoice.App("com.mxtech.videoplayer.ad"), settings.settings.first().player.playerChoice)

        rawStore.edit { it[stringPreferencesKey("${SCOPE}player_external")] = "NONE" }
        assertEquals(PlayerChoice.BuiltIn, settings.settings.first().player.playerChoice)
    }

    @Test
    fun `swatches are the seven picker colours in order, ending with espresso`() {
        val expected = listOf(0xFF000000, 0xFF171535, 0xFF351529, 0xFF351515, 0xFF153535, 0xFF090B34, 0xFF472A11)
        assertEquals(expected.map { it.toInt() }, BackgroundSwatches.ALL)
    }

    @Test
    fun `the deep navy and espresso swatches persist across store instances`() = runTest {
        listOf(0xFF090B34.toInt(), 0xFF472A11.toInt()).forEach { swatch ->
            settings.setBackgroundColor(swatch)
            assertEquals(swatch, SettingsDataStore(rawStore).settings.first().appearance.backgroundArgb)
        }
    }

    @Test
    fun `the replaced navy and ochre colours are no longer selectable`() = runTest {
        listOf(0xFF151F35.toInt(), 0xFF916405.toInt()).forEach { old ->
            assertFalse(old in BackgroundSwatches.ALL)
            settings.setBackgroundColor(old)
            assertEquals(BackgroundSwatches.DEFAULT, settings.settings.first().appearance.backgroundArgb)
        }
    }

    @Test
    fun `a leftover scroll speed value is ignored and purged`() = runTest {
        rawStore.edit { it[stringPreferencesKey("${SCOPE}ui_catalog_row_scroll_speed")] = "FAST" }
        settings.setBackgroundColor(0xFF472A11.toInt())

        settings.purgeRetiredSettings()

        assertNull(rawStore.data.first()[stringPreferencesKey("${SCOPE}ui_catalog_row_scroll_speed")])
        assertEquals(0xFF472A11.toInt(), settings.settings.first().appearance.backgroundArgb)
    }

    private companion object {
        const val PROFILE_ID = 1L
        const val SCOPE = "p$PROFILE_ID/"
    }
}
