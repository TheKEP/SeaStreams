package com.seastreams.tv.domain.usecase

import app.cash.turbine.test
import com.seastreams.tv.core.network.StremioMetaPreview
import com.seastreams.tv.core.network.TmdbFindResult
import com.seastreams.tv.core.network.TmdbMovieSummary
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider
import com.seastreams.tv.testutil.movieDetails
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Stremio addon catalogs: Home-only rows beneath the built-in rows, in addon order, age-filtered. */
class AddonHomeCatalogsTest : HomeCatalogsTestBase() {

    private val alpha = addonEntity(
        "alpha.example", sortOrder = 0,
        catalogsJson = """[
            {"type":"movie","id":"top","name":"Top"},
            {"type":"series","id":"new","name":"New"},
            {"type":"movie","id":"search","name":"Search","extra":[{"name":"search","isRequired":true}]},
            {"type":"movie","id":"by-genre","name":"Genre","extraRequired":["genre"]},
            {"type":"channel","id":"live","name":"Live"}
        ]""",
    )
    private val beta = addonEntity("beta.example", sortOrder = 1, catalogsJson = """[{"type":"movie","id":"picks","name":"Picks"}]""")
    private val disabled = addonEntity("off.example", sortOrder = 2, catalogsJson = """[{"type":"movie","id":"x","name":"X"}]""", enabled = false)
    private val alphaTop = CatalogSource.Addon(alpha.manifestUrl, "top", MediaType.MOVIE)
    private val addonSpec = CatalogRowSpec("addon-top", "Top (Movies)", alphaTop)

    private val alphaIds = listOf(
        "addon:https://alpha.example/manifest.json:movie:top",
        "addon:https://alpha.example/manifest.json:series:new",
    )
    private val betaId = "addon:https://beta.example/manifest.json:movie:picks"

    @BeforeEach
    fun setUp() = runTest {
        // Both profiles start with the same addons, each in its own per-profile list.
        listOf(adult.id, kids.id).forEach { profileId ->
            addonDao.insertIfAbsent(BuiltInAddons.defaults(profileId, 0))
            addonDao.upsert(alpha.copy(profileId = profileId, sortOrder = 2))
            addonDao.upsert(beta.copy(profileId = profileId, sortOrder = 3))
            addonDao.upsert(disabled.copy(profileId = profileId, sortOrder = 4))
        }
        coEvery { tmdb.genres(any()) } returns emptyList()
    }

    @Test
    fun `Home only shows the catalogs of the profile's own addons`() = runTest {
        addonDao.setEnabled(adult.id, beta.manifestUrl, false)
        addonDao.delete(kids.id, alpha.manifestUrl)

        assertEquals(alphaIds, useCase.observeRows(adult).first().drop(2).map { it.spec.id })
        assertEquals(listOf(betaId), useCase.observeRows(kids).first().drop(HomeCatalogSources.KIDS_ROWS.size).map { it.spec.id })
    }

    @Test
    fun `adult Home shows enabled addon catalogs directly beneath Most Watched Shows in addon order`() = runTest {
        val specs = useCase.observeRows(adult).first().map { it.spec }

        assertEquals(listOf("most-watched-movies", "most-watched-shows") + alphaIds + betaId, specs.map { it.id })
        assertEquals(listOf("Top (Movies)", "New (Shows)", "Picks (Movies)"), specs.drop(2).map { it.title })
        assertEquals(alphaTop, specs[2].source)
    }

    @Test
    fun `kids Home shows addon catalogs directly beneath Trending Family Shows`() = runTest {
        val ids = useCase.observeRows(kids).first().map { it.spec.id }

        assertEquals(2, HomeCatalogSources.KIDS_ROWS.size)
        assertEquals(HomeCatalogSources.KIDS_ROWS.map { it.id } + alphaIds + betaId, ids)
        assertEquals("trending-family-shows", ids[HomeCatalogSources.KIDS_ROWS.lastIndex])
    }

    @Test
    fun `kids Home rows are only the two TMDB family rows followed by addon catalogs`() = runTest {
        val specs = useCase.observeRows(kids).first().map { it.spec }

        assertEquals(listOf("trending-family-movies", "trending-family-shows") + alphaIds + betaId, specs.map { it.id })
        assertTrue(specs.none { it.source is CatalogSource.MdbList }, "no Kids Home row may come from MDBList")
        assertEquals(listOf("Trending Family Movies", "Trending Family Shows"), specs.take(2).map { it.title })
        assertEquals(CatalogSource.FamilyTrending(MediaType.MOVIE), specs[0].source)
        assertEquals(CatalogSource.FamilyTrending(MediaType.TV), specs[1].source)

        val adultSpecs = useCase.observeRows(adult).first().map { it.spec }
        assertEquals(listOf("most-watched-movies", "most-watched-shows") + alphaIds + betaId, adultSpecs.map { it.id })
        assertTrue(adultSpecs.none { it.source is CatalogSource.FamilyTrending })
    }

