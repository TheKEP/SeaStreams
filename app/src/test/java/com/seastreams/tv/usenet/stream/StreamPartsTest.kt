package com.seastreams.tv.usenet.stream

import com.seastreams.tv.usenet.nntp.FetchPriority
import com.seastreams.tv.usenet.nntp.SegmentFetch
import com.seastreams.tv.usenet.nntp.SegmentSource
import com.seastreams.tv.usenet.nzb.NzbFile
import com.seastreams.tv.usenet.nzb.NzbSegment
import com.seastreams.tv.usenet.yenc.DecodedSegment
import com.seastreams.tv.usenet.yenc.SegmentBufferPool
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** An in-memory segment source over [data] split at [sizes]; [hold] leaves fetches pending. */
internal class PartsSource(private val data: ByteArray, private val sizes: List<Int>) : SegmentSource {
    val pool = SegmentBufferPool()
    val fetches = ConcurrentHashMap<String, MutableList<SegmentFetch>>()
    val calls = AtomicInteger()
    @Volatile var hold = false

    val file = NzbFile("\"f.bin\"", "p", emptyList(), sizes.indices.map { NzbSegment(it + 1, sizes[it].toLong(), "s${it + 1}@t") })

    fun segment(index: Int): DecodedSegment {
        val begin = sizes.take(index).sum()
        val buffer = pool.acquire(sizes[index])
        System.arraycopy(data, begin, buffer, 0, sizes[index])
        return DecodedSegment(buffer, sizes[index], begin.toLong(), data.size.toLong(), "f.bin", index + 1, sizes.size, pool)
    }

    override fun fetch(messageId: String, part: Int, priority: FetchPriority): SegmentFetch {
        calls.incrementAndGet()
        val fetch = SegmentFetch()
        fetches.getOrPut(messageId) { java.util.Collections.synchronizedList(ArrayList()) } += fetch
        if (!hold) fetch.complete(segment(part - 1))
        return fetch
    }
}

class StreamPartsTest {

    @Test
    fun `locator converges on uneven parts and locks a proven grid`() {
        val sizes = listOf(1000, 1000, 700, 1000, 1300, 1000, 400, 1000, 1000, 600)
        val locator = SegmentLocator(sizes.size, sizes.sum().toLong())
        var begin = 0L
        val begins = sizes.map { size -> begin.also { begin += size } }
        repeat(sizes.size) { target ->
            for (offset in listOf(begins[target], begins[target] + sizes[target] - 1)) {
                var low = 0
                var high = sizes.lastIndex
                var index = locator.guess(offset, low, high)
                var probes = 0
                while (true) {
                    probes++
                    locator.record(index, begins[index], sizes[index])
                    when {
                        offset < begins[index] -> high = index - 1
                        offset >= begins[index] + sizes[index] -> low = index + 1
                        else -> break
                    }
                    index = locator.guess(offset, low, high)
                }
                assertEquals(target, index)
                assertTrue(probes <= sizes.size)
            }
        }
        val even = SegmentLocator(10, 9_500)
        even.record(3, 3_000, 1_000)
        assertEquals(1_000L, even.lockedPartSize)
        assertEquals(7, even.guess(7_500))
        even.record(5, 4_900, 1_000)
        assertNull(even.lockedPartSize, "a contradiction unlocks the grid")
        val sibling = SegmentLocator(10, 9_500).apply { adoptGrid(1_000) }
        assertEquals(9, sibling.guess(9_400))
    }

    @Test
    fun `random reads over uneven parts are byte-exact`() {
        val sizes = listOf(997, 1000, 1000, 640, 1000, 1000, 1000, 1500, 1000, 333)
        val data = Random(11).nextBytes(sizes.sum())
        val source = PartsSource(data, sizes)
        val file = UsenetFile(source.file, source, SegmentArena(1L shl 20), readAheadSegments = 3)
        file.open()
        assertEquals(data.size.toLong(), file.size)
        val random = Random(12)
        repeat(300) {
            val position = random.nextLong(data.size.toLong())
            val buffer = ByteArray(random.nextInt(1, 2_000))
            val read = file.read(position, buffer, 0, buffer.size)
            assertTrue(read > 0)
            assertArrayEquals(data.copyOfRange(position.toInt(), position.toInt() + read), buffer.copyOf(read))
        }
        assertEquals(-1, file.read(data.size.toLong(), ByteArray(1), 0, 1))
        file.close()
    }

