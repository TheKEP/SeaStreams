package com.seastreams.tv.data.debrid.library

import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.api.DebridFile
import com.seastreams.tv.data.debrid.api.DebridItem
import com.seastreams.tv.data.debrid.stream.DebridStreamRegistry
import com.seastreams.tv.data.usenet.newznab.UsenetSearchTarget
import com.seastreams.tv.domain.playback.LastUsedStream
import com.seastreams.tv.domain.playback.lastUsedIndex
import com.seastreams.tv.domain.playback.streamUrlFingerprint
import com.seastreams.tv.testutil.DebridLibraryRig
import com.seastreams.tv.testutil.FakeLibraryClient
import com.seastreams.tv.testutil.VirtualClock
import com.seastreams.tv.testutil.debridItem
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** PRD §14 Debrid search: provider order, per-provider timeout and failure isolation, file-list budget, stable URLs. */
class DebridLibrarySearchTest {

    private val movie = UsenetSearchTarget.Movie("tt1375666", "Inception", 2010)

    private val rd = FakeLibraryClient(DebridServiceKind.REAL_DEBRID, listOf(debridItem("t:1", "Inception.2010.1080p.mkv")))
    private val tb = FakeLibraryClient(DebridServiceKind.TORBOX, listOf(debridItem("torrents:9", "Inception.2010.2160p.mkv"), debridItem("torrents:8", "Tenet.2020.mkv")))

    private suspend fun TestScope.rig(vararg clients: FakeLibraryClient): DebridLibraryRig =
        DebridLibraryRig(VirtualClock(testScheduler)).apply {
            clients.forEach { add(it) }
            enable()
        }

    @Test
    fun `results follow provider order, with the service name on every stream`() = runTest {
        rd.libraryDelayMillis = 3_000 // the first provider is the slower one
        val result = rig(rd, tb).search.search(movie)

        assertEquals(listOf("Real-Debrid · 1080p", "TorBox · 2160p"), result.streams.map { it.label })
        assertEquals(listOf("Real-Debrid", "TorBox"), result.streams.map { it.hints.indexer })
        assertTrue(result.streams.all { it.addonUrl == DebridLibrarySearch.DEBRID_ADDON_URL && DebridStreamRegistry.isDebrid(it.url) })
        assertEquals("Inception.2010.2160p.mkv", result.streams[1].hints.filename)
        assertFalse(result.failed)
    }

    @Test
    fun `a provider slower than 20 seconds only loses its own results`() = runTest {
        rd.libraryDelayMillis = 60_000
        val result = rig(rd, tb).search.search(movie)

        assertEquals(listOf("TorBox · 2160p"), result.streams.map { it.label })
        assertEquals(DebridLibrarySearch.TIMEOUT_MILLIS, currentTime)
        assertFalse(result.failed)
    }

    @Test
    fun `a failing provider drops only its results, and all failing marks the search failed`() = runTest {
        rd.libraryError = IOException("unreachable")
        val rig = rig(rd, tb)
        assertEquals(listOf("TorBox · 2160p"), rig.search.search(movie).streams.map { it.label })

        tb.libraryError = IllegalStateException("garbled")
        rig.cache.clear()
        val none = rig.search.search(movie)
        assertTrue(none.streams.isEmpty())
        assertTrue(none.failed)
    }

    @Test
    fun `titles not in the account show nothing, and disabled providers or Debrid off are not searched`() = runTest {
        val rig = rig(rd, tb)
        assertTrue(rig.search.search(UsenetSearchTarget.Movie(null, "Dune", 2021)).streams.isEmpty())

        rig.repository.setEnabled(rig.repository.enabledCredentials().first { it.kind == DebridServiceKind.TORBOX }.providerId, false)
        rig.cache.clear()
        assertEquals(listOf("Real-Debrid · 1080p"), rig.search.search(movie).streams.map { it.label })
        assertEquals(1, tb.libraryCalls)

        rig.settings.setEnabled(false)
        assertFalse(rig.search.configured())
    }

    @Test
    fun `at most 8 item file lists are read per provider per lookup`() = runTest {
        val packs = (1..12).map { DebridItem("t:$it", "Inception.2010.Pack$it", null, files = null) }
        val files = packs.associate { it.id to listOf(DebridFile("f", "Inception.2010.1080p.mkv", 4_000_000_000)) }
        val big = FakeLibraryClient(DebridServiceKind.REAL_DEBRID, packs, files)
        val result = rig(big).search.search(movie)

        assertEquals(DebridLibrarySearch.MAX_FILE_LISTS, big.fileCalls)
        assertEquals(DebridLibrarySearch.MAX_FILE_LISTS, result.streams.size)
    }

    @Test
    fun `the listing is cached for 60 seconds across lookups`() = runTest {
        val rig = rig(rd)
        rig.search.search(movie)
        rig.search.search(UsenetSearchTarget.Movie(null, "Tenet", 2020))
        assertEquals(1, rd.libraryCalls)
    }

    @Test
    fun `the same file found by a later search has the same URL, so LAST USED matches it exactly`() = runTest {
        val rig = rig(rd, tb)
        val first = rig.search.search(movie).streams
        val saved = first[1].let { LastUsedStream(it.addonUrl, streamUrlFingerprint(it.url), it.label, it.description) }

        rig.registry.clear() // e.g. after a restart: the next search registers it again
        rig.cache.clear()
        rd.items = listOf(debridItem("t:2", "Inception.2010.720p.mkv")) + rd.items // the library changed meanwhile
        val second = rig.search.search(movie).streams

        assertEquals(first[1].url, second[2].url)
        assertEquals(2, lastUsedIndex(second, saved))
        assertTrue(second.none { it.url.contains("key-") }, "no credential in a stream URL")
    }
}
