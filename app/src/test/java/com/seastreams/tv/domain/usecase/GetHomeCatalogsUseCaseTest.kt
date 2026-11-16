package com.seastreams.tv.domain.usecase

import app.cash.turbine.test
import com.seastreams.tv.core.database.entity.CatalogPlacement
import com.seastreams.tv.core.database.entity.CustomCatalogEntity
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.testutil.tvDetails
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Home catalog rows: caps, age filtering with back-fill, row specs and streaming row states. */
class GetHomeCatalogsUseCaseTest : HomeCatalogsTestBase() {

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
