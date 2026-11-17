package com.seastreams.tv.domain.playback

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.SeasonInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class EpisodeNavigatorTest {

    private val seasons = listOf(SeasonInfo(1, "Season 1", 3), SeasonInfo(2, "Season 2", 2))

    @Test
    fun `next episode continues within the season, then into the next season`() {
        assertEquals(EpisodeRef(1, 2), EpisodeNavigator.next(EpisodeRef(1, 1), seasons, null))
        assertEquals(EpisodeRef(2, 1), EpisodeNavigator.next(EpisodeRef(1, 3), seasons, null))
        assertNull(EpisodeNavigator.next(EpisodeRef(2, 2), seasons, null))
    }

    @Test
    fun `next episode stops at the last aired one`() {
        assertNull(EpisodeNavigator.next(EpisodeRef(1, 3), seasons, lastAired = EpisodeRef(1, 3)))
    }

    @Test
    fun `next up is the first episode when nothing was watched`() {
        assertEquals(EpisodeRef(1, 1), EpisodeNavigator.nextUp(emptyList(), seasons, null))
    }

    @Test
    fun `next up resumes an unfinished episode and advances past a finished one`() {
        val unfinished = ProgressPoint(EpisodeRef(1, 2), positionMs = 100, durationMs = 1_000, updatedAtMillis = 5)
        assertEquals(EpisodeRef(1, 2), EpisodeNavigator.nextUp(listOf(unfinished), seasons, null))

        val finished = ProgressPoint(EpisodeRef(1, 3), positionMs = 950, durationMs = 1_000, updatedAtMillis = 9)
        assertEquals(EpisodeRef(2, 1), EpisodeNavigator.nextUp(listOf(unfinished, finished), seasons, null))
    }

    @Test
    fun `shuffle only picks real, aired episodes across every season`() {
        val random = Random(1)
        val picks = List(200) { EpisodeNavigator.shuffle(seasons, lastAired = EpisodeRef(2, 1), random = random) }

        val all = setOf(EpisodeRef(1, 1), EpisodeRef(1, 2), EpisodeRef(1, 3), EpisodeRef(2, 1))
        assertTrue(picks.all { it in all })
        assertEquals(all, picks.toSet())
    }

    @Test
    fun `shuffle of a show without episodes picks nothing`() {
        assertNull(EpisodeNavigator.shuffle(emptyList(), null))
    }
}
