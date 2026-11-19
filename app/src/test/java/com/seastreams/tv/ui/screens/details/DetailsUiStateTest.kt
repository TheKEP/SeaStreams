package com.seastreams.tv.ui.screens.details

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.playback.ProgressPoint
import com.seastreams.tv.testutil.testDetails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The resume line under the synopsis is always reserved, and the action stack draws only once final (PRD §6). */
class DetailsUiStateTest {

    private fun state(type: MediaType, vararg progress: ProgressPoint): DetailsUiState {
        val seasons = if (type == MediaType.TV) listOf(SeasonInfo(1, "Season 1", 10)) else emptyList()
        val details = testDetails(7, type, seasons = seasons)
        return DetailsUiState(media = details.media, content = DetailsContent.Loaded(details), progress = progress.toList())
    }

    /** 100-minute runtime, [watchedMinutes] in. */
    private fun point(watchedMinutes: Long, episode: EpisodeRef? = null) =
        ProgressPoint(episode, watchedMinutes * MINUTE, durationMs = 100 * MINUTE, updatedAtMillis = 1)

    @Test
    fun `a part-watched movie shows its time left`() {
        val slot = state(MediaType.MOVIE, point(12)).resumeNoteSlot
        assertTrue(slot.endsWith(" left"), "slot=$slot")
    }

    @Test
    fun `unwatched and completed movies keep a blank line reserved`() {
        assertEquals("", state(MediaType.MOVIE).resumeNoteSlot)
        assertEquals("", state(MediaType.MOVIE, point(95)).resumeNoteSlot)
    }

    @Test
    fun `clearing a movie's progress blanks the line instead of removing it`() {
        val before = state(MediaType.MOVIE, point(12))
        assertTrue(before.resumeNoteSlot.isNotEmpty())
        assertEquals("", before.copy(progress = emptyList()).resumeNoteSlot)
    }

    @Test
    fun `shows also keep the line reserved and fill it while the next-up episode is part-watched`() {
        assertEquals("", state(MediaType.TV).resumeNoteSlot)
        val inProgress = state(MediaType.TV, point(12, EpisodeRef(1, 5))).resumeNoteSlot
        assertTrue(inProgress.endsWith(" left"), "slot=$inProgress")
    }

    @Test
    fun `nothing is ready before progress has loaded`() {
        assertFalse(DetailsUiState().actionsReady)
        assertFalse(state(MediaType.MOVIE).actionsReady)
        assertFalse(state(MediaType.TV).actionsReady)
    }

    @Test
    fun `a movie is ready on progress alone, a show also needs its seasons`() {
        val movie = testDetails(7)
        val show = testDetails(9, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 10)))
        assertTrue(DetailsUiState(media = movie.media, progressLoaded = true).actionsReady)
        assertFalse(DetailsUiState(media = show.media, progressLoaded = true).actionsReady)
        assertTrue(state(MediaType.TV).copy(progressLoaded = true).actionsReady)
    }

    @Test
    fun `error stacks are always ready`() {
        assertTrue(DetailsUiState(content = DetailsContent.Failed).actionsReady)
        assertTrue(DetailsUiState(content = DetailsContent.Restricted).actionsReady)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