    @Test
    fun `read-ahead fills the window, keeps the head and tail pins and cancels the rest on seek`() {
        val source = PartsSource(ByteArray(100 * 10), List(100) { 10 }).apply { hold = true }
        val window = ReadAheadWindow(100, windowSegments = 4, headPins = 2, tailFrom = 98) { index ->
            source.fetch("s${index + 1}@t", index + 1, FetchPriority.HIGH)
        }
        window.prefetchTail()
        window.request(0)
        window.request(1)
        window.advance(1)
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 98, 99), window.activeIndices())
        val old = (2..5).map { source.fetches.getValue("s${it + 1}@t").last() }
        window.advance(50)
        assertTrue(old.all { it.cancelled }, "the old window was cancelled at once")
        assertEquals(setOf(0, 1, 51, 52, 53, 54, 98, 99), window.activeIndices())
        assertFalse(source.fetches.getValue("s1@t").last().cancelled, "head pinned")
        assertFalse(source.fetches.getValue("s100@t").last().cancelled, "tail pinned")
        window.close()
        assertTrue(source.fetches.getValue("s52@t").last().cancelled)
        assertEquals(4, ReadAheadWindow.windowFor(4, 750_000, 1L shl 40))
        assertEquals(2, ReadAheadWindow.windowFor(64, 1_000_000, 20_000_000), "heap clamp: a tenth of 20 MB")
        assertEquals(93, ReadAheadWindow.tailFrom(100, 750_000))
    }

    @Test
    fun `an interrupted reader gets InterruptedIOException`() {
        val source = PartsSource(ByteArray(10_000_000), List(10) { 1_000_000 })
        val file = UsenetFile(source.file, source, SegmentArena(32L shl 20), readAheadSegments = 2)
        file.open()
        source.hold = true
        val error = AtomicReference<Throwable>()
        val reader = Thread {
            try {
                file.read(3_500_000, ByteArray(10), 0, 10)
            } catch (e: Throwable) {
                error.set(e)
            }
        }
        reader.start()
        while (source.fetches["s4@t"].isNullOrEmpty()) Thread.sleep(1)
        Thread.sleep(20)
        reader.interrupt()
        reader.join(2_000)
        assertTrue(error.get() is InterruptedIOException, "got ${error.get()}")
        file.close()
    }

    @Test
    fun `arena is a byte-bounded LRU that releases evicted segments`() {
        val source = PartsSource(ByteArray(4_000), List(4) { 1_000 })
        val arena = SegmentArena(capacityBytes = 2_500)
        val segments = (0 until 4).map { source.segment(it) }
        arena.put("a", segments[0])
        arena.put("b", segments[1])
        assertNotNull(arena.get("a"))
        arena.put("c", segments[2])
        assertNull(arena.get("b"), "least recently used goes first")
        assertTrue(segments[1].released)
        assertNotNull(arena.get("a"))
        arena.put("d", segments[3])
        assertEquals(2, arena.count())
        assertTrue(arena.sizeBytes() <= 2_500)
        arena.clear()
        assertTrue(segments[0].released && segments[3].released)
        assertEquals(32L * 1024 * 1024, SegmentArena.sizeFor(10, 1L shl 40))
        assertEquals(36L * 1024 * 1024, SegmentArena.sizeFor(24, 1L shl 40))
        assertEquals(64L * 1024 * 1024, SegmentArena.sizeFor(256, 1L shl 40))
        assertEquals(512L * 1024 * 1024 / 10, SegmentArena.sizeFor(256, 512L * 1024 * 1024), "≤ a tenth of a 512 MB TV heap")
    }

    @Test
    fun `a file with no articles is refused`() {
        val empty = NzbFile("x", "p", emptyList(), emptyList())
        assertThrows<IllegalArgumentException> { UsenetFile(empty, PartsSource(ByteArray(0), emptyList()), SegmentArena(1), 1) }
    }

    @Test
    fun `a duplicate download is released and the held copy kept`() {
        val source = PartsSource(ByteArray(2_000), List(2) { 1_000 })
        val arena = SegmentArena(capacityBytes = 10_000)
        val first = source.segment(0)
        val again = source.segment(0)
        assertSame(first, arena.put("a", first))
        assertSame(first, arena.put("a", first), "the same segment handed in twice is kept once")
        assertSame(first, arena.put("a", again))
        assertTrue(again.released, "the duplicate's buffer went back to the pool")
        assertFalse(first.released)
        assertEquals(1_000L, arena.sizeBytes())
        arena.clear()
        arena.put("a", first)
        assertEquals(0, arena.count(), "a released segment is never re-added")
    }
}
