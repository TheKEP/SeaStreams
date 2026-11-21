package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.core.network.TmdbMovieSummary
import com.seastreams.tv.core.network.TmdbPage
import com.seastreams.tv.core.network.TmdbTvSummary
import com.seastreams.tv.domain.filter.DateWindow
import com.seastreams.tv.domain.filter.FamilyCatalogPolicy
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification
import com.seastreams.tv.domain.usecase.GetHomeCatalogsUseCase.Companion.ROW_CAP
import com.seastreams.tv.domain.usecase.GetViewMoreUseCase.Companion.VIEW_MORE_CAP
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.testCatalogLoader
import com.seastreams.tv.testutil.testProfile
import com.seastreams.tv.testutil.tvDetails
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Kids `Trending Family Movies` / `Trending Family Shows` rows and their View More (PRD §3). */
class FamilyCatalogLoaderTest {

    private val tmdb = mockk<TmdbApiService>()
    private val loader = testCatalogLoader(mockk<MdbListApiService>(), tmdb)
    private val movies = CatalogSource.FamilyTrending(MediaType.MOVIE)
    private val shows = CatalogSource.FamilyTrending(MediaType.TV)

    private val pgKidsAllowingUnrated = kidsRestriction(MovieCertification.PG, TvCertification.TV_PG, allowUnrated = true)
    private val gKids = kidsRestriction(MovieCertification.G, TvCertification.TV_Y7, allowUnrated = false)

    @BeforeEach
    fun setUp() {
        coEvery { tmdb.genres(any()) } returns emptyList()
        // 5 pages of 20: page p holds ids (p-1)*20+1 .. p*20; page 2 repeats id 1 to check de-duplication.
        coEvery { tmdb.discoverFamilyMovies(any(), any(), any()) } answers {
            val page = firstArg<Int>()
            val ids = (1..20).map { (page - 1) * 20 + it } + if (page == 2) listOf(1) else emptyList()
            TmdbPage(page, ids.map(::movie), totalPages = 5, totalResults = 100)
        }
        coEvery { tmdb.getMovieDetails(any()) } answers { firstArg<Int>().let { movieDetails(it, movieCertOf(it)) } }
        coEvery { tmdb.discoverFamilyTv(any(), any(), any()) } answers {
            val page = firstArg<Int>()
            TmdbPage(page, (1..20).map { show((page - 1) * 20 + it) }, totalPages = 3, totalResults = 60)
        }
        coEvery { tmdb.getTvDetails(any()) } answers { firstArg<Int>().let { tvDetails(it, tvCertOf(it)) } }
    }

    @Test
    fun `movie row asks TMDB for page 1 of digital releases from the last 90 days`() = runTest {
        val row = loader.load(movies, pgKidsAllowingUnrated, ROW_CAP)

        assertTrue(row.hasMore, "total_pages > 1 must flag View More")
        coVerify(exactly = 1) {
            tmdb.discoverFamilyMovies(1, FamilyCatalogPolicy.MOVIES, DateWindow(LocalDate.of(2026, 8, 23), LocalDate.of(2026, 11, 21)))
        }
        coVerify(exactly = 0) { tmdb.discoverFamilyMovies(2, any(), any()) }
        coVerify(exactly = 0) { tmdb.getExternalIds(any(), any()) }
    }

    @Test
    fun `blocked or missing genres are stripped before any per-title lookup`() = runTest {
        val row = loader.load(movies, pgKidsAllowingUnrated, ROW_CAP)

        assertTrue(row.items.none { it.tmdbId % 10 == 7 }, "Animation + Horror (blocked secondary genre) must be stripped")
        assertTrue(row.items.none { it.tmdbId % 10 == 9 }, "titles without an allowed genre must be stripped")
        coVerify(exactly = 0) { tmdb.getMovieDetails(7) }
        coVerify(exactly = 0) { tmdb.getMovieDetails(9) }
        coVerify(exactly = 0) { tmdb.getMovieDetails(17) }
    }

    @Test
    fun `PG ceiling keeps only G and PG and drops unrated even when the profile allows unrated`() = runTest {
        val row = loader.load(movies, pgKidsAllowingUnrated, ROW_CAP)

        assertEquals(setOf("G", "PG"), row.items.mapNotNull { it.certification }.toSet())
        assertTrue(row.items.all { it.certification != null })
        assertEquals(listOf(1, 4, 5, 8, 12, 13, 16, 20), row.items.map { it.tmdbId })
    }

    @Test
    fun `G ceiling strips PG titles from the family movie row`() = runTest {
        val row = loader.load(movies, gKids, ROW_CAP)

        assertTrue(row.items.isNotEmpty())
        assertTrue(row.items.all { it.certification == "G" })
    }

    @Test
    fun `the certification allowlist holds even without a profile restriction`() = runTest {
        val row = loader.load(movies, restriction = null, cap = ROW_CAP)

        assertTrue(row.items.all { it.certification == "G" || it.certification == "PG" })
        assertTrue(row.items.none { it.tmdbId % 10 == 7 || it.tmdbId % 10 == 9 })
    }

