package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.core.network.TmdbGenre
import com.seastreams.tv.core.network.TmdbMovieSummary
import com.seastreams.tv.core.network.TmdbPage
import com.seastreams.tv.core.network.TmdbTvSummary
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider
import com.seastreams.tv.domain.usecase.GetHomeCatalogsUseCase.Companion.ROW_CAP
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.testCatalogLoader
import com.seastreams.tv.testutil.testProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CatalogLoaderTest {

    private val mdbList = mockk<MdbListApiService>()
    private val tmdb = mockk<TmdbApiService>()
    private val loader = testCatalogLoader(mdbList, tmdb)
    private val kids = testProfile(2, isKids = true).toAgeRestriction()

    @BeforeEach
    fun setUp() {
        coEvery { tmdb.genres(any()) } returns listOf(TmdbGenre(28, "Action"), TmdbGenre(35, "Comedy"))
        // 5 pages of 20 movies: page p holds ids (p-1)*20+1 .. p*20.
        coEvery { tmdb.discoverMovies(any(), any(), any()) } answers {
            val page = secondArg<Int>()
            TmdbPage(page, (1..20).map { movie((page - 1) * 20 + it) }, totalPages = 5, totalResults = 100)
        }
        // Ids divisible by 3 are rated R, the rest G.
        coEvery { tmdb.getMovieDetails(any()) } answers { firstArg<Int>().let { movieDetails(it, if (it % 3 == 0) "R" else "G") } }
    }

    @Test
    fun `adult discover rows fetch only page 1 and use list results without per-title lookups`() = runTest {
        val row = loader.load(CatalogSource.Discover(MediaType.MOVIE, StreamingProvider.NETFLIX), restriction = null, cap = ROW_CAP)

        assertEquals((1..20).toList(), row.items.map { it.tmdbId })
        assertTrue(row.hasMore, "total_pages > 1 must flag View More")
        assertEquals(listOf("Action", "Comedy"), row.items.first().genres)
        assertEquals("https://image.tmdb.org/t/p/w342/p1.jpg", row.items.first().posterUrl)
        coVerify(exactly = 1) { tmdb.discoverMovies(StreamingProvider.NETFLIX, 1, null) }
        coVerify(exactly = 0) { tmdb.discoverMovies(any(), 2, any()) }
        coVerify(exactly = 0) { tmdb.getMovieDetails(any()) }
        coVerify(exactly = 0) { tmdb.getExternalIds(any(), any()) }
    }

    @Test
    fun `a single-page discover row omits View More`() = runTest {
        coEvery { tmdb.discoverMovies(any(), any(), any()) } returns
            TmdbPage(1, (1..20).map { movie(it) }, totalPages = 1, totalResults = 20)

        val row = loader.load(CatalogSource.Discover(MediaType.MOVIE), restriction = null, cap = ROW_CAP)

        assertEquals(20, row.items.size)
        assertFalse(row.hasMore)
    }

    @Test
    fun `view more takes up to five pages for 100 titles`() = runTest {
        val row = loader.load(CatalogSource.Discover(MediaType.MOVIE), restriction = null, cap = 100)

        assertEquals(100, row.items.size)
        assertFalse(row.hasMore)
        coVerify(exactly = 5) { tmdb.discoverMovies(null, any(), null) }
    }

    @Test
    fun `kids profiles only receive titles inside their ceiling`() = runTest {
        val row = loader.load(CatalogSource.Discover(MediaType.MOVIE), kids, cap = ROW_CAP)

        assertTrue(row.items.isNotEmpty())
        assertTrue(row.items.none { it.tmdbId % 3 == 0 }, "R-rated titles must be stripped")
        assertTrue(row.items.all { it.certification == "G" })
        assertTrue(row.hasMore)
        coVerify(exactly = 0) { tmdb.discoverMovies(any(), 2, any()) }
    }

    @Test
    fun `allowed passes adult items through and hydrates for kids`() = runTest {
        val items = loader.load(CatalogSource.Discover(MediaType.MOVIE), restriction = null, cap = 20).items
            .filter { it.tmdbId in setOf(1, 3, 4) }

        assertEquals(items, loader.allowed(items, restriction = null))
        assertEquals(listOf(1, 4), loader.allowed(items, kids).map { it.tmdbId })
    }

    @Test
    fun `genre categories pass with_genres and trending uses the trending endpoint`() = runTest {
        coEvery { tmdb.trendingTv(any()) } returns TmdbPage(1, listOf(TmdbTvSummary(id = 7, name = "Show")), totalPages = 1)

        loader.load(CatalogSource.Discover(MediaType.MOVIE, genreId = 28), restriction = null, cap = 20)
        val trending = loader.load(CatalogSource.Trending(MediaType.TV), restriction = null, cap = 40)

        coVerify(exactly = 1) { tmdb.discoverMovies(null, 1, 28) }
        assertEquals(listOf("Show"), trending.items.map { it.title })
        assertNull(trending.items.single().certification)
        coVerify(exactly = 1) { tmdb.trendingTv(1) }
    }

    @Test
    fun `pages needed for a cap round up to whole TMDB pages`() {
        assertEquals(1, CatalogLoader.pagesFor(ROW_CAP))
        assertEquals(1, CatalogLoader.pagesFor(20))
        assertEquals(2, CatalogLoader.pagesFor(30))
        assertEquals(5, CatalogLoader.pagesFor(100))
    }

    private fun movie(id: Int) = TmdbMovieSummary(
        id = id,
        title = "Movie $id",
        posterPath = "/p$id.jpg",
        releaseDate = "2026-02-01",
        voteAverage = 7.0,
        genreIds = listOf(28, 35),
    )
}
