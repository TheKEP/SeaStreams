package com.seastreams.tv.domain.usecase

import app.cash.turbine.test
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.tvDetails
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.random.Random

/** Home hero selection plus the prefetch/take caching shared with the profile screen. */
class GetHomeHeroUseCaseTest : HomeCatalogsTestBase() {

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
}
