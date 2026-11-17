package com.seastreams.tv.domain.playback

import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PlayerProgressTrackerTest {

    private val dao = FakeWatchProgressDao()
    private val tracker = PlayerProgressTracker(PlaybackProgressRecorder(dao, Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC)))
    private val movie = PlaybackTarget(1, testDetails(42, runtimeMinutes = 100), "tt42", episode = null)
    private val trailer = PlaybackRequest.Trailer(3, "Dune", TrailerStream(TrailerSource("https://v"), TrailerSource("https://a"), 2160))

    private fun media(start: Long = 0) = PlaybackRequest.Media(1, movie, emptyList(), 0, emptyList(), start)

    @Test
    fun `full-screen trailers never write watch progress`() = runTest {
        tracker.save(trailer, positionMs = 90_000, durationMs = 120_000)
        tracker.saveFinished(trailer, durationMs = 120_000)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `media playback saves the resume position`() = runTest {
        tracker.save(media(), positionMs = 600_000, durationMs = 6_000_000)
        val row = dao.rows.value.single()
        assertEquals(600_000, row.positionMs)
        assertEquals(6_000_000, row.durationMs)
    }

    @Test
    fun `an accidental fresh start isn't saved, a resumed one is`() = runTest {
        tracker.save(media(start = 0), positionMs = 4_000, durationMs = 6_000_000)
        assertTrue(dao.rows.value.isEmpty())

        tracker.save(media(start = 300_000), positionMs = 304_000, durationMs = 6_000_000)
        assertEquals(304_000, dao.rows.value.single().positionMs)
    }

    @Test
    fun `reaching the end marks it watched`() = runTest {
        tracker.saveFinished(media(), durationMs = 6_000_000)
        val row = dao.rows.value.single()
        assertTrue(row.positionMs >= row.durationMs * WatchProgressDao.COMPLETION_RATIO)
    }

    @Test
    fun `an unknown player duration falls back to TMDB runtime`() = runTest {
        tracker.save(media(), positionMs = 60_000, durationMs = 0)
        assertEquals(100 * 60_000L, dao.rows.value.single().durationMs)
    }
}
