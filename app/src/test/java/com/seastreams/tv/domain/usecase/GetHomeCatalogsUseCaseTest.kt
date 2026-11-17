package com.seastreams.tv.domain.usecase

import app.cash.turbine.test
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
    fun `adult row caps at 20 and flags more when the list is longer`() = runTest {
        givenMovieList(spec.listUrl, 45)

        val row = useCase.loadRow(spec, restriction = null)

        assertEquals(20, GetHomeCatalogsUseCase.ROW_CAP)
        assertEquals((1..20).toList(), row.items.map { it.tmdbId })
        assertTrue(row.hasMore)
        coVerify(exactly = 20) { tmdb.getMovieDetails(any()) }
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
    fun `exactly 20 titles fill the row without View More`() = runTest {
        givenMovieList(spec.listUrl, 20)

        val row = useCase.loadRow(spec, restriction = null)

        assertEquals((1..20).toList(), row.items.map { it.tmdbId })
        assertFalse(row.hasMore)
    }

    @Test
    fun `21 titles show 20 plus View More`() = runTest {
        givenMovieList(spec.listUrl, 21)

        val row = useCase.loadRow(spec, restriction = null)

        assertEquals((1..20).toList(), row.items.map { it.tmdbId })
        assertTrue(row.hasMore)
    }

    @Test
    fun `kids row strips out-of-range titles and back-fills from further down the list`() = runTest {
        givenMovieList(spec.listUrl, 60)

        val row = useCase.loadRow(spec, kids.toAgeRestriction())

        assertEquals(20, row.items.size)
        assertTrue(row.items.all { it.certification == "G" })
        assertTrue(row.items.none { it.tmdbId % 3 == 0 })
        assertEquals((1..29).filter { it % 3 != 0 }, row.items.map { it.tmdbId })
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
    fun `observeRows emits loading rows then each row's result`() = runTest {
        addonDao.upsert(addonEntity("offline.example", 0, """[{"type":"movie","id":"top","name":"Top"}]"""))
        HomeCatalogSources.ADULT_ROWS.forEach { givenMovieList(it.listUrl, 3) }
        coEvery { tmdb.genres(any()) } returns emptyList()
        coEvery { addonClient.fetchCatalog(any(), any(), any(), any()) } throws IOException("offline")

        useCase.observeRows(adult).test {
            val first = awaitItem()
            assertEquals(
                listOf("most-watched-movies", "most-watched-shows", "addon:https://offline.example/manifest.json:movie:top"),
                first.map { it.spec.id },
            )
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
