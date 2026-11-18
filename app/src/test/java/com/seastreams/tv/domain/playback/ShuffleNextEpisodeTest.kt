package com.seastreams.tv.domain.playback

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.testutil.testDetails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** PRD §6/§9: Next Episode and Auto-Play Next Episode follow the active shuffle session. */
class ShuffleNextEpisodeTest {

    private val seasons = listOf(SeasonInfo(1, "Season 1", 3), SeasonInfo(2, "Season 2", 4), SeasonInfo(3, "Season 3", 2))
    private val allAired = seasons.flatMap { s -> (1..s.episodeCount).map { EpisodeRef(s.number, it) } }.toSet()

    private fun pick(current: EpisodeRef, session: ShuffleSession, lastAired: EpisodeRef? = null, random: Random = Random(7)) =
        EpisodeNavigator.next(current, seasons, lastAired, session, random)

    @Test
    fun `show shuffle picks random aired episodes across every season and never the current one`() {
        val current = EpisodeRef(2, 2)
        val random = Random(42)
        val picks = List(500) { pick(current, ShuffleSession.start(ShuffleMode.Show, current), random = random)?.episode }

        picks.forEach { assertNotNull(it); assertNotEquals(current, it) }
        assertEquals(allAired - current, picks.filterNotNull().toSet(), "every other episode, in every season, is reachable")
    }

    @Test
    fun `show shuffle skips unaired episodes`() {
        val lastAired = EpisodeRef(2, 3)
        val random = Random(3)
        val picks = List(300) { pick(EpisodeRef(1, 1), ShuffleSession.start(ShuffleMode.Show, EpisodeRef(1, 1)), lastAired, random) }

        assertTrue(picks.all { it != null && it.episode <= lastAired })
    }

    @Test
    fun `show shuffle avoids repeats until every episode has played, then starts a new cycle`() {
        var current = EpisodeRef(1, 1)
        var session = ShuffleSession.start(ShuffleMode.Show, current)
        val random = Random(11)
        val firstCycle = mutableListOf(current)
        repeat(allAired.size - 1) {
            val next = checkNotNull(EpisodeNavigator.next(current, seasons, null, session, random))
            firstCycle += next.episode
            assertEquals(ShuffleMode.Show, next.session.mode, "the session stays a show shuffle")
            current = next.episode
            session = next.session
        }
        assertEquals(allAired, firstCycle.toSet(), "no repeats within one cycle")

        val next = checkNotNull(EpisodeNavigator.next(current, seasons, null, session, random))
        assertNotEquals(current, next.episode, "the new cycle still never replays the episode that just played")
        assertEquals(listOf(current, next.episode), next.session.played, "history restarts with the new cycle")
    }

    @Test
    fun `season shuffle never leaves the season and excludes the current episode`() {
        val current = EpisodeRef(2, 3)
        val random = Random(5)
        val picks = List(500) { pick(current, ShuffleSession.start(ShuffleMode.Season(2), current), random = random) }

        picks.forEach { next ->
            assertNotNull(next)
            assertEquals(2, next?.episode?.season)
            assertNotEquals(current, next?.episode)
            assertEquals(ShuffleMode.Season(2), next?.session?.mode)
        }
        assertEquals(setOf(EpisodeRef(2, 1), EpisodeRef(2, 2), EpisodeRef(2, 4)), picks.mapNotNull { it?.episode }.toSet())
    }

    @Test
    fun `season shuffle stays in its season even when the current episode is from another one`() {
        val random = Random(9)
        val picks = List(100) { pick(EpisodeRef(1, 3), ShuffleSession(ShuffleMode.Season(3)), random = random)?.episode }

        assertTrue(picks.all { it?.season == 3 })
    }

    @Test
    fun `season shuffle skips unaired episodes of the season`() {
        val random = Random(1)
        val picks = List(200) { pick(EpisodeRef(2, 1), ShuffleSession(ShuffleMode.Season(2)), EpisodeRef(2, 3), random)?.episode }

        assertEquals(setOf(EpisodeRef(2, 2), EpisodeRef(2, 3)), picks.filterNotNull().toSet())
    }

    @Test
    fun `a pool holding only the current episode has nothing next`() {
        val oneEpisode = listOf(SeasonInfo(1, "Season 1", 1))
        val single = EpisodeRef(1, 1)
        assertNull(EpisodeNavigator.next(single, oneEpisode, null, ShuffleSession(ShuffleMode.Show)))
        assertNull(EpisodeNavigator.next(single, oneEpisode, null, ShuffleSession(ShuffleMode.Season(1))))
        assertNull(pick(EpisodeRef(2, 1), ShuffleSession(ShuffleMode.Season(2)), lastAired = EpisodeRef(2, 1)))
    }

    @Test
    fun `sequential sessions keep the existing in-order behaviour`() {
        val session = ShuffleSession.Sequential
        assertEquals(NextPick(EpisodeRef(1, 2), session), pick(EpisodeRef(1, 1), session))
        assertEquals(NextPick(EpisodeRef(2, 1), session), pick(EpisodeRef(1, 3), session))
        assertNull(pick(EpisodeRef(3, 2), session), "end of the show")
        assertNull(pick(EpisodeRef(1, 3), session, lastAired = EpisodeRef(1, 3)), "next one hasn't aired")
    }

    @Test
    fun `a manual Episodes overlay pick resets the session to sequential`() {
        val show = testDetails(5, MediaType.TV, seasons = seasons)
        val shuffled = PlaybackTarget(
            profileId = 1,
            details = show,
            imdbId = "tt5",
            episode = EpisodeRef(2, 2),
            episodeTitle = "Old",
            episodeRuntimeMinutes = 40,
            shuffle = ShuffleSession(ShuffleMode.Season(2), listOf(EpisodeRef(2, 2))),
        )

        val picked = shuffled.forEpisode(EpisodeRef(3, 1))

        assertEquals(EpisodeRef(3, 1), picked.episode)
        assertEquals(ShuffleSession.Sequential, picked.shuffle)
        assertNull(picked.episodeTitle)
        assertNull(picked.episodeRuntimeMinutes)
        assertEquals(NextPick(EpisodeRef(3, 2), ShuffleSession.Sequential), EpisodeNavigator.next(EpisodeRef(3, 1), seasons, null, picked.shuffle))
    }

    @Test
    fun `starting a session records the first episode, and None stays sequential`() {
        assertEquals(ShuffleSession(ShuffleMode.Show, listOf(EpisodeRef(1, 2))), ShuffleSession.start(ShuffleMode.Show, EpisodeRef(1, 2)))
        assertEquals(ShuffleSession.Sequential, ShuffleSession.start(ShuffleMode.None, EpisodeRef(1, 2)))
    }
}
