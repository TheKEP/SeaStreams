package com.seastreams.tv.data.details

import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.core.network.TmdbCastMember
import com.seastreams.tv.core.network.TmdbCreator
import com.seastreams.tv.core.network.TmdbCredits
import com.seastreams.tv.core.network.TmdbCrewMember
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.tvDetails
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TitleDetailsRepositoryTest {

    private val tmdb = mockk<TmdbApiService>()
    private val images = TmdbImageUrls(ApiEndpoints())
    private val repository = TitleDetailsRepository(MediaHydrator(Lazy { tmdb }, images), images, AgeRestrictionEngine())
    private val kids = AgeRestriction(MovieCertification.PG, TvCertification.TV_Y7, allowUnrated = false)

    @Test
    fun `movie credits come from the single details request`() = runTest {
        coEvery { tmdb.getMovieDetails(7) } returns movieDetails(7, "PG-13").copy(
            credits = TmdbCredits(
                cast = listOf(
                    TmdbCastMember(2, "Second Lead", "B", null, order = 1),
                    TmdbCastMember(1, "Lead", "A", "/lead.jpg", order = 0),
                ),
                crew = listOf(TmdbCrewMember(9, "The Director", "Director")),
            ),
        )

        val people = repository.load(MediaRef(7, MediaType.MOVIE), restriction = null).credits

        assertEquals(listOf("The Director", "Lead", "Second Lead"), people.map { it.name })
        assertEquals(listOf("Director", "A", "B"), people.map { it.role })
        assertEquals("https://image.tmdb.org/t/p/w185/lead.jpg", people[1].profileUrl)
        assertNull(people[0].profileUrl)
        coVerify(exactly = 1) { tmdb.getMovieDetails(7) }
        coVerify(exactly = 0) { tmdb.getExternalIds(any(), any()) }
    }

    @Test
    fun `show credits fall back to the creator`() = runTest {
        coEvery { tmdb.getTvDetails(3) } returns tvDetails(3, "TV-14").copy(
            credits = TmdbCredits(cast = listOf(TmdbCastMember(1, "Star", "Hero", order = 0))),
            createdBy = listOf(TmdbCreator(50, "Showrunner")),
        )

        val people = repository.load(MediaRef(3, MediaType.TV), restriction = null).credits

        assertEquals(listOf(50, 1), people.map { it.id })
        assertEquals("Creator", people.first().role)
    }

    @Test
    fun `a title above the Kids ceiling is still restricted before credits reach the UI`() = runTest {
        coEvery { tmdb.getMovieDetails(8) } returns movieDetails(8, "R")

        assertThrows<RestrictedTitleException> { repository.load(MediaRef(8, MediaType.MOVIE), kids) }
    }
}
