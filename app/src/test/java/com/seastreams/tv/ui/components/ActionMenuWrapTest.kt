package com.seastreams.tv.ui.components

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.ui.screens.details.AUTO_PLAY
import com.seastreams.tv.ui.screens.details.MARK_PREVIOUS_UNWATCHED
import com.seastreams.tv.ui.screens.details.MARK_SEASON_WATCHED
import com.seastreams.tv.ui.screens.details.SHUFFLE_SEASON
import com.seastreams.tv.ui.screens.details.VIEW_STREAMS
import com.seastreams.tv.ui.screens.details.episodeMenu
import com.seastreams.tv.ui.screens.details.seasonMenu
import com.seastreams.tv.ui.theme.EdgeKeyResult
import com.seastreams.tv.ui.theme.edgeKeyResult
import com.seastreams.tv.ui.theme.verticalEdgeKeys
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The long-press [ActionMenu] (Episodes season / episode menus, Continue Watching) follows the two-way §1
 * edge-stop-then-wrap rule: [ActionMenu] puts `verticalListEdgeWrap(index, actions.lastIndex, …)` on every entry, so
 * the first action owns `Up` (wrap to the last), the last owns `Down` (wrap to the first), and a held key stops there.
 */
class ActionMenuWrapTest {

    private val season = seasonMenu(SeasonInfo(2, "Season 2", 10), FocusRequester(), onMark = {}, onShuffle = {})

    private val episode = episodeMenu(
        episode = mockk<Episode> {
            every { title } returns "Pilot"
            every { ref } returns EpisodeRef(1, 1)
        },
        watched = false,
        returnFocus = FocusRequester(),
        onViewStreams = {},
        onAutoPlay = {},
        onToggleWatched = {},
        onMarkPrevious = {},
    )

    /** What one D-pad event does on entry [index] of [menu]: wrap, stop on the edge, or move normally (PASS). */
    private fun press(menu: ActionMenuRequest, index: Int, key: Key, repeatCount: Int = 0): EdgeKeyResult =
        edgeKeyResult(key in verticalEdgeKeys(index, menu.actions.lastIndex), isKeyDown = true, repeatCount = repeatCount)

    @Test
    fun `season menu wraps both ways on a fresh press and stops while held`() {
        assertEquals(listOf(MARK_SEASON_WATCHED, SHUFFLE_SEASON), listOf(season.actions.first().label, season.actions.last().label))
        val last = season.actions.lastIndex
        assertEquals(EdgeKeyResult.WRAP, press(season, 0, Key.DirectionUp))
        assertEquals(EdgeKeyResult.WRAP, press(season, last, Key.DirectionDown))
        assertEquals(EdgeKeyResult.HOLD, press(season, 0, Key.DirectionUp, repeatCount = 3))
        assertEquals(EdgeKeyResult.HOLD, press(season, last, Key.DirectionDown, repeatCount = 1))
        // Inward moves and the middle entry are left to normal focus search.
        assertEquals(EdgeKeyResult.PASS, press(season, 0, Key.DirectionDown))
        assertEquals(EdgeKeyResult.PASS, press(season, last, Key.DirectionUp))
        assertEquals(EdgeKeyResult.PASS, press(season, 1, Key.DirectionUp))
        assertEquals(EdgeKeyResult.PASS, press(season, 1, Key.DirectionDown))
    }

    @Test
    fun `episode menu wraps both ways on a fresh press and stops while held`() {
        assertEquals(VIEW_STREAMS, episode.actions.first().label)
        assertEquals(AUTO_PLAY, episode.actions[1].label)
        assertEquals(MARK_PREVIOUS_UNWATCHED, episode.actions.last().label)
        val last = episode.actions.lastIndex
        assertEquals(4, last)
        assertEquals(EdgeKeyResult.WRAP, press(episode, 0, Key.DirectionUp))
        assertEquals(EdgeKeyResult.WRAP, press(episode, last, Key.DirectionDown))
        assertEquals(EdgeKeyResult.HOLD, press(episode, 0, Key.DirectionUp, repeatCount = 5))
        assertEquals(EdgeKeyResult.HOLD, press(episode, last, Key.DirectionDown, repeatCount = 5))
        (1 until last).forEach { middle ->
            assertEquals(EdgeKeyResult.PASS, press(episode, middle, Key.DirectionUp))
            assertEquals(EdgeKeyResult.PASS, press(episode, middle, Key.DirectionDown))
        }
    }

    @Test
    fun `a key-up never wraps and a single-action menu owns both edges`() {
        assertEquals(EdgeKeyResult.PASS, edgeKeyResult(matchesKey = true, isKeyDown = false, repeatCount = 0))
        val single = ActionMenuRequest("Only", null, listOf(MenuAction("One") {}), FocusRequester())
        assertEquals(setOf(Key.DirectionUp, Key.DirectionDown), verticalEdgeKeys(0, single.actions.lastIndex))
        assertEquals(EdgeKeyResult.WRAP, press(single, 0, Key.DirectionUp))
        assertEquals(EdgeKeyResult.HOLD, press(single, 0, Key.DirectionDown, repeatCount = 2))
    }
}
