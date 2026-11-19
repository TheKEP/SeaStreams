package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.LastUsedStreamEntity
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.FakeLastUsedStreamDao
import com.seastreams.tv.testutil.FakeLibraryDao
import com.seastreams.tv.testutil.FakeProfileDao
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.testProfile
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * PRD §8: an MDBList import only ever *adds* titles. Titles already in the target profile's Library
 * are skipped — never duplicated, deleted, overwritten or re-dated — and their watch progress and
 * LAST USED stream are left exactly as they were. Other profiles are never touched.
 */
class MdbListImportDuplicateSafetyTest {

    private val listUrl = "https://mdblist.com/lists/owner/picks"
    private val mdbList = mockk<MdbListApiService>()
    private val tmdb = mockk<TmdbApiService>()
    private val profiles = FakeProfileDao()
    private val library = FakeLibraryDao()
    private val progress = FakeWatchProgressDao()
    private val lastUsed = FakeLastUsedStreamDao()
    private val useCase = ImportMdbListUseCase(
        mdbList,
        MediaHydrator(Lazy { tmdb }, TmdbImageUrls(ApiEndpoints())),
        profiles,
        library,
        AgeRestrictionEngine(),
        Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
    )
    private var profileA = 0L
    private var profileB = 0L

    @BeforeEach
    fun setUp() = runTest {
        profileA = profiles.insert(testProfile(0, isKids = false))
        profileB = profiles.insert(testProfile(0, isKids = false))
        coEvery { tmdb.getMovieDetails(any()) } answers { movieDetails(firstArg(), "PG") }
    }

    @Test
    fun `existing titles keep their row, addedAt, progress and last used stream`() = runTest {
        val saved1 = saved(profileA, 1, addedAt = 1_000, title = "My Title")
        val saved2 = saved(profileA, 2, addedAt = 2_000, title = "Other")
        library.insertAll(listOf(saved1, saved2))
        val inProgress = progress(profileA, 1, positionMs = 40_000, dismissed = true)
        val completed = progress(profileA, 2, positionMs = 100_000)
        progress.upsertAll(listOf(inProgress, completed))
        val stream = lastUsed(profileA, 1)
        lastUsed.upsert(stream)
        listIds(1, 2, 3, 4)

        val result = useCase(profileA, listUrl, LibraryTarget.MOVIES)

        assertEquals(2, result.added)
        assertEquals(2, result.alreadyInLibrary)
        assertEquals(0, result.failed)
        val rows = library.getAll(profileA)
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.tmdbId }, "each title exactly once")
        assertEquals(listOf(saved1, saved2), rows.take(2), "existing rows untouched, original addedAt kept")
        assertEquals(listOf(NOW, NOW), rows.drop(2).map { it.addedAtMillis })
        assertEquals(listOf(inProgress, completed), progress.rows.value)
        assertEquals(listOf(stream), lastUsed.rows.value)
        coVerify(exactly = 0) { tmdb.getMovieDetails(1) }
        coVerify(exactly = 0) { tmdb.getMovieDetails(2) }
    }

    @Test
    fun `a title repeated within one list is fetched and imported once`() = runTest {
        listIds(5, 5, 6, 5, 6)

        val result = useCase(profileA, listUrl, LibraryTarget.MOVIES)

        assertEquals(LibraryImportResult(added = 2, alreadyInLibrary = 0, skippedOtherType = 0, blockedByAgeRestriction = 0, failed = 0), result)
        assertEquals(listOf(5, 6), library.getAll(profileA).map { it.tmdbId })
        coVerify(exactly = 1) { tmdb.getMovieDetails(5) }
        coVerify(exactly = 1) { tmdb.getMovieDetails(6) }
    }

    @Test
    fun `importing into profile A never affects profile B`() = runTest {
        val savedB = saved(profileB, 1, addedAt = 500, title = "B's copy")
        library.insert(savedB)
        val progressB = progress(profileB, 1, positionMs = 30_000)
        progress.upsert(progressB)
        val streamB = lastUsed(profileB, 1)
        lastUsed.upsert(streamB)
        listIds(1, 3)

        val result = useCase(profileA, listUrl, LibraryTarget.MOVIES)

        assertEquals(2, result.added, "B's copy doesn't count as already in A's library")
        assertEquals(0, result.alreadyInLibrary)
        assertEquals(listOf(1, 3), library.getAll(profileA).map { it.tmdbId })
        assertEquals(listOf(savedB), library.getAll(profileB))
        assertEquals(listOf(progressB), progress.rows.value)
        assertEquals(listOf(streamB), lastUsed.rows.value)
    }

    @Test
    fun `re-import of an already saved list needs no TMDB and reports every title as already in library`() = runTest {
        library.insertAll(listOf(saved(profileA, 1, 1_000, "One"), saved(profileA, 2, 1_000, "Two")))
        coEvery { tmdb.getMovieDetails(any()) } throws IOException("TMDB down")
        listIds(1, 2, 1)

        val result = useCase(profileA, listUrl, LibraryTarget.MOVIES)

        assertEquals(LibraryImportResult(added = 0, alreadyInLibrary = 2, skippedOtherType = 0, blockedByAgeRestriction = 0, failed = 0), result)
        coVerify(exactly = 0) { tmdb.getMovieDetails(any()) }
    }

    @Test
    fun `the import cap counts only new titles`() = runTest {
        val cap = ImportMdbListUseCase.MAX_IMPORT_ITEMS
        library.insertAll((1..cap).map { saved(profileA, it, 1_000, "Saved $it") })
        listIds(*(1..cap + 10).toList().toIntArray())

        val result = useCase(profileA, listUrl, LibraryTarget.MOVIES)

        assertEquals(10, result.added)
        assertEquals(cap, result.alreadyInLibrary)
        assertEquals(cap + 10, library.getAll(profileA).size)
    }

    private fun listIds(vararg ids: Int) {
        coEvery { mdbList.fetchList(listUrl) } returns ids.map { MdbListItem(tmdbId = it, mediaTypeRaw = "movie") }
    }

    private fun saved(profileId: Long, tmdbId: Int, addedAt: Long, title: String) = LibraryItemEntity(
        profileId = profileId,
        tmdbId = tmdbId,
        mediaType = MediaType.MOVIE,
        title = title,
        posterPath = "/custom$tmdbId.jpg",
        certification = "PG",
        genreIds = "18",
        addedAtMillis = addedAt,
    )

    private fun progress(profileId: Long, tmdbId: Int, positionMs: Long, dismissed: Boolean = false) = WatchProgressEntity(
        profileId = profileId,
        tmdbId = tmdbId,
        mediaType = MediaType.MOVIE,
        title = "Movie $tmdbId",
        episodeTitle = null,
        posterPath = null,
        backdropPath = null,
        positionMs = positionMs,
        durationMs = 100_000,
        updatedAtMillis = 3_000,
        dismissedFromContinueWatching = dismissed,
    )

    private fun lastUsed(profileId: Long, tmdbId: Int) = LastUsedStreamEntity(
        profileId = profileId,
        tmdbId = tmdbId,
        mediaType = MediaType.MOVIE,
        addonUrl = "https://addon",
        streamUrl = "https://stream/$tmdbId",
        label = "Addon · 4k",
        description = "",
        updatedAtMillis = 3_000,
    )

    private companion object {
        const val NOW = 42_000L
    }
}
