package com.seastreams.tv.domain.playback

import com.seastreams.tv.core.database.entity.LastUsedStreamEntity
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.FakeLastUsedStreamDao
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock

/** Details `Clear progress` (PRD §6): forgets only the movie's `watch_progress` row. */
class PlaybackProgressRecorderTest {

    private val progressDao = FakeWatchProgressDao()
    private val lastUsedDao = FakeLastUsedStreamDao()
    private val recorder = PlaybackProgressRecorder(progressDao, Clock.systemUTC())
    private val lastUsed = LastUsedStreamRecorder(lastUsedDao, Clock.systemUTC())
    private val movie = testDetails(7)

    private fun progress(profileId: Long, tmdbId: Int = 7, type: MediaType = MediaType.MOVIE, season: Int = 0, episode: Int = 0) =
        WatchProgressEntity(
            profileId = profileId, tmdbId = tmdbId, mediaType = type, seasonNumber = season, episodeNumber = episode,
            title = "Title $tmdbId", episodeTitle = null, posterPath = null, backdropPath = null,
            positionMs = 40_000, durationMs = 100_000, updatedAtMillis = 1,
        )

    @Test
    fun `clearing a movie's progress keeps its LAST USED stream`() = runTest {
        progressDao.upsert(progress(1))
        lastUsedDao.upsert(
            LastUsedStreamEntity(
                profileId = 1, tmdbId = 7, mediaType = MediaType.MOVIE, addonUrl = "https://addon",
                streamUrlFingerprint = "fp", label = "1080p", description = "desc", updatedAtMillis = 1,
            ),
        )

        recorder.clearMovieProgress(1, movie.ref)

        assertEquals(emptyList<WatchProgressEntity>(), progressDao.rows.value)
        val target = PlaybackTarget(1, movie, "tt0000007", episode = null)
        assertEquals(0, recorder.resumePositionMs(target))
        assertEquals("fp", lastUsed.lastUsed(target)?.urlFingerprint)
    }

    @Test
    fun `only the active profile's movie record is cleared`() = runTest {
        progressDao.upsert(progress(1))
        progressDao.upsert(progress(2))
        progressDao.upsert(progress(1, tmdbId = 8))

        recorder.clearMovieProgress(1, movie.ref)

        assertEquals(listOf(2L to 7, 1L to 8), progressDao.rows.value.map { it.profileId to it.tmdbId })
    }

    @Test
    fun `a show reference is a no-op`() = runTest {
        progressDao.upsert(progress(1, type = MediaType.TV, season = 1, episode = 1))

        recorder.clearMovieProgress(1, testDetails(7, MediaType.TV).ref)

        assertEquals(1, progressDao.rows.value.size)
    }
}
