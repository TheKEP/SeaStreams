package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.core.network.TmdbCountryReleaseDates
import com.seastreams.tv.core.network.TmdbGenre
import com.seastreams.tv.core.network.TmdbMovieDetails
import com.seastreams.tv.core.network.TmdbReleaseDate
import com.seastreams.tv.core.network.TmdbReleaseDates
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification
import com.seastreams.tv.testutil.FakeProfileDao
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ImportMdbListUseCaseTest {

    private val mdbList = mockk<MdbListApiService>()
    private val tmdb = mockk<TmdbApiService>()
    private val libraryDao = mockk<LibraryDao>()
    private val profiles = FakeProfileDao()
    private val inserted = slot<List<LibraryItemEntity>>()
    private val clock = Clock.fixed(Instant.ofEpochMilli(42), ZoneOffset.UTC)
    private val hydrator = MediaHydrator(Lazy { tmdb }, TmdbImageUrls(ApiEndpoints()))
    private val useCase = ImportMdbListUseCase(mdbList, hydrator, profiles, libraryDao, AgeRestrictionEngine(), clock)

    private var adultId = 0L
    private var kidsId = 0L

    @BeforeEach
    fun setUp() = runTest {
        adultId = profiles.insert(profile("Adult", isKids = false))
        kidsId = profiles.insert(profile("Kid", isKids = true))
        coEvery { mdbList.fetchList("https://mdblist.com/lists/owner/mixed") } returns listOf(
            MdbListItem(tmdbId = 1, mediaTypeRaw = "movie"),
            MdbListItem(tmdbId = 2, mediaTypeRaw = "movie"),
            MdbListItem(tmdbId = 3, mediaTypeRaw = "movie"),
            MdbListItem(tmdbId = 4, mediaTypeRaw = "movie"),
            MdbListItem(tmdbId = 1, mediaTypeRaw = "movie"),
            MdbListItem(tmdbId = 99, mediaTypeRaw = "show"),
            MdbListItem(tmdbId = null, mediaTypeRaw = "movie"),
        )
        coEvery { tmdb.getMovieDetails(1) } returns movie(1, "Toy Story", "G")
        coEvery { tmdb.getMovieDetails(2) } returns movie(2, "Alien", "R")
        coEvery { tmdb.getMovieDetails(3) } returns movie(3, "Indie Short", null)
        coEvery { tmdb.getMovieDetails(4) } throws IOException("timeout")
        coEvery { libraryDao.tmdbIds(any(), any()) } returns emptyList()
        coEvery { libraryDao.insertAll(capture(inserted)) } answers { inserted.captured.map { 1L } }
    }

    @Test
    fun `kids import strips out-of-range and unrated titles before writing`() = runTest {
        val result = useCase(kidsId, "mdblist.com/lists/owner/mixed", LibraryTarget.MOVIES)

        assertEquals(listOf("Toy Story"), inserted.captured.map { it.title })
        assertEquals(LibraryImportResult(added = 1, alreadyInLibrary = 0, skippedOtherType = 1, blockedByAgeRestriction = 2, failed = 1), result)
        val row = inserted.captured.single()
        assertEquals(kidsId, row.profileId)
        assertEquals(MediaType.MOVIE, row.mediaType)
        assertEquals("16,35", row.genreIds)
        assertEquals("G", row.certification)
        assertEquals(42L, row.addedAtMillis)
    }

    @Test
    fun `adult import keeps every resolvable title once and never calls external_ids`() = runTest {
        coEvery { libraryDao.insertAll(capture(inserted)) } answers { listOf(1L, -1L, 1L) }

        val result = useCase(adultId, "https://mdblist.com/lists/owner/mixed/json", LibraryTarget.MOVIES)

        assertEquals(listOf("Toy Story", "Alien", "Indie Short"), inserted.captured.map { it.title })
        assertEquals(2, result.added)
        assertEquals(1, result.alreadyInLibrary)
        coVerify(exactly = 1) { tmdb.getMovieDetails(1) }
        coVerify(exactly = 0) { tmdb.getExternalIds(any(), any()) }
    }

    @Test
    fun `unknown profile or non-mdblist url is rejected`() = runTest {
        assertThrows<UserFacingException> { useCase(999, "mdblist.com/lists/owner/mixed", LibraryTarget.MOVIES) }
        assertThrows<UserFacingException> { useCase(adultId, "https://example.com/lists/a/b", LibraryTarget.MOVIES) }
    }

    private fun profile(name: String, isKids: Boolean) = ProfileEntity(
        name = name,
        avatarIndex = 0,
        isKids = isKids,
        movieCeiling = if (isKids) MovieCertification.PG else null,
        tvCeiling = if (isKids) TvCertification.TV_Y7 else null,
        allowUnrated = false,
        createdAtMillis = 0,
    )

    private fun movie(id: Int, title: String, certification: String?) = TmdbMovieDetails(
        id = id,
        title = title,
        posterPath = "/p$id.jpg",
        genres = listOf(TmdbGenre(16), TmdbGenre(35)),
        releaseDates = TmdbReleaseDates(
            listOfNotNull(certification?.let { TmdbCountryReleaseDates("US", listOf(TmdbReleaseDate(it, 3))) }),
        ),
    )
}
