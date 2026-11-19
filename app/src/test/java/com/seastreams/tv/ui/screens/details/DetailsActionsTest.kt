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

/**
 * The Details action stack (PRD §6). Its first button carries the `Up` wrap edge (to the last) and its
 * last button the `Down` wrap edge (to the first), so these keys are exactly what wrap targets.
 */
class DetailsActionsTest {

    private fun actions(state: DetailsUiState, onClearProgress: () -> Unit = {}) = detailsActions(
        state = state,
        onPlay = {},
        onTrailer = {},
        onMoreEpisodes = {},
        onShuffle = {},
        onToggleLibrary = {},
        onClearProgress = onClearProgress,
        onRetry = {},
        onBack = {},
    )

    private fun keys(state: DetailsUiState) = actions(state).map { it.key }

    private fun loaded(type: MediaType, inLibrary: Boolean = false, progress: List<ProgressPoint> = emptyList()): DetailsUiState {
        val seasons = if (type == MediaType.TV) listOf(SeasonInfo(1, "Season 1", 10)) else emptyList()
        val details = testDetails(7, type, seasons = seasons)
        return DetailsUiState(media = details.media, content = DetailsContent.Loaded(details), inLibrary = inLibrary, progress = progress)
    }

    private fun point(positionMs: Long, episode: EpisodeRef? = null) = ProgressPoint(episode, positionMs, durationMs = 100_000, updatedAtMillis = 1)

    private val halfWatched = listOf(point(40_000))

    @Test
    fun `movie stack runs Play to the library toggle`() {
        assertEquals(listOf("play", "trailer", "library"), keys(loaded(MediaType.MOVIE)))
    }

    @Test
    fun `show stack adds More episodes and Shuffle and still ends on the library toggle`() {
        assertEquals(listOf("play", "trailer", "episodes", "shuffle", "library"), keys(loaded(MediaType.TV)))
    }

    @Test
    fun `wrap edges are Play-Resume at the top and the library toggle at the bottom, saved or not`() {
        listOf(MediaType.MOVIE, MediaType.TV).forEach { type ->
            listOf(false, true).forEach { saved ->
                val stack = keys(loaded(type, inLibrary = saved))
                assertEquals("play", stack.first(), "$type saved=$saved")
                assertEquals("library", stack.last(), "$type saved=$saved")
            }
        }
    }

    @Test
    fun `library toggle label follows saved state`() {
        val actions = { saved: Boolean ->
            actions(loaded(MediaType.MOVIE, saved)).last().label
        }
        assertEquals(libraryButtonLabel(false), actions(false))
        assertEquals(libraryButtonLabel(true), actions(true))
    }

    @Test
    fun `failed stack wraps between Retry and Go back`() {
        assertEquals(listOf("retry", "back"), keys(DetailsUiState(content = DetailsContent.Failed)))
    }

    @Test
    fun `restricted stack is a single Go back, both wrap edges at once`() {
        val stack = keys(DetailsUiState(content = DetailsContent.Restricted))
        assertEquals(listOf("back"), stack)
        assertEquals(stack.first(), stack.last())
    }

    @Test
    fun `a movie in the Resume state ends on Clear progress, which owns the bottom wrap edge`() {
        val state = loaded(MediaType.MOVIE, progress = halfWatched)
        assertEquals("Resume", state.playLabel)
        assertEquals(listOf("play", "trailer", "library", "clear"), keys(state))
        assertEquals(CLEAR_PROGRESS_LABEL, actions(state).last().label)
    }

    @Test
    fun `unwatched and completed movies show Play and no Clear progress`() {
        listOf(emptyList(), listOf(point(95_000))).forEach { progress ->
            val state = loaded(MediaType.MOVIE, progress = progress)
            assertEquals("Play", state.playLabel)
            assertFalse(state.canClearProgress)
            assertEquals(listOf("play", "trailer", "library"), keys(state))
        }
    }

    @Test
    fun `a show with a part-watched episode never offers Clear progress`() {
        val state = loaded(MediaType.TV, progress = listOf(point(40_000, EpisodeRef(1, 1))))
        assertEquals("Resume S1:E1", state.playLabel)
        assertFalse(state.canClearProgress)
        assertEquals(listOf("play", "trailer", "episodes", "shuffle", "library"), keys(state))
    }

    @Test
    fun `only Clear progress moves focus to Play before running its click`() {
        var cleared = 0
        val stack = actions(loaded(MediaType.MOVIE, progress = halfWatched), onClearProgress = { cleared++ })
        assertEquals(listOf("clear"), stack.filter { it.focusFirstOnClick }.map { it.key })
        stack.single { it.key == "clear" }.onClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `clearing the progress turns Resume back into Play and drops the button`() {
        val before = loaded(MediaType.MOVIE, progress = halfWatched)
        assertTrue(before.canClearProgress)
        val after = before.copy(progress = emptyList())
        assertEquals("Play", after.playLabel)
        assertEquals("play", keys(after).first())
        assertEquals("library", keys(after).last())
        // Every button above Clear progress keeps its key (and so its node): nothing above it remounts.
        assertEquals(keys(before).dropLast(1), keys(after))
    }
}
