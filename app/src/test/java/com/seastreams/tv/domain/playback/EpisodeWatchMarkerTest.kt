package com.seastreams.tv.domain.playback

import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

class EpisodeWatchMarkerTest {

    private val dao = FakeWatchProgressDao()
    private val marker = EpisodeWatchMarker(dao, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC))
    private val show = testDetails(SHOW, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 6), SeasonInfo(2, "Season 2", 4)))
    private val season1 = (1..6).map { episode(1, it) }
    private val season2 = (1..4).map { episode(2, it) }

    /** S1: E1 watched, E2 half-way, E3 untouched, E4 watched, E5/E6 untouched. S2: E1 watched. */
    private fun seedMixed() {
        dao.rows.value = listOf(saved(1, 1, 1_000, 1_000), saved(1, 2, 500, 1_000), saved(1, 4, 950, 1_000), saved(2, 1, 1_000, 1_000))
    }

    private fun watched(): Set<EpisodeRef> = dao.rows.value.filter { isCompleted(it.positionMs, it.durationMs) }
        .map { EpisodeRef(it.seasonNumber, it.episodeNumber) }.toSet()

    private fun saved(): Set<EpisodeRef> = dao.rows.value.map { EpisodeRef(it.seasonNumber, it.episodeNumber) }.toSet()

    @Test
    fun `mark season watched finishes every episode of a mixed season and leaves other seasons alone`() = runTest {
        seedMixed()
        marker.markWatched(PROFILE, show, SeasonEpisodeSelection.season(season1 + season2, 1))

        assertEquals((1..6).map { EpisodeRef(1, it) }.toSet() + EpisodeRef(2, 1), watched())
        assertEquals(watched(), saved())
    }

    @Test
    fun `mark season unwatched clears every episode of a mixed season and leaves other seasons alone`() = runTest {
        seedMixed()
        marker.markUnwatched(PROFILE, show, SeasonEpisodeSelection.season(season1 + season2, 1).map { it.ref })

        assertEquals(setOf(EpisodeRef(2, 1)), saved())
    }

    @Test
    fun `mark season watched then unwatched round-trips to nothing saved`() = runTest {
        marker.markWatched(PROFILE, show, season2)
        assertEquals(season2.map { it.ref }.toSet(), watched())

        marker.markUnwatched(PROFILE, show, season2.map { it.ref })
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `marked episodes keep a known duration and the last one marked is the newest`() = runTest {
        dao.rows.value = listOf(saved(1, 2, 100, 2_400_000))
        val noRuntime = episode(1, 3).copy(runtimeMinutes = null)
        marker.markWatched(PROFILE, show, listOf(noRuntime, season1[1], season1[0]))

        val rows = dao.rows.value.associateBy { it.episodeNumber }
        assertEquals(RUNTIME_MINUTES * 60_000L, rows.getValue(1).durationMs)
        assertEquals(2_400_000L, rows.getValue(2).durationMs)
        assertTrue(rows.values.all { it.positionMs == it.durationMs && it.durationMs > 0 })
        assertEquals(3, rows.values.maxBy { it.updatedAtMillis }.episodeNumber)
        assertEquals("Episode 1-1", rows.getValue(1).episodeTitle)
    }

    @Test
    fun `toggling a single episode watched then unwatched`() = runTest {
        seedMixed()
        marker.markWatched(PROFILE, show, listOf(season1[2]))
        assertTrue(EpisodeRef(1, 3) in watched())

        marker.markUnwatched(PROFILE, show, listOf(EpisodeRef(1, 3)))
        assertTrue(EpisodeRef(1, 3) !in saved())
        // Its neighbours are untouched.
        assertEquals(setOf(EpisodeRef(1, 1), EpisodeRef(1, 2), EpisodeRef(1, 4), EpisodeRef(2, 1)), saved())
    }

    @Test
    fun `previous in season covers episode 1 only when episode 1 is selected`() {
        assertEquals(listOf(EpisodeRef(1, 1)), SeasonEpisodeSelection.throughEpisode(season1 + season2, EpisodeRef(1, 1)).map { it.ref })
    }

    @Test
    fun `previous in season stops at the selected episode and stays in its season`() {
        val picked = SeasonEpisodeSelection.throughEpisode(season1 + season2, EpisodeRef(2, 3)).map { it.ref }
        assertEquals((1..3).map { EpisodeRef(2, it) }, picked)
    }

    @Test
    fun `mark previous watched mid-season over a mixed range`() = runTest {
        seedMixed()
        marker.markWatched(PROFILE, show, SeasonEpisodeSelection.throughEpisode(season1, EpisodeRef(1, 3)))

        assertEquals(setOf(EpisodeRef(1, 1), EpisodeRef(1, 2), EpisodeRef(1, 3), EpisodeRef(1, 4), EpisodeRef(2, 1)), watched())
    }

    @Test
    fun `mark previous unwatched mid-season clears a mixed range and nothing after it`() = runTest {
        seedMixed()
        marker.markUnwatched(PROFILE, show, SeasonEpisodeSelection.throughEpisode(season1, EpisodeRef(1, 3)).map { it.ref })

        assertEquals(setOf(EpisodeRef(1, 4), EpisodeRef(2, 1)), saved())
    }

    @Test
    fun `mark previous from episode 1 touches only episode 1`() = runTest {
        marker.markWatched(PROFILE, show, SeasonEpisodeSelection.throughEpisode(season1, EpisodeRef(1, 1)))
        assertEquals(setOf(EpisodeRef(1, 1)), saved())

        seedMixed()
        marker.markUnwatched(PROFILE, show, SeasonEpisodeSelection.throughEpisode(season1, EpisodeRef(1, 1)).map { it.ref })
        assertEquals(setOf(EpisodeRef(1, 2), EpisodeRef(1, 4), EpisodeRef(2, 1)), saved())
    }

    @Test
    fun `shuffle season picks only from that season and reaches every aired episode`() {
        val random = Random(42)
        val picks = (1..500).map { SeasonEpisodeSelection.shuffle(season1 + season2, 2, lastAired = null, random = random) }

        assertTrue(picks.all { it != null && it.ref.season == 2 })
        assertEquals(season2.map { it.ref }.toSet(), picks.mapNotNull { it?.ref }.toSet())
    }

    @Test
    fun `shuffle season skips unaired episodes and returns null when none aired`() {
        val random = Random(7)
        val picks = (1..200).mapNotNull { SeasonEpisodeSelection.shuffle(season1 + season2, 2, EpisodeRef(2, 2), random)?.ref }

        assertEquals(setOf(EpisodeRef(2, 1), EpisodeRef(2, 2)), picks.toSet())
        assertNull(SeasonEpisodeSelection.shuffle(season1 + season2, 2, EpisodeRef(1, 6), random))
        assertNull(SeasonEpisodeSelection.shuffle(season1, 3, lastAired = null, random = random))
    }

    private fun episode(season: Int, number: Int) =
        Episode(EpisodeRef(season, number), "Episode $season-$number", "", null, RUNTIME_MINUTES, null)

    private fun saved(season: Int, episode: Int, position: Long, duration: Long) = WatchProgressEntity(
        profileId = PROFILE,
        tmdbId = SHOW,
        mediaType = MediaType.TV,
        seasonNumber = season,
        episodeNumber = episode,
        title = "Show",
        episodeTitle = null,
        posterPath = null,
        backdropPath = null,
        positionMs = position,
        durationMs = duration,
        updatedAtMillis = 1L,
    )

    private companion object {
        const val PROFILE = 1L
        const val SHOW = 99
        const val NOW = 1_000_000L
        const val RUNTIME_MINUTES = 45
    }
}
