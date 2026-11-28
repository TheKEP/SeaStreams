package com.seastreams.tv.data.library

import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.TmdbApiKeyMissingException
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.core.network.TmdbKeyword
import com.seastreams.tv.core.network.TmdbMovieKeywords
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.FakeLibraryDao
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Keyword lookups for Library movies saved before keywords were stored (`Christmas` genre, PRD §7). */
class LibraryKeywordBackfillTest {

    private val dao = FakeLibraryDao()
    private val tmdb = mockk<TmdbApiService>()
    private val scope = CoroutineScope(SupervisorJob())
    private val backfill = LibraryKeywordBackfill(dao, MediaHydrator(Lazy { tmdb }, TmdbImageUrls(ApiEndpoints())), scope)

    private fun save(tmdbId: Int, type: MediaType = MediaType.MOVIE, keywordIds: String? = null, profileId: Long = 1) = runBlocking {
        dao.insert(LibraryItemEntity(profileId, tmdbId, type, "Title $tmdbId", null, "PG", "35", 0, keywordIds))
    }

    private fun keywords(profileId: Long = 1) = dao.rows.value.filter { it.profileId == profileId }.associate { it.tmdbId to it.keywordIds }

    private suspend fun awaitLaunched() = scope.coroutineContext.job.children.toList().forEach { it.join() }

    @Test
    fun `only movies not looked up yet are fetched, and failures stay unknown`(): Unit = runBlocking {
        save(1)
        save(2, keywordIds = "9715")
        save(3, type = MediaType.TV, keywordIds = "")
        save(4)
        coEvery { tmdb.getMovieKeywords(1) } returns TmdbMovieKeywords(listOf(TmdbKeyword(207317, "christmas"), TmdbKeyword(9715)))
        coEvery { tmdb.getMovieKeywords(4) } throws IOException("offline")

        assertTrue(backfill.run(1))

        assertEquals(mapOf(1 to "207317,9715", 2 to "9715", 3 to "", 4 to null), keywords())
        coVerify(exactly = 0) { tmdb.getMovieKeywords(2) }
        coVerify(exactly = 0) { tmdb.getMovieKeywords(3) }
    }

    @Test
    fun `results are written in chunks, not per title`(): Unit = runBlocking {
        val count = LibraryKeywordBackfill.CHUNK_SIZE * 2 + 1
        (1..count).forEach { save(it) }
        coEvery { tmdb.getMovieKeywords(any()) } returns TmdbMovieKeywords(emptyList())

        assertTrue(backfill.run(1))

        assertEquals(3, dao.keywordBatches)
        assertTrue(keywords().values.all { it == "" })
    }

    @Test
    fun `a chunk's lookups run concurrently, never more than WORKERS at once`(): Unit = runBlocking {
        val workers = LibraryKeywordBackfill.WORKERS
        (1..LibraryKeywordBackfill.CHUNK_SIZE).forEach { save(it) }
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        // Holds every lookup until WORKERS are in flight at once: a sequential backfill would time out here.
        val allSlotsBusy = CompletableDeferred<Unit>()
        coEvery { tmdb.getMovieKeywords(any()) } coAnswers {
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now, ::maxOf)
            if (now >= workers) allSlotsBusy.complete(Unit)
            allSlotsBusy.await()
            inFlight.decrementAndGet()
            TmdbMovieKeywords(listOf(TmdbKeyword(1441)))
        }

        assertTrue(withTimeout(10_000) { backfill.run(1) })

        assertEquals(workers, peak.get())
        assertEquals(1, dao.keywordBatches)
        assertTrue(keywords().values.all { it == "1441" })
    }

    @Test
    fun `a missing API key stops quietly, keeping what the chunk already fetched`(): Unit = runBlocking {
        save(1)
        save(2)
        coEvery { tmdb.getMovieKeywords(1) } returns TmdbMovieKeywords(listOf(TmdbKeyword(1441)))
        coEvery { tmdb.getMovieKeywords(2) } throws TmdbApiKeyMissingException()

        assertFalse(backfill.run(1))

        assertEquals(mapOf(1 to "1441", 2 to null), keywords())
    }

    @Test
    fun `ensure runs once per profile per process`(): Unit = runBlocking {
        save(1)
        coEvery { tmdb.getMovieKeywords(1) } returns TmdbMovieKeywords(listOf(TmdbKeyword(193048)))

        repeat(3) {
            backfill.ensure(1)
            awaitLaunched()
        }

        coVerify(exactly = 1) { tmdb.getMovieKeywords(1) }
        assertEquals(mapOf(1 to "193048"), keywords())
    }

    @Test
    fun `an interrupted run is retried by the next trigger`(): Unit = runBlocking {
        save(1)
        coEvery { tmdb.getMovieKeywords(1) } throws TmdbApiKeyMissingException()
        backfill.ensure(1)
        awaitLaunched()

        coEvery { tmdb.getMovieKeywords(1) } returns TmdbMovieKeywords(listOf(TmdbKeyword(207317)))
        backfill.ensure(1)
        awaitLaunched()

        assertEquals(mapOf(1 to "207317"), keywords())
    }

    @Test
    fun `following the active profile backfills each profile it switches to, once`(): Unit = runBlocking {
        save(1, profileId = 1)
        save(2, profileId = 2)
        coEvery { tmdb.getMovieKeywords(any()) } returns TmdbMovieKeywords(listOf(TmdbKeyword(1441)))

        backfill.followActiveProfile(flowOf(null, 1L, 2L, 1L))
        awaitLaunched()

        coVerify(exactly = 1) { tmdb.getMovieKeywords(1) }
        coVerify(exactly = 1) { tmdb.getMovieKeywords(2) }
        assertEquals(mapOf(1 to "1441"), keywords(1))
        assertEquals(mapOf(2 to "1441"), keywords(2))
    }
}
