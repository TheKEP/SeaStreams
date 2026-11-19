package com.seastreams.tv.player

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ParallelRangeReaderTest {

    private val executor = Executors.newCachedThreadPool()
    private val data = Random(7).nextBytes(1_000_003)

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Fixed-size chunks with a window large enough never to throttle. */
    private fun fixed(chunkSize: Int) = RangeReadAhead(chunkSize, chunkSize, 0, 64L * 1024 * 1024)

    /** 10 KB chunks for the first 40 KB, then 20 KB chunks, at most [highWater] bytes held. */
    private fun adaptive(highWater: Long = 200_000) = RangeReadAhead(10_000, 20_000, 40_000, highWater)

    /** Serves [data] ranges, counting concurrent connections. */
    private inner class FakeServer(private val slowReads: Boolean = false) : RangeFetcher {
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val opens = mutableListOf<LongRange>()
        var dropAfterBytes: Int? = null
        var failAlways = false

        @Synchronized
        private fun record(range: LongRange) {
            opens += range
        }

        @Synchronized
        fun openCount(): Int = opens.size

        @Synchronized
        fun sortedOpens(): List<LongRange> = opens.sortedBy { it.first }

        override fun open(start: Long, endInclusive: Long): RangeConnection {
            if (failAlways) throw IOException("server down")
            record(start..endInclusive)
            val now = concurrent.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, ::maxOf)
            val drop = dropAfterBytes.also { dropAfterBytes = null }
            return object : RangeConnection {
                var position = start
                var served = 0

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position > endInclusive) return -1
                    if (drop != null && served >= drop) throw IOException("connection reset")
                    if (slowReads) Thread.sleep(1)
                    val count = minOf(length.toLong(), endInclusive - position + 1, 8_192L).toInt()
                    System.arraycopy(data, position.toInt(), buffer, offset, count)
                    position += count
                    served += count
                    return count
                }

                override fun cancel() = Unit

                override fun close() {
                    concurrent.decrementAndGet()
                }
            }
        }
    }

    private fun readAll(reader: ParallelRangeReader, bufferSize: Int = 10_000, pauseMs: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = reader.read(buffer, 0, buffer.size)
            if (read == ParallelRangeReader.END_OF_INPUT) break
            out.write(buffer, 0, read)
            if (pauseMs > 0) Thread.sleep(pauseMs)
        }
        return out.toByteArray()
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    @ParameterizedTest
    @CsvSource("1, 65536", "2, 100000", "3, 131072", "4, 4194304", "4, 999")
    fun `chunks are reassembled in order whatever the connection count`(connections: Int, chunkSize: Int) {
        val reader = ParallelRangeReader(FakeServer(), 0, data.size.toLong(), fixed(chunkSize), connections, executor, ThroughputMeter())
        assertArrayEquals(data, readAll(reader))
        reader.close()
    }

    @Test
    fun `reading from an offset with a bounded length returns exactly that slice`() {
        val reader = ParallelRangeReader(FakeServer(), 12_345, 512_000, adaptive(), 3, executor, null)
        assertArrayEquals(data.copyOfRange(12_345, 512_000), readAll(reader, bufferSize = 777))
    }

    @Test
    fun `never more than the configured connections download at once`() {
        val server = FakeServer(slowReads = true)
        val reader = ParallelRangeReader(server, 0, data.size.toLong(), adaptive(), 3, executor, null)
        readAll(reader)
        assertTrue(server.maxConcurrent.get() <= 3, "max concurrent was ${server.maxConcurrent.get()}")
    }

    @Test
    fun `workers keep pipelining chunks ahead of an idle reader up to the high-water mark`() {
        val server = FakeServer(slowReads = true)
        val reader = ParallelRangeReader(server, 0, data.size.toLong(), adaptive(highWater = 200_000), 3, executor, null)

        // 4 × 10 KB start-up chunks + 8 × 20 KB steady chunks = 200 KB, far beyond `connections` chunks.
        awaitTrue { server.openCount() == 12 && server.concurrent.get() == 0 }
        Thread.sleep(200)

        assertEquals(12, server.openCount(), "nothing scheduled past the high-water mark")
        assertEquals(12, reader.chunksScheduled)
        assertEquals(3, server.maxConcurrent.get(), "all connections ran while the reader was idle")
        assertTrue(reader.peakRetainedBytes <= 200_000)
        reader.close()
    }

    @Test
    fun `chunk buffers never exceed the high-water mark while a slow reader drains them`() {
        val window = adaptive(highWater = 100_000)
        val reader = ParallelRangeReader(FakeServer(), 0, data.size.toLong(), window, 4, executor, null)
        assertArrayEquals(data, readAll(reader, bufferSize = 4_096, pauseMs = 0))
        assertTrue(reader.peakRetainedBytes in 1..window.highWaterBytes, "peak ${reader.peakRetainedBytes}")
    }

    @Test
    fun `start-up chunks are small, later chunks large, and the last one is truncated`() {
        val server = FakeServer()
        val reader = ParallelRangeReader(server, 0, 95_000, adaptive(), 2, executor, null)
        readAll(reader)
        assertEquals(
            listOf(
                0L..9_999L, 10_000L..19_999L, 20_000L..29_999L, 30_000L..39_999L,
                40_000L..59_999L, 60_000L..79_999L, 80_000L..94_999L,
            ),
            server.sortedOpens(),
        )
    }

    @Test
    fun `the probe's open connection is used for the first chunk`() {
        val server = FakeServer()
        val probe = server.open(0, 99_999)
        val reader = ParallelRangeReader(server, 0, 200_000, fixed(100_000), 2, executor, null, firstConnection = probe)
        assertArrayEquals(data.copyOfRange(0, 200_000), readAll(reader))
        assertEquals(2, server.openCount(), "probe + chunk 2, no second request for chunk 1")
    }

    @Test
    fun `a dropped connection is resumed from where it stopped`() {
        val server = FakeServer().apply { dropAfterBytes = 30_000 }
        val reader = ParallelRangeReader(server, 0, 200_000, fixed(100_000), 1, executor, null)
        assertArrayEquals(data.copyOfRange(0, 200_000), readAll(reader))
        assertTrue(server.opens.any { it.first in 30_000L..99_999L }, "retry starts mid-chunk: ${server.opens}")
    }

    @Test
    fun `a chunk that keeps failing surfaces an IOException to the reader`() {
        val server = FakeServer().apply { failAlways = true }
        val reader = ParallelRangeReader(server, 0, 200_000, fixed(100_000), 2, executor, null)
        assertThrows<IOException> { readAll(reader) }
        reader.close()
    }

    @Test
    fun `close cancels in-flight downloads and unblocks a waiting reader`() {
        val blocked = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val hanging = RangeFetcher { _, _ ->
            object : RangeConnection {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    blocked.countDown()
                    Thread.sleep(60_000)
                    return -1
                }

                override fun cancel() {
                    cancelled.incrementAndGet()
                }

                override fun close() = Unit
            }
        }
        val reader = ParallelRangeReader(hanging, 0, 1_000_000, fixed(100_000), 3, executor, null)
        val readerError = arrayOfNulls<Throwable>(1)
        val thread = Thread { readerError[0] = runCatching { reader.read(ByteArray(10), 0, 10) }.exceptionOrNull() }
        thread.start()
        assertTrue(blocked.await(5, TimeUnit.SECONDS))

        reader.close()
        thread.join(5_000)

        assertTrue(!thread.isAlive, "the blocked read returned")
        assertTrue(readerError[0] is IOException)
        assertEquals(3, cancelled.get(), "every in-flight connection was cancelled")
    }

    @Test
    fun `a seek stops the pipeline and a reader at the new position serves the exact slice`() {
        val server = FakeServer(slowReads = true)
        val old = ParallelRangeReader(server, 0, data.size.toLong(), adaptive(highWater = 400_000), 3, executor, null)
        awaitTrue { server.openCount() >= 4 }

        old.close()
        assertThrows<IOException> { old.read(ByteArray(10), 0, 10) }
        awaitTrue { server.concurrent.get() == 0 }
        val opensAfterClose = server.openCount()
        Thread.sleep(300)
        assertEquals(opensAfterClose, server.openCount(), "no range opened after close")

        val seeked = ParallelRangeReader(server, 600_000, data.size.toLong(), adaptive(), 3, executor, null)
        assertArrayEquals(data.copyOfRange(600_000, data.size), readAll(seeked))
    }

    @Test
    fun `the meter sees every downloaded byte`() {
        val meter = ThroughputMeter()
        val reader = ParallelRangeReader(FakeServer(), 0, 200_000, fixed(50_000), 2, executor, meter)
        readAll(reader)
        assertTrue(meter.megabitsPerSecond() > 0)
        awaitTrue { meter.activeConnections.get() == 0 }
    }

    @Test
    fun `only real 206 responses with a total length allow splitting`() {
        assertEquals(123_456_789L, partialContentTotalLength(206, "bytes 0-4194303/123456789"))
        assertNull(partialContentTotalLength(200, "bytes 0-4194303/123456789"), "server ignored the range")
        assertNull(partialContentTotalLength(206, null))
        assertNull(partialContentTotalLength(206, "bytes 0-4194303/*"), "unknown total length")
    }
}
