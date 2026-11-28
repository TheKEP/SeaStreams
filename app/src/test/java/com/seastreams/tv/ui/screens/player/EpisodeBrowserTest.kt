package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.SeasonInfo
import io.mockk.mockk
import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The in-player Episodes overlay follows the playing episode after a (shuffled) episode change. */
class EpisodeBrowserTest {

    private val loaded = EpisodeBrowser(season = 1, episodes = listOf(mockk<Episode>()), playing = EpisodeRef(1, 2))

    @Test
    fun `an episode in the loaded season keeps the loaded list`() {
        val next = loaded.following(EpisodeRef(1, 3))

        assertEquals(EpisodeRef(1, 3), next.playing)
        assertEquals(1, next.season)
        assertSame(loaded.episodes, next.episodes)
    }

    @Test
    fun `a jump to another season switches the overlay to that season`() {
        val next = loaded.following(EpisodeRef(4, 7))

        assertEquals(EpisodeRef(4, 7), next.playing)
        assertEquals(4, next.season)
        assertEquals(emptyList<Episode>(), next.episodes)
    }

    private fun episode(season: Int, number: Int) = Episode(EpisodeRef(season, number), "E$number", "", null, null, null)

    private val season2 = EpisodeBrowser(
        seasons = listOf(SeasonInfo(1, "Season 1", 8), SeasonInfo(2, "Season 2", 8), SeasonInfo(3, "Season 3", 8)),
        season = 2,
        episodes = (1..8).map { episode(2, it) },
        playing = EpisodeRef(2, 5),
    )

    @Test
    fun `the overlay reopens on the playing season even after browsing another one`() {
        val browsedAway = season2.copy(season = 3, episodes = (1..8).map { episode(3, it) })

        assertEquals(2, browsedAway.openSeason(fallback = 1))
    }

    @Test
    fun `without a playing episode the overlay opens on the fallback season`() {
        assertEquals(4, EpisodeBrowser().openSeason(fallback = 4))
        assertNull(EpisodeBrowser().openSeason(fallback = null))
    }

    @Test
    fun `focus lands on the playing episode once its season has loaded`() {
        assertEquals(4, episodeFocusIndex(season2))
    }

    @Test
    fun `no episode focus while the season is loading or empty`() {
        assertNull(episodeFocusIndex(season2.copy(loading = true)))
        assertNull(episodeFocusIndex(season2.copy(episodes = emptyList())))
    }

    @Test
    fun `another season or an unlisted playing episode focuses the first episode`() {
        assertEquals(0, episodeFocusIndex(season2.copy(playing = EpisodeRef(1, 5))))
        assertEquals(0, episodeFocusIndex(season2.copy(playing = EpisodeRef(2, 42))))
        assertEquals(0, episodeFocusIndex(season2.copy(playing = null)))
    }

    @Test
    fun `season Up wraps to the listed season's last episode`() {
        assertEquals(7, lastEpisodeIndex(season2))
    }

    @Test
    fun `season Up has no wrap target while loading or empty`() {
        assertNull(lastEpisodeIndex(season2.copy(loading = true)))
        assertNull(lastEpisodeIndex(season2.copy(episodes = emptyList())))
    }

    @Test
    fun `held Up stops on episode 1 and a fresh Up passes on to the season row`() {
        assertTrue(holdsFirstEpisodeUp(repeatCount = 1))
        assertTrue(holdsFirstEpisodeUp(repeatCount = 12))
        assertFalse(holdsFirstEpisodeUp(repeatCount = 0))
    }

    @Test
    fun `every season pill owns Up and the row ends own Left and Right`() {
        assertEquals(setOf(Key.DirectionUp, Key.DirectionLeft), seasonPillEdgeKeys(0, 2))
        assertEquals(setOf(Key.DirectionUp), seasonPillEdgeKeys(1, 2))
        assertEquals(setOf(Key.DirectionUp, Key.DirectionRight), seasonPillEdgeKeys(2, 2))
        assertEquals(setOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight), seasonPillEdgeKeys(0, 0))
        assertEquals(emptySet<Key>(), seasonPillEdgeKeys(3, 2))
        assertEquals(emptySet<Key>(), seasonPillEdgeKeys(0, -1))
    }

    @Test
    fun `the season pill index follows the listed season`() {
        assertEquals(1, seasonPillIndex(season2))
        assertEquals(-1, seasonPillIndex(season2.copy(season = 9)))
    }
}
