package com.seastreams.tv.data.debrid.library

import com.seastreams.tv.data.debrid.api.DebridFile
import com.seastreams.tv.data.debrid.api.DebridItem
import com.seastreams.tv.testutil.VirtualClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** ARCHITECTURE §12 `DebridLibraryCache`: 60 s per provider, single-flight, per credential generation, cleared on demand. */
class DebridLibraryCacheTest {

    private var loads = 0

    private suspend fun listing(name: String = "Film"): List<DebridItem> {
        loads++
        delay(1_000) // the service takes a second to answer
        return listOf(DebridItem("1", "$name ${loads}"))
    }

    @Test
    fun `a listing is reused for 60 seconds, then fetched again`() = runTest {
        val cache = DebridLibraryCache(VirtualClock(testScheduler))

        val first = cache.library(1, 0) { listing() }
        advanceTimeBy(DebridLibraryCache.TTL_MILLIS - 2_000)
        assertEquals(first, cache.library(1, 0) { listing() })
        assertEquals(1, loads)

        advanceTimeBy(2_000)
        cache.library(1, 0) { listing() }
        assertEquals(2, loads)
    }

    @Test
    fun `concurrent lookups share one fetch`() = runTest {
        val cache = DebridLibraryCache(VirtualClock(testScheduler))

        val results = (1..5).map { async { cache.library(1, 0) { listing() } } }.awaitAll()

        assertEquals(1, loads)
        assertEquals(1, results.distinct().size)
    }

    @Test
    fun `a failed fetch is shared by its waiters but never cached`() = runTest {
        val cache = DebridLibraryCache(VirtualClock(testScheduler))
        val failing = suspend {
            loads++
            delay(1_000)
            throw IOException("unreachable")
        }

        val waiters = (1..3).map { async { runCatching { cache.library(1, 0) { failing() } } } }.awaitAll()
        assertEquals(1, loads)
        waiters.forEach { assertThrows<IOException> { it.getOrThrow() } }

        cache.library(1, 0) { listing() }
        assertEquals(2, loads)
    }

    @Test
    fun `a new credential generation, invalidate, retain and clear all drop the listing`() = runTest {
        val cache = DebridLibraryCache(VirtualClock(testScheduler))
        cache.library(1, 0) { listing() }
        cache.library(2, 0) { listing() }

        cache.library(1, 1) { listing() } // key replaced: generation 1
        assertEquals(3, loads)

        cache.invalidate(1)
        cache.library(1, 1) { listing() }
        assertEquals(4, loads)

        cache.retain(setOf(1)) // provider 2 removed
        cache.library(2, 0) { listing() }
        assertEquals(5, loads)

        cache.clear()
        cache.library(1, 1) { listing() }
        cache.library(2, 0) { listing() }
        assertEquals(7, loads)
    }

    @Test
    fun `file lists are cached per item for 60 seconds and per generation`() = runTest {
        val cache = DebridLibraryCache(VirtualClock(testScheduler))
        var reads = 0
        val read = suspend { reads++; listOf(DebridFile("f", "a.mkv", 1)) }

        cache.files(1, 0, "item") { read() }
        cache.files(1, 0, "item") { read() }
        assertEquals(1, reads)
        cache.files(1, 1, "item") { read() }
        assertEquals(2, reads)
        advanceTimeBy(DebridLibraryCache.TTL_MILLIS)
        cache.files(1, 1, "item") { read() }
        assertEquals(3, reads)
    }
}
