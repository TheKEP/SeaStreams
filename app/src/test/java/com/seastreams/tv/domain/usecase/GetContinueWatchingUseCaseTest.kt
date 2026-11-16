package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.testProfile
import com.seastreams.tv.testutil.tvDetails
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class GetContinueWatchingUseCaseTest {

    private val dao = mockk<WatchProgressDao>()
    private val tmdb = mockk<TmdbApiService>()
    private val images = TmdbImageUrls(ApiEndpoints())
    private val useCase = GetContinueWatchingUseCase(dao, MediaHydrator(Lazy { tmdb }, images), images, AgeRestrictionEngine())

    private val rows = listOf(
        progress(tmdbId = 1, type = MediaType.MOVIE),
        progress(tmdbId = 2, type = MediaType.MOVIE),
        progress(tmdbId = 3, type = MediaType.TV, season = 7, episode = 10),
        progress(tmdbId = 4, type = MediaType.MOVIE),
    )

    @BeforeEach
    fun setUp() {
        every { dao.observeContinueWatching(any(), any()) } returns flowOf(rows)
        coEvery { tmdb.getMovieDetails(1) } returns movieDetails(1, "G")
        coEvery { tmdb.getMovieDetails(2) } returns movieDetails(2, "R")
        coEvery { tmdb.getTvDetails(3) } returns tvDetails(3, "TV-Y")
        coEvery { tmdb.getMovieDetails(4) } throws IOException("offline")
    }

    @Test
    fun `kids profile only keeps titles confirmed within its ceiling`() = runTest {
        val items = useCase.observe(testProfile(2, isKids = true)).first()

        assertEquals(listOf(1, 3), items.map { it.media.tmdbId })
        val episode = items[1]
        assertEquals(7, episode.seasonNumber)
        assertEquals(10, episode.episodeNumber)
    }

    @Test
    fun `adult profile keeps everything, falling back to stored data offline`() = runTest {
        val items = useCase.observe(testProfile(1, isKids = false)).first()

        assertEquals(listOf(1, 2, 3, 4), items.map { it.media.tmdbId })
        val offline = items.last().media
        assertEquals("Stored 4", offline.title)
        assertEquals("https://image.tmdb.org/t/p/w342/stored4.jpg", offline.posterUrl)
        assertEquals(0.25f, items.first().progress)
    }

    private fun progress(tmdbId: Int, type: MediaType, season: Int = 0, episode: Int = 0) = WatchProgressEntity(
        profileId = 1,
        tmdbId = tmdbId,
        mediaType = type,
        seasonNumber = season,
        episodeNumber = episode,
        title = "Stored $tmdbId",
        episodeTitle = null,
        posterPath = "/stored$tmdbId.jpg",
        backdropPath = null,
        positionMs = 25,
        durationMs = 100,
        updatedAtMillis = 0,
    )
}
