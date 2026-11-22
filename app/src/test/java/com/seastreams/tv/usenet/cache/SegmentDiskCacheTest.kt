package com.seastreams.tv.usenet.cache

import com.seastreams.tv.core.cache.CacheDirProvider
import com.seastreams.tv.testutil.FakeNntpServer
import com.seastreams.tv.usenet.nntp.DefaultSocketOpener
import com.seastreams.tv.usenet.nntp.FetchPriority
import com.seastreams.tv.usenet.nntp.NntpEngine
import com.seastreams.tv.usenet.nntp.NntpEngineSettings
import com.seastreams.tv.usenet.stream.SegmentArena
import com.seastreams.tv.usenet.stream.UsenetFile
import com.seastreams.tv.usenet.yenc.DecodedSegment
import com.seastreams.tv.usenet.yenc.SegmentBufferPool
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class SegmentDiskCacheTest {
    @TempDir
    lateinit var cacheDir: File

    private val pool = SegmentBufferPool()
    /** Free space before the cache wrote anything; like a real disk, what the cache writes is no longer free. */
    private var free = 100L * GB
    private val inline = Executor { it.run() }

    private fun cache(writer: Executor = inline, maxPending: Int = 16) =
        SegmentDiskCache(CacheDirProvider { cacheDir }, { free - dirBytes() }, writer, maxPending)

    private fun segment(size: Int, seed: Int = size): DecodedSegment {
        val data = Random(seed).nextBytes(size)
        return DecodedSegment(data, size, 0, size.toLong(), "n.bin", 1, 1)
    }

    private fun dirBytes() = File(cacheDir, SegmentDiskCache.DIR).listFiles().orEmpty().sumOf { it.length() }

    @Test
    fun `round trips a segment through a file named by SHA-1 of the message ID`() {
        val cache = cache()
        val original = segment(5_000)
        cache.putAsync("<id>@x", original)
        val files = File(cacheDir, SegmentDiskCache.DIR).listFiles().orEmpty()
        assertEquals(listOf(SegmentDiskCache.keyOf("<id>@x")), files.map { it.name })
        val back = checkNotNull(cache.get("<id>@x", pool))
        assertArrayEquals(original.toByteArray(), back.toByteArray())
        assertEquals("n.bin", back.name)
        assertNull(cache.get("<id>@x", pool, expectedPart = 2), "a part mismatch is a miss")
        assertNull(cache.get("other@x", pool))
    }

    @Test
    fun `never exceeds the profile cap`() {
        val cache = cache().apply { profileCapBytes = 50_000 }
        repeat(40) { cache.putAsync("m$it", segment(4_000, it)) }
        assertTrue(dirBytes() <= 50_000, "dir holds ${dirBytes()}")
        assertTrue(cache.indexedBytes() <= 50_000)
        assertNull(cache.get("m0", pool), "oldest evicted")
        assertNotNull(cache.get("m39", pool))
    }

    @Test
    fun `the free-space guard keeps 2 GB free and stays under 25 percent of usable space`() {
        val cache = cache().apply { profileCapBytes = 4L * 1024 * MB }
        free = SegmentDiskCache.MIN_FREE_BYTES + 30_000
        assertEquals(30_000L, cache.capBytes(), "whatever leaves 2 GB free")
        repeat(20) { cache.putAsync("g$it", segment(4_000, it)) }
        assertTrue(dirBytes() <= 30_000)
        free = 8L * GB
        assertTrue(cache.capBytes() in 2L * GB..2L * GB + 30_000, "25% of the usable 8 GB")
        free = 100L * GB
        assertEquals(4L * 1024 * MB, cache.capBytes(), "the profile cap")
        cache.profileCapBytes = 0
        cache.putAsync("off", segment(10))
        assertNull(cache.get("off", pool), "Off disables the cache")
    }

    @Test
    fun `writes are dropped when the writer falls behind`() {
        val queued = ArrayList<Runnable>()
        val cache = cache(writer = { queued += it }, maxPending = 2)
        repeat(5) { cache.putAsync("w$it", segment(100, it)) }
        assertEquals(2, queued.size)
        queued.forEach { it.run() }
        assertNotNull(cache.get("w0", pool))
        assertNull(cache.get("w4", pool))
    }

    @Test
    fun `clear empties the cache while a reader is busy, and an older in-flight write is discarded`() {
        val held = ArrayList<Runnable>()
        val cache = cache(writer = { held += it })
        repeat(10) { cache.putAsync("r$it", segment(20_000, it)) }
        held.toList().also { held.clear() }.forEach { it.run() }
        val stop = AtomicBoolean(false)
        val failures = AtomicInteger()
        val reader = Thread {
            while (!stop.get()) {
                repeat(10) {
                    try {
                        cache.get("r$it", pool)?.let { segment -> if (segment.length != 20_000) failures.incrementAndGet() }
                    } catch (e: Exception) {
                        failures.incrementAndGet()
                    }
                }
            }
        }
        reader.start()
        cache.putAsync("late", segment(1_000))
        Thread.sleep(30)
        cache.clear()
        held.forEach { it.run() } // the write queued before the clear lands afterwards
        Thread.sleep(30)
        stop.set(true)
        reader.join(2_000)
        assertEquals(0, failures.get())
        assertEquals(0L, cache.indexedBytes())
        assertEquals(0L, dirBytes())
        assertNull(cache.get("late", pool))
        assertNull(cache.get("r1", pool))
    }

    @Test
    fun `a restart re-indexes what is on disk`() {
        cache().apply { repeat(3) { putAsync("k$it", segment(1_000, it)) } }
        val reopened = cache()
        assertEquals(3_000L + 3 * HEADER_PLUS_NAME, reopened.indexedBytes())
        assertNotNull(reopened.get("k2", pool))
    }

    @Test
    fun `random reads through the engine are byte-exact and re-reads come from the disk cache`() {
        val server = FakeNntpServer()
        val data = Random(99).nextBytes(150_000)
        val file = server.addFile(data, partSize = 7_000)
        val disk = cache()
        val engine = NntpEngine(DefaultSocketOpener, { System.nanoTime() / 1_000_000 }, disk)
        try {
            engine.configure(listOf(server.endpoint(maxConnections = 3, pipelineDepth = 4)), NntpEngineSettings(readAheadSegments = 4))
            val stream = UsenetFile(file, engine.cachedSource, SegmentArena(64L * 1024 * 1024), readAheadSegments = 4, segmentTimeoutMs = 10_000)
            stream.open()
            assertEquals(data.size.toLong(), stream.size)
            val random = Random(5)
            repeat(200) {
                val position = random.nextLong(data.size.toLong())
                val buffer = ByteArray(random.nextInt(1, 20_000))
                val read = stream.read(position, buffer, 0, buffer.size)
                assertArrayEquals(data.copyOfRange(position.toInt(), position.toInt() + read), buffer.copyOf(read))
            }
            stream.close()
            val deadline = System.currentTimeMillis() + 10_000
            while (file.segments.any { disk.get(it.messageId, pool) == null } && System.currentTimeMillis() < deadline) Thread.sleep(10)
            val requested = file.segments.sumOf { server.bodyCount(it.messageId) }
            val again = UsenetFile(file, engine.cachedSource, SegmentArena(64L * 1024 * 1024), readAheadSegments = 4, segmentTimeoutMs = 10_000)
            val all = ByteArray(data.size)
            var at = 0
            while (at < all.size) at += again.read(at.toLong(), all, at, all.size - at)
            assertArrayEquals(data, all)
            again.close()
            val cached = file.segments.count { disk.get(it.messageId, pool) != null }
            assertEquals(file.segments.size, cached)
            assertEquals(requested, file.segments.sumOf { server.bodyCount(it.messageId) }, "the second pass hit no provider")
            engine.fetch(file.segments[0].messageId, 1, FetchPriority.HIGH).await(5_000)
        } finally {
            engine.close()
            server.close()
        }
    }

    private companion object {
        const val MB = 1024L * 1024
        const val GB = 1024L * MB
        const val HEADER_PLUS_NAME = 34 + 5
    }
}
