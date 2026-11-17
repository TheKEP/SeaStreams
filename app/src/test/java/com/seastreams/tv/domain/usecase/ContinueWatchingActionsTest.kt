package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.playback.PlaybackOutcome
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ProgressSource
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.testutil.testMedia
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ContinueWatchingActionsTest {

    private val dao = FakeWatchProgressDao()
    private val actions = ContinueWatchingActions(dao)
    private val recorder = PlaybackProgressRecorder(dao, Clock.fixed(Instant.ofEpochMilli(LATER), ZoneOffset.UTC))

    private fun row(tmdbId: Int, type: MediaType, season: Int, episode: Int, position: Long, updated: Long, profile: Long = PROFILE) =
        WatchProgressEntity(
            profileId = profile,
            tmdbId = tmdbId,
            mediaType = type,
            seasonNumber = season,
            episodeNumber = episode,
            title = "Title $tmdbId",
            episodeTitle = null,
            posterPath = null,
            backdropPath = null,
            positionMs = position,
            durationMs = DURATION,
            updatedAtMillis = updated,
        )

    private fun item(tmdbId: Int, type: MediaType, season: Int = 0, episode: Int = 0) =
        ContinueWatchingItem(testMedia(tmdbId, type), season, episode, progress = 0.5f)

    private suspend fun continueWatching() =
        dao.observeContinueWatching(PROFILE).first().map { Triple(it.tmdbId, it.seasonNumber, it.episodeNumber) }

    private fun saved(tmdbId: Int, season: Int = 0, episode: Int = 0, profile: Long = PROFILE) = dao.rows.value.firstOrNull {
        it.profileId == profile && it.tmdbId == tmdbId && it.seasonNumber == season && it.episodeNumber == episode
    }

    private fun reported(position: Long) =
        PlaybackOutcome(positionMs = position, durationMs = DURATION, completed = false, source = ProgressSource.REPORTED)

    @Test
    fun `remove hides a movie but keeps its position, duration and watched state`() = runTest {
        dao.rows.value = listOf(row(MOVIE, MediaType.MOVIE, 0, 0, 400, 2), row(OTHER_MOVIE, MediaType.MOVIE, 0, 0, 300, 1))
        val before = saved(MOVIE)

        actions.remove(PROFILE, item(MOVIE, MediaType.MOVIE))

        assertEquals(listOf(Triple(OTHER_MOVIE, 0, 0)), continueWatching())
        assertEquals(before?.copy(dismissedFromContinueWatching = true), saved(MOVIE))
    }

    @Test
    fun `a removed movie keeps its resume point and reappears once played again`() = runTest {
        dao.rows.value = listOf(row(MOVIE, MediaType.MOVIE, 0, 0, 400, 2))
        actions.remove(PROFILE, item(MOVIE, MediaType.MOVIE))
        assertTrue(continueWatching().isEmpty())

        val target = PlaybackTarget(PROFILE, testDetails(MOVIE), "tt1", episode = null)
        assertEquals(400L, recorder.resumePositionMs(target))
        recorder.record(target, reported(500))

        assertEquals(listOf(Triple(MOVIE, 0, 0)), continueWatching())
        assertFalse(saved(MOVIE)?.dismissedFromContinueWatching ?: true)
    }

    @Test
    fun `a removed episode reappears when that episode is played again`() = runTest {
        dao.rows.value = listOf(row(SHOW, MediaType.TV, 1, 3, 400, 2), row(SHOW, MediaType.TV, 1, 2, DURATION, 1))
        actions.remove(PROFILE, item(SHOW, MediaType.TV, 1, 3))
        assertTrue(continueWatching().isEmpty())
        assertTrue(saved(SHOW, 1, 3)?.dismissedFromContinueWatching ?: false)
        assertFalse(saved(SHOW, 1, 2)?.dismissedFromContinueWatching ?: true, "only the focused episode is flagged")

        val show = testDetails(SHOW, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 6)))
        recorder.record(PlaybackTarget(PROFILE, show, "tt2", EpisodeRef(1, 3)), reported(450))

        assertEquals(listOf(Triple(SHOW, 1, 3)), continueWatching())
    }

    @Test
    fun `clear progress on a movie deletes only that movie`() = runTest {
        dao.rows.value = listOf(
            row(MOVIE, MediaType.MOVIE, 0, 0, 400, 2),
            row(OTHER_MOVIE, MediaType.MOVIE, 0, 0, 300, 1),
            row(MOVIE, MediaType.MOVIE, 0, 0, 200, 1, profile = OTHER_PROFILE),
        )

        actions.clearProgress(PROFILE, item(MOVIE, MediaType.MOVIE))

        assertNull(saved(MOVIE), "the movie is unwatched again")
        assertEquals(listOf(Triple(OTHER_MOVIE, 0, 0)), continueWatching())
        assertEquals(200L, saved(MOVIE, profile = OTHER_PROFILE)?.positionMs, "other profiles keep theirs")
    }

    @Test
    fun `clear progress on an episode clears that single episode and no other`() = runTest {
        val others = listOf(
            row(SHOW, MediaType.TV, 1, 2, DURATION, 1),
            row(SHOW, MediaType.TV, 1, 4, 100, 0),
            row(SHOW, MediaType.TV, 2, 3, DURATION, 0),
        )
        dao.rows.value = others + row(SHOW, MediaType.TV, 1, 3, 400, 2)

        actions.clearProgress(PROFILE, item(SHOW, MediaType.TV, 1, 3))

        assertNull(saved(SHOW, 1, 3))
        assertEquals(others.toSet(), dao.rows.value.toSet())
    }

    private companion object {
        const val PROFILE = 1L
        const val OTHER_PROFILE = 2L
        const val MOVIE = 10
        const val OTHER_MOVIE = 11
        const val SHOW = 20
        const val DURATION = 1_000L
        const val LATER = 50L
    }
}
