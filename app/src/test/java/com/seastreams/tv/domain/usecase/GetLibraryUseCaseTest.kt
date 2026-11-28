package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.LibraryItemWithActivity
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.filter.LibraryKeywordGenres
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.FakeProfileDao
import com.seastreams.tv.testutil.testProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GetLibraryUseCaseTest {

    private val libraryDao = mockk<LibraryDao>()
    private val profileDao = FakeProfileDao()
    private val useCase = GetLibraryUseCase(libraryDao, profileDao, TmdbImageUrls(ApiEndpoints()), AgeRestrictionEngine())

    private val rows = listOf(
        row(1, "Bluey", MediaType.TV, "TV-Y", watched = 99),
        row(2, "Alien", MediaType.MOVIE, "R", watched = null),
        row(3, "Paddington", MediaType.MOVIE, "PG", watched = null),
        row(4, "Unrated Thing", MediaType.MOVIE, null, watched = null),
    )

    @Test
    fun `adult profiles see every saved title with posters and activity`() = runTest {
        val adult = testProfile(1, isKids = false)
        every { libraryDao.observeWithActivity(adult.id) } returns flowOf(rows)

        val entries = useCase.observe(adult).first()

        assertEquals(listOf(1, 2, 3, 4), entries.map { it.media.tmdbId })
        assertEquals("https://image.tmdb.org/t/p/w342/p1.jpg", entries.first().media.posterUrl)
        assertEquals(listOf(16, 10762), entries.first().genreIds)
        assertEquals(99L, entries.first().lastWatchedAtMillis)
    }

    @Test
    fun `kids profiles never receive saved titles above their current ceiling`() = runTest {
        val kids = testProfile(2, isKids = true) // PG / TV-Y7, unrated blocked
        every { libraryDao.observeWithActivity(kids.id) } returns flowOf(rows)

        val entries = useCase.observe(kids).first()

        assertEquals(listOf("Bluey", "Paddington"), entries.map { it.media.title })
    }

    @Test
    fun `movies with a Christmas keyword carry the Christmas genre, shows and unknown keywords don't`() = runTest {
        val adult = testProfile(1, isKids = false)
        every { libraryDao.observeWithActivity(adult.id) } returns flowOf(
            listOf(
                row(1, "Elf", MediaType.MOVIE, "PG", watched = null, keywordIds = "9715,207317"),
                row(2, "Alien", MediaType.MOVIE, "R", watched = null, keywordIds = "9715"),
                row(3, "Old save", MediaType.MOVIE, "PG", watched = null, keywordIds = null),
                row(4, "Bluey", MediaType.TV, "TV-Y", watched = null, keywordIds = "207317"),
            ),
        )

        val genres = useCase.observe(adult).first().associate { it.media.tmdbId to it.genreIds }

        val xmas = LibraryKeywordGenres.CHRISTMAS_GENRE_ID
        assertEquals(mapOf(1 to listOf(16, 10762, xmas), 2 to listOf(16, 10762), 3 to listOf(16, 10762), 4 to listOf(16, 10762)), genres)
    }

    @Test
    fun `sort order is persisted on the profile`() = runTest {
        val id = profileDao.insert(testProfile(0, isKids = false))

        useCase.setSortOrder(id, LibrarySortOrder.LAST_WATCHED)

        assertEquals(LibrarySortOrder.LAST_WATCHED, profileDao.getById(id)?.librarySortOrder)
    }

    private fun row(id: Int, title: String, type: MediaType, certification: String?, watched: Long?, keywordIds: String? = null) = LibraryItemWithActivity(
        item = LibraryItemEntity(
            profileId = 1,
            tmdbId = id,
            mediaType = type,
            title = title,
            posterPath = "/p$id.jpg",
            certification = certification,
            genreIds = "16,10762",
            addedAtMillis = id.toLong(),
            keywordIds = keywordIds,
        ),
        lastWatchedAtMillis = watched,
    )
}