    @Test
    fun `a single-page family row has no View More`() = runTest {
        coEvery { tmdb.discoverFamilyMovies(any(), any(), any()) } returns
            TmdbPage(1, (1..20).map(::movie), totalPages = 1, totalResults = 20)

        assertFalse(loader.load(movies, pgKidsAllowingUnrated, ROW_CAP).hasMore)
    }

    @Test
    fun `View More fetches only pages 2 to 5 once each with the same filtering and the row as prefix`() = runTest {
        val seed = loader.load(movies, pgKidsAllowingUnrated, ROW_CAP)

        val grown = loader.expand(movies, pgKidsAllowingUnrated, VIEW_MORE_CAP, seed, seedPages = 1)

        assertEquals(seed.items, grown.items.take(seed.items.size))
        assertEquals(grown.items.map { it.key }.distinct(), grown.items.map { it.key })
        assertTrue(grown.items.size > seed.items.size)
        assertTrue(grown.items.all { it.certification == "G" || it.certification == "PG" })
        assertTrue(grown.items.none { it.tmdbId % 10 == 7 || it.tmdbId % 10 == 9 })
        assertFalse(grown.hasMore)
        coVerify(exactly = 1) { tmdb.discoverFamilyMovies(1, any(), any()) }
        (2..5).forEach { page -> coVerify(exactly = 1) { tmdb.discoverFamilyMovies(page, any(), any()) } }
        coVerify(exactly = 0) { tmdb.getExternalIds(any(), any()) }
    }

    @Test
    fun `show row uses the on-the-air window and a TV-Y7 ceiling strips TV-G and TV-PG`() = runTest {
        val row = loader.load(shows, gKids, ROW_CAP)

        assertTrue(row.items.isNotEmpty())
        assertTrue(row.items.all { it.certification == "TV-Y" || it.certification == "TV-Y7" })
        assertTrue(row.items.none { it.tmdbId % 10 == 7 }, "Kids + Reality must be stripped")
        assertTrue(row.hasMore)
        coVerify(exactly = 1) {
            tmdb.discoverFamilyTv(1, FamilyCatalogPolicy.SHOWS, DateWindow(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 11, 28)))
        }
        coVerify(exactly = 0) { tmdb.getExternalIds(any(), any()) }
    }

    @Test
    fun `TV-PG ceiling keeps TV-Y through TV-PG but never TV-14 or unrated shows`() = runTest {
        val row = loader.load(shows, pgKidsAllowingUnrated, ROW_CAP)

        assertEquals(setOf("TV-Y", "TV-Y7", "TV-G", "TV-PG"), row.items.mapNotNull { it.certification }.toSet())
        assertTrue(row.items.none { it.tmdbId == 5 || it.tmdbId == 6 }, "TV-14 and unrated must be stripped")
    }

    @Test
    fun `show View More grows through the remaining pages with identical filtering`() = runTest {
        val seed = loader.load(shows, gKids, ROW_CAP)

        val grown = loader.expand(shows, gKids, VIEW_MORE_CAP, seed, seedPages = 1)

        assertEquals(seed.items, grown.items.take(seed.items.size))
        assertTrue(grown.items.all { it.certification == "TV-Y" || it.certification == "TV-Y7" })
        assertTrue(grown.items.none { it.tmdbId % 10 == 7 })
        (1..3).forEach { page -> coVerify(exactly = 1) { tmdb.discoverFamilyTv(page, any(), any()) } }
    }

    private fun kidsRestriction(movie: MovieCertification, tv: TvCertification, allowUnrated: Boolean) =
        testProfile(2, isKids = true).copy(movieCeiling = movie, tvCeiling = tv, allowUnrated = allowUnrated).toAgeRestriction()

    /** `id % 4`: 0 → PG, 1 → G, 2 → PG-13, 3 → unrated. */
    private fun movieCertOf(id: Int): String? = when (id % 4) {
        0 -> "PG"
        1 -> "G"
        2 -> "PG-13"
        else -> null
    }

    /** `id % 4`: 0 → TV-Y, 1 → TV-Y7, 2 → TV-G, 3 → TV-PG; id 5 is TV-14 and id 6 unrated. */
    private fun tvCertOf(id: Int): String? = when {
        id == 5 -> "TV-14"
        id == 6 -> null
        id % 4 == 0 -> "TV-Y"
        id % 4 == 1 -> "TV-Y7"
        id % 4 == 2 -> "TV-G"
        else -> "TV-PG"
    }

    /** `id % 10 == 7`: Animation + Horror; `id % 10 == 9`: no genres; otherwise Family + Comedy. */
    private fun movie(id: Int) = TmdbMovieSummary(
        id = id,
        title = "Movie $id",
        posterPath = "/p$id.jpg",
        genreIds = when (id % 10) {
            7 -> listOf(16, 27)
            9 -> emptyList()
            else -> listOf(10751, 35)
        },
    )

    /** `id % 10 == 7`: Kids + Reality; otherwise Kids + Animation. */
    private fun show(id: Int) = TmdbTvSummary(
        id = id,
        name = "Show $id",
        posterPath = "/p$id.jpg",
        genreIds = if (id % 10 == 7) listOf(10762, 10764) else listOf(10762, 16),
    )
}
