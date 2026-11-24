package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.ui.screens.player.BarControl.AUDIO
import com.seastreams.tv.ui.screens.player.BarControl.EPISODES
import com.seastreams.tv.ui.screens.player.BarControl.INFO
import com.seastreams.tv.ui.screens.player.BarControl.NEXT_EPISODE
import com.seastreams.tv.ui.screens.player.BarControl.PLAY_PAUSE
import com.seastreams.tv.ui.screens.player.BarControl.SPEED
import com.seastreams.tv.ui.screens.player.BarControl.STREAMS
import com.seastreams.tv.ui.screens.player.BarControl.SUBTITLES
import com.seastreams.tv.ui.screens.player.BarControl.SUBTITLE_SYNC
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The bottom action bar's visible controls (PRD §9). Its first entry carries the `Left` wrap edge and
 * its last the `Right` wrap edge, so these lists are exactly what bidirectional wrap targets.
 */
class PlayerActionBarTest {

    private val show = testDetails(5, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 10)))
    private val movie = PlayerUiState(request = PlaybackRequest.Media(1, PlaybackTarget(1, testDetails(9), "tt9", episode = null), emptyList(), 0, emptyList(), 0))
    private val episode = PlayerUiState(request = PlaybackRequest.Media(2, PlaybackTarget(1, show, "tt5", EpisodeRef(1, 3)), emptyList(), 0, emptyList(), 0))
    private val trailer = PlayerUiState(request = PlaybackRequest.Trailer(3, "Trailer", TrailerStream(TrailerSource("https://v"), audio = null, height = 1080)))

    private fun PlayerUiState.withSubtitle(source: SubtitleSource) = copy(
        subtitleOptions = listOf(SubtitleOption("s1", "English", "en", source)),
        selectedSubtitleId = "s1",
    )

    @Test
    fun `movie bar runs Play-Pause to Info without episode controls or sync`() {
        assertEquals(listOf(PLAY_PAUSE, SUBTITLES, AUDIO, SPEED, STREAMS, INFO), actionBarControls(movie))
    }

    @Test
    fun `episode bar adds Next Episode and Episodes and still ends on Info`() {
        assertEquals(listOf(PLAY_PAUSE, NEXT_EPISODE, SUBTITLES, AUDIO, SPEED, STREAMS, EPISODES, INFO), actionBarControls(episode))
    }

    @Test
    fun `active external subtitle adds the sync offset right after Subtitles`() {
        assertEquals(
            listOf(PLAY_PAUSE, SUBTITLES, SUBTITLE_SYNC, AUDIO, SPEED, STREAMS, INFO),
            actionBarControls(movie.withSubtitle(SubtitleSource.EXTERNAL)),
        )
        assertEquals(
            listOf(PLAY_PAUSE, NEXT_EPISODE, SUBTITLES, SUBTITLE_SYNC, AUDIO, SPEED, STREAMS, EPISODES, INFO),
            actionBarControls(episode.withSubtitle(SubtitleSource.EXTERNAL)),
        )
    }

    @Test
    fun `embedded subtitle shows no sync offset`() {
        assertEquals(actionBarControls(movie), actionBarControls(movie.withSubtitle(SubtitleSource.EMBEDDED)))
    }

    @Test
    fun `trailer HUD is Play-Pause alone, so it is both wrap edges`() {
        val controls = actionBarControls(trailer)
        assertEquals(listOf(PLAY_PAUSE), controls)
        assertEquals(controls.first(), controls.last())
    }

    @Test
    fun `every configuration starts on Play-Pause, and media ends on Info`() {
        listOf(movie, episode, movie.withSubtitle(SubtitleSource.EXTERNAL), episode.withSubtitle(SubtitleSource.EXTERNAL))
            .forEach { state ->
                val controls = actionBarControls(state)
                assertEquals(PLAY_PAUSE, controls.first(), "$controls")
                assertEquals(INFO, controls.last(), "$controls")
            }
    }

    @Test
    fun `bar order matches PRD left-to-right order`() {
        val full = actionBarControls(episode.withSubtitle(SubtitleSource.EXTERNAL))
        assertEquals(BarControl.entries.toList(), full)
    }
}
