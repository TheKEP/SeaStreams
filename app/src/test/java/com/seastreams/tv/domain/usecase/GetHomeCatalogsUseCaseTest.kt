package com.seastreams.tv.domain.usecase

import app.cash.turbine.test
import com.seastreams.tv.core.database.entity.CatalogPlacement
import com.seastreams.tv.core.database.entity.CustomCatalogEntity
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.testutil.FakeCatalogDao
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.testProfile
import com.seastreams.tv.testutil.tvDetails
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.Clock
import kotlin.random.Random

class GetHomeCatalogsUseCaseTest {

    private val mdbList = mockk<MdbListApiService>()
    private val tmdb = mockk<TmdbApiService>()
    private val catalogDao = FakeCatalogDao()
    private val useCase = GetHomeCatalogsUseCase(
        Lazy { mdbList },
        MediaHydrator(Lazy { tmdb }, TmdbImageUrls(ApiEndpoints())),
        catalogDao,
        AgeRestrictionEngine(),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        Clock.systemUTC(),
    )
    private val adult = testProfile(1, isKids = false)
    private val kids = testProfile(2, isKids = true)
    private val spec = CatalogRowSpec("test", "Test", "https://mdblist.com/lists/owner/test")

    /** Movies 1..[count]; ids divisible by 3 are rated R, the rest G. */
    private fun givenMovieList(url: String, count: Int) {
        coEvery { mdbList.fetchList(url) } returns (1..count).map { MdbListItem(tmdbId = it, mediaTypeRaw = "movie") }
        coEvery { tmdb.getMovieDetails(any()) } answers {
            val id = firstArg<Int>()
            movieDetails(id, if (id % 3 == 0) "R" else "G")
        }
    }

    @Test
    fun `adult row caps at 30 and flags more when the list is longer`() = runTest {
        givenMovieList(spec.listUrl, 45)

        val row = useCase.loadRow(spec, restriction = null)

        assertEquals((1..30).toList(), row.items.map { it.tmdbId })
        assertTrue(row.hasMore)
        coVerify(exactly = 30) { tmdb.getMovieDetails(any()) }
        coVerify(exactly = 0) { tmdb.getExternalIds(any(), any()) }
    }

    @Test
    fun `short row has no View More`() = runTest {
        givenMovieList(spec.listUrl, 12)

        val row = useCase.loadRow(spec, restriction = null)

        assertEquals(12, row.items.size)
        assertFalse(row.hasMore)
    }

    @Test
    fun `kids row strips out-of-range titles and back-fills from further down the list`() = runTest {
        givenMovieList(spec.listUrl, 60)

        val row = useCase.loadRow(spec, kids.toAgeRestriction())

        assertEquals(30, row.items.size)
        assertTrue(row.items.all { it.certification == "G" })
        assertTrue(row.items.none { it.tmdbId % 3 == 0 })
        assertEquals((1..44).filter { it % 3 != 0 }, row.items.map { it.tmdbId })
        assertTrue(row.hasMore)
    }

    @Test
    fun `kids row drops unrated and mature TV titles`() = runTest {
        coEvery { mdbList.fetchList(spec.listUrl) } returns listOf(
            MdbListItem(tmdbId = 1, mediaTypeRaw = "show"),
            MdbListItem(tmdbId = 2, mediaTypeRaw = "show"),
            MdbListItem(tmdbId = 3, mediaTypeRaw = "show"),
            MdbListItem(tmdbId = 4, mediaTypeRaw = "show"),
        )
        coEvery { tmdb.getTvDetails(1) } returns tvDetails(1, "TV-Y")
        coEvery { tmdb.getTvDetails(2) } returns tvDetails(2, "TV-MA")
        coEvery { tmdb.getTvDetails(3) } returns tvDetails(3, null)
        coEvery { tmdb.getTvDetails(4) } returns tvDetails(4, "TV-14")

        val row = useCase.loadRow(spec, kids.toAgeRestriction())

        assertEquals(listOf(1), row.items.map { it.tmdbId })
        assertFalse(row.hasMore)
    }

    @Test
    fun `row specs are the fixed rows for the profile type plus custom Home lists`() {
        val custom = listOf(CustomCatalogEntity(7, "https://mdblist.com/lists/a/b", "My List", CatalogPlacement.HOME, 0, 0))

        val adultTitles = HomeCatalogSources.rowSpecs(isKids = false, custom).map { it.title }
        val kidsTitles = HomeCatalogSources.rowSpecs(isKids = true, custom).map { it.title }

        assertEquals(listOf("Most Watched Movies", "Most Watched Shows", "My List"), adultTitles)
        assertEquals(
            listOf("Trending Kids Movies", "Trending Kids Shows", "Latest Family Movies", "Latest Family Shows", "My List"),
            kidsTitles,
        )
    }

