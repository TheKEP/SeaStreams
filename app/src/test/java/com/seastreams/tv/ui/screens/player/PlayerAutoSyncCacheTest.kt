package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.player.subtitle.ExternalSubtitleLoader
import com.seastreams.tv.player.subtitle.ExternalSubtitleTimeline
import com.seastreams.tv.player.subtitle.TimedCue
import com.seastreams.tv.player.subtitle.sync.AutoSyncStatus
import com.seastreams.tv.player.subtitle.sync.SyncRate
import com.seastreams.tv.player.subtitle.sync.SyncSource
import com.seastreams.tv.player.subtitle.sync.syncInfoText
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Remembered sync results per stream + track, the file clock rate, and a manual nudge winning over both. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerAutoSyncCacheTest {

    private val english = SubtitleOption("https://subs/en.srt", "English · OpenSubtitles", "eng", SubtitleSource.EXTERNAL)
    private val spanish = SubtitleOption("https://subs/es.srt", "Spanish · OpenSubtitles", "spa", SubtitleSource.EXTERNAL)
    private val streams = listOf(StreamSource("a", "Addon", "https://cdn/1", "S1", "", emptyMap()), StreamSource("a", "Addon", "https://cdn/2", "S2", "", emptyMap()))
    private val base = PlayerUiState(streams = streams, streamIndex = 0, subtitleOptions = listOf(english, spanish))

    private val tracking = AutoSyncStatus.Locked(SyncSource.AUDIO, refining = false, tracking = true, rate = SyncRate.PAL_TO_FILM)

    /** The track picked again: what `selectSubtitle` resets before `startAutoSync` consults the cache. */
    private fun PlayerUiState.picked(option: SubtitleOption) =
        copy(selectedSubtitleId = option.id, subtitleOffsetMs = 0, subtitleRate = SyncRate.UNIT, subtitleSyncStatus = AutoSyncStatus.Off)

    @Test
    fun `an auto result is remembered per stream and track`() {
        val session = AutoSyncSession()
        val synced = base.picked(english).withDetectedOffset(1_300, SyncRate.PAL_TO_FILM).copy(subtitleSyncStatus = tracking)
        session.rememberAuto(synced, SyncSource.AUDIO)

        assertEquals(CachedSync(1_300, SyncRate.PAL_TO_FILM, SyncSource.AUDIO, manual = false), session.cached(base.picked(english)))
        assertNull(session.cached(base.picked(spanish)), "another track")
        assertNull(session.cached(base.picked(english).copy(streamIndex = 1)), "another stream may be timed differently")
        assertNull(session.cached(base), "no external track")
        session.clear()
        assertNull(session.cached(base.picked(english)), "forgotten with the session")
    }

    @Test
    fun `a nudge on a PAL-fixed track is what comes back when the track is picked again`() {
        val session = AutoSyncSession()
        val locked = base.picked(english).withDetectedOffset(1_300, SyncRate.PAL_TO_FILM).copy(subtitleSyncStatus = tracking)
        session.rememberAuto(locked, SyncSource.AUDIO)

        // HUD `+` x3: detection stops, the nudge steps from the auto value and keeps the rate.
        session.manualOverride = true
        var nudged = locked
        repeat(3) {
            nudged = nudged.withManualStep(+1)
            session.rememberManual(nudged)
        }
        assertEquals(1_600L, nudged.subtitleOffsetMs)
        assertEquals(SyncRate.PAL_TO_FILM, nudged.subtitleRate)

        // A late auto result (e.g. an in-flight status) can't overwrite the user's value...
        session.rememberAuto(locked, SyncSource.AUDIO)
        session.manualOverride = false // ...not even after switching to another track and back.
        session.rememberAuto(locked, SyncSource.AUDIO)

        val cached = session.cached(base.picked(english))
        assertEquals(CachedSync(1_600, SyncRate.PAL_TO_FILM, SyncSource.AUDIO, manual = true), cached)
        val restored = base.picked(english).withManualSync(requireNotNull(cached))
        assertEquals(1_600L, restored.subtitleOffsetMs, "the user's offset, not the pre-nudge 1.3 s")
        assertEquals(SyncRate.PAL_TO_FILM, restored.subtitleRate)
        assertEquals(AutoSyncStatus.Manual, restored.subtitleSyncStatus)
        assertFalse(restored.subtitleOffsetAuto)
        assertEquals("+1.6s · Manual · 25→23.976 fps", syncInfoText(restored.subtitleOffsetMs, restored.subtitleSyncStatus, restored.subtitleRate))
    }

    @Test
    fun `a nudge before any lock is remembered too`() {
        val session = AutoSyncSession()
        val nudged = base.picked(english).withManualStep(-1)
        session.rememberManual(nudged)
        assertEquals(CachedSync(-100, SyncRate.UNIT, null, manual = true), session.cached(base.picked(english)))
    }

    @Test
    fun `Info names the file's clock and an other-language reference`() {
        assertEquals("+0.4s · Auto (audio) · 25→23.976 fps · tracking", syncInfoText(400, tracking, SyncRate.PAL_TO_FILM))
        val foreign = AutoSyncStatus.Locked(SyncSource.EMBEDDED, refining = true, referenceLanguage = "fra")
        assertEquals("+2.3s · Auto (embedded subtitles, fra) · refining", syncInfoText(2_300, foreign))
    }

    @Test
    fun `the rate maps the playhead onto the file's own clock`() = runTest {
        // A line at 1000 s on a 25 fps file's clock plays at ~1042.7 s on a 23.976 fps video.
        val timeline = ExternalSubtitleTimeline(listOf(TimedCue(1_000_000_000, 1_002_000_000, "Hello")))
        val loader = mockk<ExternalSubtitleLoader> { coEvery { load(any()) } returns timeline }
        val subtitles = PlayerSubtitles(this) { loader }
        subtitles.activate(english, onLoaded = {}) { error("load failed") }
        advanceUntilIdle()

        assertNull(subtitles.textAt(1_000_500, 0, externalActive = true, cues = emptyList(), rate = SyncRate.PAL_TO_FILM))
        assertEquals("Hello", subtitles.textAt(1_043_000, 0, externalActive = true, cues = emptyList(), rate = SyncRate.PAL_TO_FILM))
        assertEquals("Hello", subtitles.textAt(1_042_000, 1_000, externalActive = true, cues = emptyList(), rate = SyncRate.PAL_TO_FILM))
        assertEquals("Hello", subtitles.textAt(1_000_500, 0, externalActive = true, cues = emptyList()))
    }
}
