package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.core.network.TmdbGenre
import com.seastreams.tv.core.network.TmdbMovieSummary
import com.seastreams.tv.core.network.TmdbPage
import com.seastreams.tv.data.catalog.GenreRepository
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.usecase.GetHomeCatalogsUseCase.Companion.ROW_CAP
import com.seastreams.tv.domain.usecase.GetViewMoreUseCase.Companion.VIEW_MORE_CAP
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.testProfile
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** [CatalogLoader.expand]: View More grows an in-memory row instead of reloading it. */
class CatalogLoaderExpandTest {

    private val mdbList = mockk<MdbListApiService>()
    private val tmdb = mockk<TmdbApiService>()
    private val hydrator = MediaHydrator(Lazy { tmdb }, TmdbImageUrls(ApiEndpoints()))
    private val loader = CatalogLoader(
        Lazy { mdbList },
        hydrator,
        TmdbImageUrls(ApiEndpoints()),
        GenreRepository(hydrator),
        AgeRestrictionEngine(),
    )
    private val source = CatalogSource.Discover(MediaType.MOVIE)
    private val kids = testProfile(2, isKids = true).toAgeRestriction()

    @BeforeEach
    fun setUp() {
        coEvery { tmdb.genres(any()) } returns listOf(TmdbGenre(28, "Action"))
        givenPages(perPage = 20, step = 20, totalPages = 5)
        // Ids divisible by 3 are rated R, the rest G.
        coEvery { tmdb.getMovieDetails(any()) } answers { firstArg<Int>().let { movieDetails(it, if (it % 3 == 0) "R" else "G") } }
    }

    @Test
    fun `pages 2 to 5 are fetched concurrently and page 1 is never fetched again`() = runBlocking {
        val seed = loader.load(source, restriction = null, cap = ROW_CAP)
        // Each of pages 2..5 waits until all four have started: a sequential loop would never finish.
        val started = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        coEvery { tmdb.discoverMovies(any(), range(2, 5), any()) } coAnswers {
            if (started.incrementAndGet() == 4) allStarted.complete(Unit)
            allStarted.await()
            page(secondArg(), perPage = 20, step = 20, totalPages = 5)
        }

        val expanded = withTimeout(5_000) { loader.expand(source, null, VIEW_MORE_CAP, seed, seedPages = 1) }

        assertEquals((1..100).toList(), expanded.items.map { it.tmdbId })
        assertFalse(expanded.hasMore)
        coVerify(exactly = 1) { tmdb.discoverMovies(null, 1, null) }
        (2..5).forEach { p -> coVerify(exactly = 1) { tmdb.discoverMovies(null, p, null) } }
    }

    @Test
    fun `titles repeated across pages are de-duplicated with the seed kept as the prefix`() = runTest {
        // Page p holds ids (p-1)*15+1 .. (p-1)*15+20, so each page repeats 5 titles of the previous one.
        givenPages(perPage = 20, step = 15, totalPages = 5)
        val seed = loader.load(source, restriction = null, cap = ROW_CAP)

        val expanded = loader.expand(source, null, VIEW_MORE_CAP, seed, seedPages = 1)

        assertEquals((1..80).toList(), expanded.items.map { it.tmdbId })
        assertEquals(seed.items, expanded.items.take(seed.items.size))
    }

    @Test
    fun `the expanded list is capped at 100 titles`() = runTest {
        givenPages(perPage = 30, step = 30, totalPages = 8)
        val seed = loader.load(source, restriction = null, cap = ROW_CAP)

        val expanded = loader.expand(source, null, VIEW_MORE_CAP, seed, seedPages = 1)

        assertEquals(VIEW_MORE_CAP, expanded.items.size)
        assertEquals(expanded.items.size, expanded.items.distinctBy { it.key }.size)
        assertTrue(expanded.hasMore)
        coVerify(exactly = 0) { tmdb.discoverMovies(any(), 6, any()) }
    }

    @Test
    fun `kids expansions only add titles inside the ceiling`() = runTest {
        val seed = loader.load(source, kids, cap = ROW_CAP)

        val expanded = loader.expand(source, kids, VIEW_MORE_CAP, seed, seedPages = 1)

        assertEquals(seed.items, expanded.items.take(seed.items.size))
        assertTrue(expanded.items.size > seed.items.size)
        assertTrue(expanded.items.none { it.tmdbId % 3 == 0 }, "R-rated titles must be stripped")
    }

    @Test
    fun `a row without more titles is returned as-is`() = runTest {
        givenPages(perPage = 20, step = 20, totalPages = 1)
        val seed = loader.load(source, restriction = null, cap = ROW_CAP)

        assertEquals(seed, loader.expand(source, null, VIEW_MORE_CAP, seed, seedPages = 1))
        coVerify(exactly = 0) { tmdb.discoverMovies(any(), 2, any()) }
    }

    @Test
    fun `MDBList expansions resume after the seed without re-hydrating it`() = runTest {
        val list = CatalogSource.MdbList("https://mdblist.com/lists/owner/test")
        coEvery { mdbList.fetchList(list.listUrl) } returns (1..60).map { MdbListItem(tmdbId = it, mediaTypeRaw = "movie") }
        val seed = loader.load(list, restriction = null, cap = ROW_CAP)

        val expanded = loader.expand(list, null, VIEW_MORE_CAP, seed, seedPages = 1)

        assertEquals((1..60).toList(), expanded.items.map { it.tmdbId })
        (1..20).forEach { id -> coVerify(exactly = 1) { tmdb.getMovieDetails(id) } }
    }

    private fun givenPages(perPage: Int, step: Int, totalPages: Int) {
        coEvery { tmdb.discoverMovies(any(), any(), any()) } answers { page(secondArg(), perPage, step, totalPages) }
    }

    private fun page(page: Int, perPage: Int, step: Int, totalPages: Int): TmdbPage<TmdbMovieSummary> =
        TmdbPage(page, (1..perPage).map { movie((page - 1) * step + it) }, totalPages = totalPages, totalResults = totalPages * perPage)

    private fun movie(id: Int) = TmdbMovieSummary(id = id, title = "Movie $id", posterPath = "/p$id.jpg", genreIds = listOf(28))
}
