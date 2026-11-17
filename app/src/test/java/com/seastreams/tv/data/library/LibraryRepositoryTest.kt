package com.seastreams.tv.data.library

import com.seastreams.tv.testutil.FakeLibraryDao
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.domain.model.MediaType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryTest {

    private val dao = FakeLibraryDao()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC)
    private val movie = testDetails(42)

    private fun TestScope.repository() = LibraryRepository(dao, clock, backgroundScope)

    @Test
    fun `adding a title twice stores it once and reports the duplicate`() = runTest {
        val repo = repository()

        assertEquals(LibraryAddResult.ADDED, repo.add(1, movie))
        assertEquals(LibraryAddResult.ALREADY_IN_LIBRARY, repo.add(1, movie))

        assertEquals(1, dao.rows.value.size)
        // The existence check short-circuits: the duplicate never even reaches the insert.
        assertEquals(1, dao.insertCalls)
    }

    @Test
    fun `concurrent adds (double press) never create a duplicate`() = runTest {
        val repo = repository()

        val results = List(5) { async { repo.add(1, movie) } }.awaitAll()

        assertEquals(1, results.count { it == LibraryAddResult.ADDED })
        assertEquals(4, results.count { it == LibraryAddResult.ALREADY_IN_LIBRARY })
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `an ignored insert (row appeared after the check) is reported as already in library`() = runTest {
        // Simulates another writer (e.g. a portal import) saving the title between check and insert.
        val racingDao = object : com.seastreams.tv.core.database.dao.LibraryDao by dao {
            override suspend fun contains(profileId: Long, tmdbId: Int, mediaType: MediaType) = false
        }
        dao.rows.value = listOf(entityFor(1))
        val repo = LibraryRepository(racingDao, clock, backgroundScope)

        assertEquals(LibraryAddResult.ALREADY_IN_LIBRARY, repo.add(1, movie))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `the same title can be saved by different profiles`() = runTest {
        val repo = repository()

        assertEquals(LibraryAddResult.ADDED, repo.add(1, movie))
        assertEquals(LibraryAddResult.ADDED, repo.add(2, movie))
        assertEquals(2, dao.rows.value.size)
    }

    @Test
    fun `saved row carries the title's metadata for the Library screen`() = runTest {
        repository().add(1, movie)

        val row = dao.rows.value.single()
        assertEquals("Title 42", row.title)
        assertEquals("/poster.jpg", row.posterPath)
        assertEquals("18,35", row.genreIds)
        assertEquals(clock.millis(), row.addedAtMillis)
    }

    @Test
    fun `observeContains follows add and remove`() = runTest {
        val repo = repository()
        assertFalse(repo.observeContains(1, movie.ref).first())

        repo.add(1, movie)
        assertTrue(repo.observeContains(1, movie.ref).first())

        repo.remove(1, movie.ref)
        assertFalse(repo.observeContains(1, movie.ref).first())
    }

    @Test
    fun `clear library waits out the 5 second undo window before deleting`() = runTest {
        val repo = repository()
        repo.add(1, movie)

        repo.scheduleClear(1)
        assertEquals(clock.millis() + 5_000, repo.pendingClear.value?.deadlineMillis)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, dao.rows.value.size)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(dao.rows.value.isEmpty())
        assertNull(repo.pendingClear.value)
    }

    @Test
    fun `undo within the window keeps the library`() = runTest {
        val repo = repository()
        repo.add(1, movie)
        repo.scheduleClear(1)
        advanceTimeBy(3_000)
        runCurrent()

        assertTrue(repo.undoClear())
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, dao.rows.value.size)
        assertNull(repo.pendingClear.value)
        assertFalse(repo.undoClear())
    }

    private fun entityFor(profileId: Long) = com.seastreams.tv.core.database.entity.LibraryItemEntity(
        profileId = profileId,
        tmdbId = movie.media.tmdbId,
        mediaType = movie.media.mediaType,
        title = movie.media.title,
        posterPath = null,
        certification = null,
        genreIds = "",
        addedAtMillis = 0,
    )
}
