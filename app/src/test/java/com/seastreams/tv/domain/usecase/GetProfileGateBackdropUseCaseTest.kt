package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.core.network.TmdbMovieSummary
import com.seastreams.tv.core.network.TmdbPage
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification
import com.seastreams.tv.testutil.testMedia
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** PRD §2 Child-Safe Profile Gate Artwork. */
class GetProfileGateBackdropUseCaseTest {

    private val tmdb = mockk<TmdbApiService>()
    private val hydrator = mockk<MediaHydrator>()
    private val useCase = GetProfileGateBackdropUseCase(Lazy { tmdb }, hydrator, AgeRestrictionEngine(), TmdbImageUrls(ApiEndpoints()))

    private fun summary(id: Int, vararg genres: Int, backdrop: Boolean = true, adult: Boolean = false) =
        TmdbMovieSummary(id = id, backdropPath = if (backdrop) "/b$id.jpg" else null, genreIds = genres.toList(), adult = adult)

    private fun certified(id: Int, certification: String?) {
        coEvery { hydrator.hydrate(MediaRef(id, MediaType.MOVIE)) } returns testMedia(id).copy(certification = certification)
    }

    @Test
    fun `horror, romance, thriller and crime titles never qualify`() {
        val candidates = GetProfileGateBackdropUseCase.gateCandidates(
            listOf(summary(1, 27), summary(2, 10749, 35), summary(3, 53), summary(4, 80, 18), summary(5, 16, 10751), summary(6, adult = true), summary(7, backdrop = false)),
        )
        assertEquals(listOf(5), candidates.map { it.id })
    }

    @Test
    fun `the ceiling is PG and TV-PG with a Kids profile, else PG-13 and TV-14, never unrated`() {
        val kids = GetProfileGateBackdropUseCase.gateRestriction(anyKidsProfile = true)
        assertEquals(MovieCertification.PG, kids.movieCeiling)
        assertEquals(TvCertification.TV_PG, kids.tvCeiling)
        assertFalse(kids.allowUnrated)

        val adults = GetProfileGateBackdropUseCase.gateRestriction(anyKidsProfile = false)
        assertEquals(MovieCertification.PG_13, adults.movieCeiling)
        assertEquals(TvCertification.TV_14, adults.tvCeiling)
        assertFalse(adults.allowUnrated)
    }

    @Test
    fun `with a Kids profile a PG-13 title is skipped for a PG one`() = runTest {
        coEvery { tmdb.discoverGateMovies(any(), any()) } returns TmdbPage(page = 1, results = listOf(summary(10, 12), summary(11, 16)), totalPages = 1)
        certified(10, "PG-13")
        certified(11, "PG")

        val url = useCase(anyKidsProfile = true, random = Random(1))
        assertTrue(url?.endsWith("/b11.jpg") == true, "got $url")
    }

    @Test
    fun `unrated and R titles are rejected even when only adults exist`() = runTest {
        coEvery { tmdb.discoverGateMovies(any(), any()) } returns TmdbPage(page = 1, results = listOf(summary(20, 28), summary(21, 28)), totalPages = 1)
        certified(20, null)
        certified(21, "R")

        assertNull(useCase(anyKidsProfile = false))
    }

    @Test
    fun `TMDB is asked for the gate ceiling without the excluded genres`() = runTest {
        coEvery { tmdb.discoverGateMovies(any(), any()) } returns TmdbPage(results = emptyList())
        useCase(anyKidsProfile = true)
        useCase(anyKidsProfile = false)
        coVerify { tmdb.discoverGateMovies("PG", setOf(27, 10749, 53, 80)) }
        coVerify { tmdb.discoverGateMovies("PG-13", setOf(27, 10749, 53, 80)) }
    }

    @Test
    fun `only the excluded-genre-free candidates are ever looked up`() = runTest {
        coEvery { tmdb.discoverGateMovies(any(), any()) } returns TmdbPage(page = 1, results = listOf(summary(30, 27), summary(31, 35)), totalPages = 1)
        certified(31, "PG")

        assertTrue(useCase(anyKidsProfile = true)?.endsWith("/b31.jpg") == true)
        coVerify(exactly = 0) { hydrator.hydrate(MediaRef(30, MediaType.MOVIE)) }
    }
}
