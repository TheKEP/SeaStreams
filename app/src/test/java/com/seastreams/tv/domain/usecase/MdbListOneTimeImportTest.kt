package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.SeaStreamsDatabase
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.FakeLibraryDao
import com.seastreams.tv.testutil.FakeProfileDao
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.testProfile
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock

/**
 * PRD §8: importing an MDBList list is a one-time batch insert into the Library. Nothing about the
 * list is kept, so there is nothing to track, re-fetch or sync afterwards.
 */
class MdbListOneTimeImportTest {

    private val listUrl = "https://mdblist.com/lists/owner/favourites"
    private val mdbList = mockk<MdbListApiService>()
    private val tmdb = mockk<TmdbApiService>()
    private val profiles = FakeProfileDao()
    private val library = FakeLibraryDao()
    private val useCase = ImportMdbListUseCase(
        mdbList,
        MediaHydrator(Lazy { tmdb }, TmdbImageUrls(ApiEndpoints())),
        profiles,
        library,
        AgeRestrictionEngine(),
        Clock.systemUTC(),
    )
    private var adultId = 0L
    private var kidsId = 0L

    @BeforeEach
    fun setUp() = runTest {
        adultId = profiles.insert(testProfile(0, isKids = false))
        kidsId = profiles.insert(testProfile(0, isKids = true))
        // Movies 1..6 (6 listed twice) plus one show; ids divisible by 3 are rated R, the rest G.
        coEvery { mdbList.fetchList(listUrl) } returns
            (1..6).map { MdbListItem(tmdbId = it, mediaTypeRaw = "movie") } +
            MdbListItem(tmdbId = 6, mediaTypeRaw = "movie") +
            MdbListItem(tmdbId = 50, mediaTypeRaw = "show")
        coEvery { tmdb.getMovieDetails(any()) } answers {
            val id = firstArg<Int>()
            movieDetails(id, if (id % 3 == 0) "R" else "G")
        }
    }

    @Test
    fun `import adds each title once and a repeat import only skips duplicates`() = runTest {
        val first = useCase(adultId, listUrl, LibraryTarget.MOVIES)

        assertEquals(6, first.added)
        assertEquals(0, first.alreadyInLibrary)
        assertEquals(1, first.skippedOtherType)
        assertEquals((1..6).toList(), library.getAll(adultId).map { it.tmdbId })

        val second = useCase(adultId, "mdblist.com/lists/owner/favourites/json", LibraryTarget.MOVIES)

        assertEquals(0, second.added)
        assertEquals(6, second.alreadyInLibrary)
        assertEquals((1..6).toList(), library.getAll(adultId).map { it.tmdbId }, "no duplicates after re-import")
        assertTrue(library.getAll(adultId).all { it.mediaType == MediaType.MOVIE })
    }

    @Test
    fun `the list is fetched exactly once per import and never again afterwards`() = runTest {
        useCase(adultId, listUrl, LibraryTarget.MOVIES)

        coVerify(exactly = 1) { mdbList.fetchList(listUrl) }
        confirmVerified(mdbList)
        coVerify(exactly = 0) { tmdb.getExternalIds(any(), any()) }
        // Library rows hold the titles only: no list URL or list identity is written anywhere.
        assertTrue(library.getAll(adultId).none { row -> listOf(row.title, row.genreIds, row.posterPath.orEmpty()).any { "mdblist" in it } })
    }

    @Test
    fun `kids import respects the profile's rating ceiling`() = runTest {
        val result = useCase(kidsId, listUrl, LibraryTarget.MOVIES)

        assertEquals(listOf(1, 2, 4, 5), library.getAll(kidsId).map { it.tmdbId })
        assertEquals(2, result.blockedByAgeRestriction)
        assertTrue(library.getAll(adultId).isEmpty())
    }

    @Test
    fun `the database has no table that could track imported lists`() {
        // Room's exported schema for the current version (module dir is the unit-test working dir).
        val schema = File("schemas/${SeaStreamsDatabase::class.java.name}/$DB_VERSION.json")
        val database = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
        val tables = database.getValue("entities").jsonArray.map { it.jsonObject.getValue("tableName").jsonPrimitive.content }

        assertEquals(listOf("profiles", "library_items", "watch_progress", "addons", "cache_entries"), tables)
    }

    private companion object {
        /** `SeaStreamsDatabase` version that dropped the `custom_catalogs` table. */
        const val DB_VERSION = 2
    }
}