    @Test
    fun `reordering or disabling addons reorders Home's addon rows`() = runTest {
        useCase.observeRows(adult).test {
            assertEquals(alphaIds + betaId, awaitItem().drop(2).map { it.spec.id })

            addonDao.reorder(adult.id, listOf(beta.manifestUrl, alpha.manifestUrl, BuiltInAddons.TMDB_URL))
            var rows = awaitItem()
            while (rows.size < 5 || rows[2].spec.id != betaId) rows = awaitItem()
            assertEquals(listOf(betaId) + alphaIds, rows.drop(2).map { it.spec.id })

            addonDao.setEnabled(adult.id, beta.manifestUrl, false)
            while (rows.any { it.spec.id == betaId }) rows = awaitItem()
            assertEquals(alphaIds, rows.drop(2).map { it.spec.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addon row resolves tmdb and imdb ids, caps at 20 and flags View More`() = runTest {
        // 30 metas alternating tmdb:N / ttN, plus entries that can't be mapped onto a TMDB movie.
        val metas = (1..30).map { n -> StremioMetaPreview(if (n % 2 == 0) "tmdb:$n" else "tt$n", "movie") }
        givenCatalogPages(listOf(StremioMetaPreview("kitsu:1", "movie"), StremioMetaPreview("tt999", "series")) + metas)

        val row = useCase.loadRow(addonSpec, restriction = null)

        assertEquals((1..GetHomeCatalogsUseCase.ROW_CAP).toList(), row.items.map { it.tmdbId })
        assertTrue(row.hasMore)
        // Only as many metas as the cap needs are resolved: 10 via /find, 10 via details.
        coVerify(exactly = 10) { tmdb.findByImdbId(any()) }
        coVerify(exactly = 10) { tmdb.getMovieDetails(any()) }
        coVerify(exactly = 0) { tmdb.getExternalIds(any(), any()) }
    }

    @Test
    fun `kids addon row only keeps titles inside the rating ceiling`() = runTest {
        givenCatalogPages((1..9).map { StremioMetaPreview("tmdb:$it", "movie") })

        val row = useCase.loadRow(addonSpec, kids.toAgeRestriction())

        assertEquals(listOf(1, 2, 4, 5, 7, 8), row.items.map { it.tmdbId })
        assertFalse(row.hasMore)
    }

    @Test
    fun `View More grows an addon row through skip pages with the row as prefix`() = runTest {
        val first = (1..30).map { StremioMetaPreview("tmdb:$it", "movie") }
        val second = (31..40).map { StremioMetaPreview("tmdb:$it", "movie") }
        coEvery { addonClient.fetchCatalog(alpha.manifestUrl, "movie", "top", 0) } returns first
        coEvery { addonClient.fetchCatalog(alpha.manifestUrl, "movie", "top", 30) } returns second
        coEvery { addonClient.fetchCatalog(alpha.manifestUrl, "movie", "top", 40) } returns emptyList()
        givenMovieDetails()
        val seed = loader.load(alphaTop, restriction = null, cap = GetHomeCatalogsUseCase.ROW_CAP)

        val grown = loader.expand(alphaTop, null, GetViewMoreUseCase.VIEW_MORE_CAP, seed, seedPages = 1)

        assertEquals(seed.items, grown.items.take(seed.items.size))
        assertEquals((1..40).toList(), grown.items.map { it.tmdbId })
        assertFalse(grown.hasMore)
    }

    @Test
    fun `addon catalog rows are a Home-only source`() {
        val specs = AddonCatalogSources.rowSpecs(listOf(alpha, beta))

        assertTrue(specs.all { it.source is CatalogSource.Addon })
        MediaType.entries.forEach { type ->
            val discover = DiscoverCatalogSources.rowSpecs(type, StreamingProvider.entries)
            assertTrue(discover.none { it.source is CatalogSource.Addon }, "$type tab must not contain addon catalogs")
        }
    }

    /** One page of [metas] at skip 0, then an empty page; R-rated every third id, IMDb ids → id via /find. */
    private fun givenCatalogPages(metas: List<StremioMetaPreview>) {
        coEvery { addonClient.fetchCatalog(alpha.manifestUrl, "movie", "top", 0) } returns metas
        coEvery { addonClient.fetchCatalog(alpha.manifestUrl, "movie", "top", metas.size) } returns emptyList()
        givenMovieDetails()
        coEvery { tmdb.findByImdbId(any()) } answers {
            val id = firstArg<String>().removePrefix("tt").toInt()
            TmdbFindResult(movieResults = listOf(TmdbMovieSummary(id = id, title = "Movie $id")))
        }
    }

    private fun givenMovieDetails() {
        coEvery { tmdb.getMovieDetails(any()) } answers {
            val id = firstArg<Int>()
            movieDetails(id, if (id % 3 == 0) "R" else "G")
        }
    }
}
