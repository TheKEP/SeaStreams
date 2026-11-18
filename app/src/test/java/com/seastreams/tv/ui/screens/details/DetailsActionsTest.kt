package com.seastreams.tv.ui.screens.details

import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.testutil.testDetails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The Details action stack (PRD §6). Its first button carries the `Up` wrap edge (to the last) and its
 * last button the `Down` wrap edge (to the first), so these keys are exactly what wrap targets.
 */
class DetailsActionsTest {

    private fun keys(state: DetailsUiState) = detailsActions(
        state = state,
        onPlay = {},
        onTrailer = {},
        onMoreEpisodes = {},
        onShuffle = {},
        onToggleLibrary = {},
        onRetry = {},
        onBack = {},
    ).map { it.key }

    private fun loaded(type: MediaType, inLibrary: Boolean = false): DetailsUiState {
        val seasons = if (type == MediaType.TV) listOf(SeasonInfo(1, "Season 1", 10)) else emptyList()
        val details = testDetails(7, type, seasons = seasons)
        return DetailsUiState(media = details.media, content = DetailsContent.Loaded(details), inLibrary = inLibrary)
    }

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
            detailsActions(loaded(MediaType.MOVIE, saved), {}, {}, {}, {}, {}, {}, {}).last().label
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
}