    @Test
    fun `observeRows emits loading rows then each row's result`() = runTest {
        catalogDao.insert(CustomCatalogEntity(0, "https://mdblist.com/lists/a/custom", "Custom", CatalogPlacement.HOME, 0, 0))
        HomeCatalogSources.ADULT_ROWS.forEach { givenMovieList(it.listUrl, 3) }
        coEvery { mdbList.fetchList("https://mdblist.com/lists/a/custom") } throws IOException("offline")

        useCase.observeRows(adult).test {
            val first = awaitItem()
            assertEquals(listOf("most-watched-movies", "most-watched-shows", "custom-1"), first.map { it.spec.id })
            assertTrue(first.all { it.content == RowContent.Loading })
            var latest = first
            while (latest.any { it.content == RowContent.Loading }) latest = awaitItem()
            assertTrue(latest[0].content is RowContent.Loaded)
            assertTrue(latest[1].content is RowContent.Loaded)
            assertEquals(RowContent.Failed, latest[2].content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Kids lists of 50 each: even movie ids are PG-13 (over the PG ceiling); show ids divisible by 3 are TV-MA. */
    private fun givenKidsHeroLists() {
        coEvery { mdbList.fetchList(HomeCatalogSources.KIDS_MOVIES) } returns
            (1..50).map { MdbListItem(tmdbId = it, mediaTypeRaw = "movie") }
        coEvery { mdbList.fetchList(HomeCatalogSources.KIDS_SHOWS) } returns
            (101..150).map { MdbListItem(tmdbId = it, mediaTypeRaw = "show") }
        coEvery { tmdb.getMovieDetails(any()) } answers { movieDetails(firstArg(), if (firstArg<Int>() % 2 == 0) "PG-13" else "PG") }
        coEvery { tmdb.getTvDetails(any()) } answers { tvDetails(firstArg(), if (firstArg<Int>() % 3 == 0) "TV-MA" else "TV-Y") }
    }

    @Test
    fun `kids hero caps at ten approved titles drawn only from the top 15 of each kids list`() = runTest {
        givenKidsHeroLists()

        val hero = useCase.loadHero(kids, Random(4))

        val approvedTop = (1..15).filter { it % 2 != 0 }.toSet() + (101..115).filter { it % 3 != 0 }.toSet()
        assertEquals(GetHomeCatalogsUseCase.HERO_SIZE, hero.size)
        assertTrue(approvedTop.containsAll(hero.map { it.tmdbId }), "hero must only hold approved top-15 titles")
        assertTrue(hero.none { it.certification == "PG-13" || it.certification == "TV-MA" })
        assertEquals(hero.size, hero.map { it.tmdbId }.toSet().size)
        (16..50).forEach { id -> coVerify(exactly = 0) { tmdb.getMovieDetails(id) } }
        (116..150).forEach { id -> coVerify(exactly = 0) { tmdb.getTvDetails(id) } }
    }

    @Test
    fun `kids hero never back-fills past the top 15 when the age filter strips most titles`() = runTest {
        givenKidsHeroLists()
        coEvery { tmdb.getMovieDetails(any()) } answers { movieDetails(firstArg(), "R") }
        coEvery { tmdb.getTvDetails(any()) } answers { tvDetails(firstArg(), if (firstArg<Int>() <= 103) "TV-Y" else "TV-14") }

        val hero = useCase.loadHero(kids, Random(2))

        assertEquals(setOf(101, 102, 103), hero.map { it.tmdbId }.toSet())
        coVerify(exactly = 0) { tmdb.getMovieDetails(16) }
        coVerify(exactly = 0) { tmdb.getTvDetails(116) }
    }

    @Test
    fun `adult hero picks ten titles with backdrops from the top 15 of each chart`() = runTest {
        coEvery { mdbList.fetchList(HomeCatalogSources.HERO_ADULT_MOVIES) } returns
            (1..40).map { MdbListItem(tmdbId = it, mediaTypeRaw = "movie") }
        coEvery { mdbList.fetchList(HomeCatalogSources.HERO_ADULT_SHOWS) } returns
            (101..140).map { MdbListItem(tmdbId = it, mediaTypeRaw = "show") }
        coEvery { tmdb.getMovieDetails(any()) } answers { movieDetails(firstArg(), "R", backdrop = firstArg<Int>() > 5) }
        coEvery { tmdb.getTvDetails(any()) } answers { tvDetails(firstArg(), "TV-MA") }

        val hero = useCase.loadHero(adult, Random(1))

        assertEquals(GetHomeCatalogsUseCase.HERO_SIZE, hero.size)
        assertTrue(hero.all { it.heroBackdropUrl != null })
        val top = (1..15).toSet() + (101..115).toSet()
        assertTrue(top.containsAll(hero.map { it.tmdbId }), "hero must only hold top-15 titles")
        (16..40).forEach { id -> coVerify(exactly = 0) { tmdb.getMovieDetails(id) } }
    }

    @Test
    fun `adult hero survives one failed chart and fails only when both lists fail`() = runTest {
        coEvery { mdbList.fetchList(HomeCatalogSources.HERO_ADULT_MOVIES) } returns
            (1..40).map { MdbListItem(tmdbId = it, mediaTypeRaw = "movie") }
        coEvery { mdbList.fetchList(HomeCatalogSources.HERO_ADULT_SHOWS) } throws IOException("offline")
        coEvery { tmdb.getMovieDetails(any()) } answers { movieDetails(firstArg(), "R") }

        val hero = useCase.loadHero(adult, Random(1))

        assertEquals(GetHomeCatalogsUseCase.HERO_SIZE, hero.size)
        assertTrue(hero.all { it.tmdbId in 1..15 })

        coEvery { mdbList.fetchList(HomeCatalogSources.HERO_ADULT_MOVIES) } throws IOException("offline")
        assertThrows<IOException> { useCase.loadHero(adult) }
    }

    @Test
    fun `prefetch loads hero and top two rows once, and Home reuses them`() = runTest {
        givenMovieList(HomeCatalogSources.HERO_ADULT_MOVIES, 15)
        givenMovieList(HomeCatalogSources.HERO_ADULT_SHOWS, 0)
        HomeCatalogSources.ADULT_ROWS.forEach { givenMovieList(it.listUrl, 5) }

        val prefetch = useCase.prefetch(adult)
        val hero = useCase.takeHero(adult)
        useCase.observeRows(adult).test {
            var latest = awaitItem()
            while (latest.any { it.content == RowContent.Loading }) latest = awaitItem()
            assertEquals(prefetch.rows, latest.map { (it.content as RowContent.Loaded).row })
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(prefetch.hero, hero)
        assertEquals(2, prefetch.rows.size)
        coVerify(exactly = 1) { mdbList.fetchList(HomeCatalogSources.HERO_ADULT_MOVIES) }
        HomeCatalogSources.ADULT_ROWS.forEach { row -> coVerify(exactly = 1) { mdbList.fetchList(row.listUrl) } }
    }

    @Test
    fun `taking the hero consumes it so the next visit reshuffles`() = runTest {
        givenMovieList(HomeCatalogSources.HERO_ADULT_MOVIES, 15)
        givenMovieList(HomeCatalogSources.HERO_ADULT_SHOWS, 0)

        useCase.takeHero(adult)
        useCase.takeHero(adult)

        coVerify(exactly = 2) { mdbList.fetchList(HomeCatalogSources.HERO_ADULT_MOVIES) }
    }

    @Test
    fun `prefetch reports a failed hero as null instead of throwing`() = runTest {
        coEvery { mdbList.fetchList(HomeCatalogSources.HERO_ADULT_MOVIES) } throws IOException("offline")
        coEvery { mdbList.fetchList(HomeCatalogSources.HERO_ADULT_SHOWS) } throws IOException("offline")
        HomeCatalogSources.ADULT_ROWS.forEach { givenMovieList(it.listUrl, 3) }

        val prefetch = useCase.prefetch(adult)

        assertEquals(null, prefetch.hero)
        assertEquals(2, prefetch.rows.size)
    }

    @Test
    fun `hero pool is the top 15 of each list, shuffled`() {
        val movies = (1..30).map { MediaRef(it, MediaType.MOVIE) }
        val shows = (1..30).map { MediaRef(it, MediaType.TV) }
        val inOrder = movies.take(15) + shows.take(15)

        val pool = GetHomeCatalogsUseCase.buildHeroPool(movies, shows, Random(0))
        val otherPool = GetHomeCatalogsUseCase.buildHeroPool(movies, shows, Random(99))

        assertEquals(30, pool.size)
        assertEquals(inOrder.toSet(), pool.toSet())
        assertTrue(pool != inOrder, "pool should be shuffled")
        assertTrue(pool != otherPool, "different seeds should give different orders")
    }

    @Test
    fun `hero pool drops duplicates within the top 15 and handles short lists`() {
        val movies = listOf(1, 2, 1, 3).map { MediaRef(it, MediaType.MOVIE) }
        val shows = (1..5).map { MediaRef(it, MediaType.TV) }

        val pool = GetHomeCatalogsUseCase.buildHeroPool(movies, shows, Random(0))

        assertEquals(8, pool.size)
        assertEquals(movies.toSet() + shows.toSet(), pool.toSet())
    }

    @Test
    fun `mdblist entries without tmdb id or media type are skipped`() {
        val refs = listOf(
            MdbListItem(tmdbId = 1, mediaTypeRaw = "movie"),
            MdbListItem(tmdbId = null, mediaTypeRaw = "movie"),
            MdbListItem(tmdbId = 2, mediaTypeRaw = "podcast"),
            MdbListItem(tmdbId = 1, mediaTypeRaw = "movie"),
            MdbListItem(tmdbId = 1, mediaTypeRaw = "show"),
        ).toRefs()

        assertEquals(listOf(MediaRef(1, MediaType.MOVIE), MediaRef(1, MediaType.TV)), refs)
    }
}
