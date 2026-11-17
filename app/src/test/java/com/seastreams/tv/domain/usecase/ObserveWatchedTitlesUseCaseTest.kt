package com.seastreams.tv.domain.usecase

import app.cash.turbine.test
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.FakeWatchProgressDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObserveWatchedTitlesUseCaseTest {

    private val dao = FakeWatchProgressDao()
    private val useCase = ObserveWatchedTitlesUseCase(dao)

    private fun row(tmdbId: Int, position: Long, profileId: Long = PROFILE, type: MediaType = MediaType.MOVIE, episode: Int = 0) =
        WatchProgressEntity(
            profileId = profileId, tmdbId = tmdbId, mediaType = type, seasonNumber = if (episode > 0) 1 else 0,
            episodeNumber = episode, title = "T$tmdbId", episodeTitle = null, posterPath = null, backdropPath = null,
            positionMs = position, durationMs = DURATION, updatedAtMillis = 1L,
        )

    @Test
    fun `only finished movies of the profile get the watched badge`() = runTest {
        dao.rows.value = listOf(
            row(1, position = DURATION), // Mark watched
            row(2, position = 900), // exactly 90%
            row(3, position = 899), // in progress: bar, no badge
            row(4, position = DURATION, profileId = OTHER_PROFILE),
            row(5, position = DURATION, type = MediaType.TV, episode = 1), // episodes badge on the Episodes screen
        )

        assertEquals(setOf(MediaRef(1, MediaType.MOVIE), MediaRef(2, MediaType.MOVIE)), useCase.observe(PROFILE).first())
    }

    @Test
    fun `badge appears once an in-progress movie crosses 90 percent`() = runTest {
        dao.rows.value = listOf(row(7, position = 400))
        useCase.observe(PROFILE).test {
            assertEquals(emptySet<MediaRef>(), awaitItem())
            dao.rows.update { listOf(row(7, position = 950)) }
            assertEquals(setOf(MediaRef(7, MediaType.MOVIE)), awaitItem())
        }
    }

    private companion object {
        const val PROFILE = 1L
        const val OTHER_PROFILE = 2L
        const val DURATION = 1_000L
    }
}
