package com.seastreams.tv.ui.screens.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Typography
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.player.subtitle.ExternalSubtitleLoader
import com.seastreams.tv.player.subtitle.ExternalSubtitleTimeline
import com.seastreams.tv.player.subtitle.SubtitleOffset
import com.seastreams.tv.player.subtitle.TimedCue
import com.seastreams.tv.player.subtitle.sync.AutoSyncStatus
import com.seastreams.tv.player.subtitle.sync.SyncSource
import com.seastreams.tv.player.subtitle.sync.hudCaption
import com.seastreams.tv.player.subtitle.sync.syncInfoText
import com.seastreams.tv.testutil.testDetails
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Auto-sync's effect on the HUD offset, the Info overlay and the live cue lookup (PRD §9). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerAutoSyncTest {

    private val external = SubtitleOption("https://subs/en.srt", "English · OpenSubtitles", "eng", SubtitleSource.EXTERNAL)
    private val embedded = SubtitleOption("0:2", "English", "eng", SubtitleSource.EMBEDDED)
    private val playing = PlayerUiState(subtitleOptions = listOf(embedded, external), selectedSubtitleId = external.id)

    private val locked = AutoSyncStatus.Locked(SyncSource.AUDIO, refining = false)

    @Test
    fun `a detected offset is snapped and clamped, and shows Auto once locked`() {
        assertEquals(1_300L, playing.withDetectedOffset(1_340).subtitleOffsetMs)
        assertEquals(-60_000L, playing.withDetectedOffset(-75_000).subtitleOffsetMs)
        val shown = playing.withDetectedOffset(1_300).copy(subtitleSyncStatus = locked)
        assertTrue(shown.subtitleSyncStatus is AutoSyncStatus.Locked)
        assertEquals("Auto", shown.subtitleSyncStatus.hudCaption())
    }

    @Test
    fun `the HUD caption tells scanning, locked and idle apart`() {
        assertEquals("Syncing…", AutoSyncStatus.Scanning(SyncSource.EMBEDDED, 12).hudCaption())
        assertEquals("Auto", AutoSyncStatus.Locked(SyncSource.EMBEDDED, refining = true).hudCaption())
        assertEquals("Auto", locked.hudCaption())
        listOf(AutoSyncStatus.Off, AutoSyncStatus.NoMatch, AutoSyncStatus.Manual).forEach { assertNull(it.hudCaption(), "$it") }
    }

    @Test
    fun `Info spells out what auto-sync is doing`() {
        assertEquals("Syncing… · embedded (0 cues), 20 s", syncInfoText(0, AutoSyncStatus.Scanning(SyncSource.EMBEDDED, 20)))
        assertEquals(
            "Syncing… · audio (18 cues, best +5.1s @ 0.42, 60% onsets), 25 s",
            syncInfoText(0, AutoSyncStatus.Scanning(SyncSource.AUDIO, 25, cues = 18, bestOffsetMs = 5_100, bestPeak = 0.42, onsetPercent = 60)),
        )
        assertEquals(
            "Syncing… · audio (1 cue), 20 s · embedded gave 2 cues",
            syncInfoText(0, AutoSyncStatus.Scanning(SyncSource.AUDIO, 20, cues = 1, embeddedCues = 2)),
        )
        assertEquals("+2.3s · Auto (embedded subtitles)", syncInfoText(2_300, AutoSyncStatus.Locked(SyncSource.EMBEDDED, refining = false)))
        assertEquals("+2.3s · Auto (audio) · refining", syncInfoText(2_300, AutoSyncStatus.Locked(SyncSource.AUDIO, refining = true)))
        assertEquals("+1.4s · Manual", syncInfoText(1_400, AutoSyncStatus.Manual))
        assertEquals("0.0s · no confident match", syncInfoText(0, AutoSyncStatus.NoMatch))
        assertEquals("0.0s", syncInfoText(0, AutoSyncStatus.Off))
    }

    @Test
    fun `a manual nudge steps from the auto value and marks the offset Manual`() {
        val nudged = playing.withDetectedOffset(1_300).copy(subtitleSyncStatus = locked).withManualStep(+1)
        assertEquals(1_400L, nudged.subtitleOffsetMs)
        assertEquals(AutoSyncStatus.Manual, nudged.subtitleSyncStatus)
        assertFalse(nudged.subtitleSyncStatus is AutoSyncStatus.Locked)
        assertEquals(1_300L, nudged.withManualStep(-1).subtitleOffsetMs)
    }

    @Test
    fun `with auto-sync off the control starts at 0_0s and steps manually`() {
        assertEquals(0L, playing.subtitleOffsetMs)
        assertFalse(playing.subtitleSyncStatus is AutoSyncStatus.Locked)
        assertEquals(-100L, playing.withManualStep(-1).subtitleOffsetMs)
        assertEquals(60_000L, playing.copy(subtitleOffsetMs = 60_000).withManualStep(+1).subtitleOffsetMs)
    }

    @Test
    fun `Info keeps describing the chosen external track while the reference track plays`() {
        // ExoPlayer may have the embedded `0:2` selected as auto-sync's reference; Info reads the session's choice.
        val rows = subtitleInfoRows(playing.withDetectedOffset(-2_100).copy(subtitleSyncStatus = locked)).associate { it.label to it.value }
        assertEquals("English · OpenSubtitles", rows["Subtitles"])
        assertEquals("English", rows["Subtitle language"])
        assertEquals("External", rows["Subtitle source"])
        assertEquals("-2.1s · Auto (audio)", rows["Subtitle sync"])

        val embeddedRows = subtitleInfoRows(playing.copy(selectedSubtitleId = embedded.id)).associate { it.label to it.value }
        assertEquals("Embedded", embeddedRows["Subtitle source"])
        assertNull(embeddedRows["Subtitle sync"])
    }

    @Test
    fun `the offset shifts external cue times before Clean SDH renders them`() = runTest {
        val timeline = ExternalSubtitleTimeline(listOf(TimedCue(10_000_000, 12_000_000, "[SIGHS] Later")))
        val loader = mockk<ExternalSubtitleLoader> { coEvery { load(any()) } returns timeline }
        val subtitles = PlayerSubtitles(this) { loader }
        var loaded: ExternalSubtitleTimeline? = null
        subtitles.activate(external, onLoaded = { loaded = it }) { error("load failed") }
        advanceUntilIdle()

        assertTrue(loaded === timeline)
        assertNull(subtitles.textAt(8_000, 0, externalActive = true, cues = emptyList()))
        // +2.3 s shows each cue 2.3 s earlier: at 8.0 s the 10.0–12.0 s cue is on screen.
        assertEquals("[SIGHS] Later", subtitles.textAt(8_000, 2_300, externalActive = true, cues = emptyList()))
        assertNull(subtitles.textAt(9_800, 2_300, externalActive = true, cues = emptyList()), "end shifted too")
        assertEquals("[SIGHS] Later", subtitles.textAt(12_500, -1_000, externalActive = true, cues = emptyList()))
        subtitles.renderer.cleanSdh = true
        assertEquals("Later", subtitles.textAt(8_000, 2_300, externalActive = true, cues = emptyList()))
    }

    @Test
    fun `the full episode bar with the sync caption fits a 960 dp wide TV`() {
        val show = testDetails(5, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 10)))
        val state = playing.withDetectedOffset(-12_300).copy(
            subtitleSyncStatus = AutoSyncStatus.Scanning(SyncSource.AUDIO, 5),
            request = PlaybackRequest.Media(2, PlaybackTarget(1, show, "tt5", EpisodeRef(1, 3)), emptyList(), 0, emptyList(), 0),
        )
        val controls = actionBarControls(state)
        assertEquals(9, controls.size, "every control is showing")

        // Upper bound for a bold 14 sp label: 0.7 em per character.
        val charWidth = 14.dp * 0.7f
        fun pill(label: String) = charWidth * label.length + PILL_PADDING * 2
        val sync = SYNC_BUTTON_SIZE * 2 + SYNC_SPACING * 2 + SYNC_LABEL_WIDTH
        val widths: List<Dp> = controls.map { control ->
            when (control) {
                BarControl.PLAY_PAUSE, BarControl.NEXT_EPISODE -> CIRCLE_SIZE
                BarControl.SUBTITLES -> pill("Subtitles")
                BarControl.SUBTITLE_SYNC -> sync
                BarControl.AUDIO -> pill("Audio")
                BarControl.SPEED -> pill("Speed")
                BarControl.STREAMS -> pill("Streams")
                BarControl.EPISODES -> pill("Episodes")
                BarControl.INFO -> pill("Info")
            }
        }
        val divider = 1.dp
        val total = widths.fold(divider) { sum, width -> sum + width } + BAR_SPACING * controls.size // n−1 gaps + divider's gap
        val available = 960.dp - 48.dp * 2 // HUD side padding
        assertTrue(total <= available, "bar needs $total of $available")

        // The widest value and the longer caption (`Syncing…`, 11 sp) still fit the fixed slot, stacked within the 48 dp row.
        assertTrue(charWidth * SubtitleOffset.label(-60_000).length <= SYNC_LABEL_WIDTH)
        assertTrue(11.dp * 0.7f * "Syncing…".length <= SYNC_LABEL_WIDTH)
        val typography = Typography()
        val stacked = typography.titleSmall.lineHeight.value + typography.labelSmall.lineHeight.value
        assertTrue(stacked <= CIRCLE_SIZE.value, "two-line sync label is $stacked dp tall")
    }

    @Test
    fun `right-hand panels stop above the action bar and push subtitles to their left`() {
        assertTrue(PANEL_BOTTOM_INSET >= HUD_BOTTOM_BLOCK, "panel inset $PANEL_BOTTOM_INSET vs HUD block $HUD_BOTTOM_BLOCK")
        listOf(PlayerPanel.INFO, PlayerPanel.SUBTITLES, PlayerPanel.AUDIO, PlayerPanel.EPISODES).forEach { panel ->
            assertTrue(cueEndPadding(panel) >= PanelWidth, "$panel")
        }
        assertEquals(CUE_SIDE_PADDING, cueEndPadding(null))
        // On a 960 dp TV the cue keeps a usable width beside the panel.
        assertTrue(960.dp - CUE_SIDE_PADDING - cueEndPadding(PlayerPanel.INFO) >= 300.dp)
    }
}
