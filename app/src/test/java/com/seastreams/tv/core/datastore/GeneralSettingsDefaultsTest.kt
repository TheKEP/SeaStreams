package com.seastreams.tv.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** PRD §11: every General Settings default a new profile (or any key with no stored value) reads back. */
class GeneralSettingsDefaultsTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var raw: DataStore<Preferences>
    private lateinit var settings: SettingsDataStore

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        raw = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "defaults.preferences_pb") }
        settings = SettingsDataStore(raw)
        runBlocking { settings.setActiveProfileId(PROFILE_ID) }
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `appearance - black background and unblurred episode thumbnails`() = runTest {
        val s = settings.settings.first()
        assertEquals(0xFF000000.toInt(), s.appearance.backgroundArgb)
        assertFalse(s.player.blurUnwatchedThumbnails)
    }

    @Test
    fun `playback - auto-play, failover, buffer, connections and trailers`() = runTest {
        val p = settings.settings.first().player
        assertTrue(p.autoPlayTopStream)
        assertNull(p.preferredAutoPlayAddonUrl, "null = the first enabled stream addon")
        assertTrue(p.autoPlayNextEpisode)
        assertTrue(p.autoPlayFromContinueWatching)
        assertTrue(p.autoStreamFailover)
        assertEquals(10, p.fallbackTimeoutSeconds)
        assertEquals(PlaybackBufferProfile.LARGE, p.playbackBufferProfile)
        assertEquals("Large (2 min)", p.playbackBufferProfile.label)
        assertEquals(4, p.parallelStreamConnections)
        assertTrue(p.autoPlayTrailers)
        assertTrue(p.muteAutoPlayTrailers)
    }

    @Test
    fun `playback - ExoPlayer with Dolby Vision and HDR defaults`() = runTest {
        val p = settings.settings.first().player
        assertEquals(PlayerChoice.BuiltIn, p.playerChoice)
        assertTrue(p.convertDvProfile7ToHdr)
        assertEquals(DolbyVisionPlayback.AUTO, p.dolbyVisionPlayback)
        assertTrue(p.hdrWhiteDimming)
    }

    @Test
    fun `audio - passthrough on, no effects, no delay, English`() = runTest {
        val a = settings.settings.first().audio
        assertTrue(a.passthroughEnabled)
        assertEquals(DialogueBoost.OFF, a.dialogueBoost)
        assertFalse(a.nightMode)
        assertEquals(0, a.audioDelayMs)
        assertEquals("en", a.preferredAudioLanguage)
    }

    @Test
    fun `subtitles - always off, English, hide non-preferred, clean SDH, auto-sync`() = runTest {
        val sub = settings.settings.first().subtitles
        assertEquals(SubtitleMode.ALWAYS_OFF, sub.mode)
        assertEquals("en", sub.preferredLanguage)
        assertTrue(sub.hideNonPreferredLanguages)
        assertTrue(sub.cleanSdh)
        assertTrue(sub.autoSyncExternal)
    }

    @Test
    fun `subtitle styling - 100 percent, bold, upright, white, outline, transparent background`() = runTest {
        val style = settings.settings.first().subtitles.style
        assertEquals(1.0f, style.fontScale)
        assertTrue(style.bold)
        assertFalse(style.italic)
        assertEquals(0xFFFFFFFF.toInt(), style.textColor)
        assertEquals(SubtitleEdgeType.OUTLINE, style.edgeType)
        assertEquals(0f, style.backgroundOpacity)
    }

    @Test
    fun `the defaults come from the reader and nothing is written for a fresh profile`() = runTest {
        assertEquals(AppSettings(), settings.settings.first())
        val stored = raw.data.first().asMap().keys.map { it.name }
        assertTrue(stored.none { it.startsWith(profileKeyPrefix(PROFILE_ID)) }, "stored: $stored")
    }

    @Test
    fun `defaults are emitted while no profile is active`() = runTest {
        settings.setActiveProfileId(null)
        assertEquals(AppSettings(), settings.settings.first())
    }

    @Test
    fun `keys with no stored value keep their defaults when other keys are saved`() = runTest {
        settings.setBackgroundColor(BackgroundSwatches.ALL.last())
        val s = settings.settings.first()
        assertEquals(BackgroundSwatches.ALL.last(), s.appearance.backgroundArgb)
        assertEquals(AppSettings().copy(appearance = s.appearance), s)
    }

    @Test
    fun `a new profile starts from the defaults, not another profile's values`() = runTest {
        settings.updatePlayer { it.copy(autoPlayTopStream = false, hdrWhiteDimming = false, autoPlayFromContinueWatching = false) }
        settings.updateSubtitles {
            it.copy(hideNonPreferredLanguages = false, cleanSdh = false, style = it.style.copy(bold = false))
        }
        settings.setActiveProfileId(OTHER_PROFILE_ID)
        assertEquals(AppSettings(), settings.settings.first())

        settings.setActiveProfileId(PROFILE_ID)
        val customised = settings.settings.first()
        assertFalse(customised.player.autoPlayTopStream)
        assertFalse(customised.player.hdrWhiteDimming)
        assertFalse(customised.subtitles.cleanSdh)
        assertFalse(customised.subtitles.style.bold)
    }

    private companion object {
        const val PROFILE_ID = 1L
        const val OTHER_PROFILE_ID = 2L
    }
}
