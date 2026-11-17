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

    /** Serves [data] ranges, counting concurrent connections; [failFirstOpensAt] breaks the first opens of a range start. */
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

    private fun readAll(reader: ParallelRangeReader, bufferSize: Int = 10_000): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = reader.read(buffer, 0, buffer.size)
            if (read == ParallelRangeReader.END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    @ParameterizedTest
    @CsvSource("1, 65536", "2, 100000", "3, 131072", "4, 4194304", "4, 999")
    fun `chunks are reassembled in order whatever the connection count`(connections: Int, chunkSize: Int) {
        val server = FakeServer()
        val reader = ParallelRangeReader(server, 0, data.size.toLong(), chunkSize, connections, executor, ThroughputMeter())
        assertArrayEquals(data, readAll(reader))
        reader.close()
    }

    @Test
    fun `reading from an offset with a bounded length returns exactly that slice`() {
        val reader = ParallelRangeReader(FakeServer(), 12_345, 512_000, 50_000, 3, executor, null)
        assertArrayEquals(data.copyOfRange(12_345, 512_000), readAll(reader, bufferSize = 777))
    }

    @Test
    fun `never more than the configured connections download at once`() {
        val server = FakeServer(slowReads = true)
        val reader = ParallelRangeReader(server, 0, data.size.toLong(), 40_000, 3, executor, null)
        readAll(reader)
        assertTrue(server.maxConcurrent.get() <= 3, "max concurrent was ${server.maxConcurrent.get()}")
    }

    @Test
    fun `chunk ranges are consecutive 4 MB style byte ranges`() {
        val server = FakeServer()
        val reader = ParallelRangeReader(server, 0, 300_000, 100_000, 2, executor, null)
        readAll(reader)
        assertEquals(listOf(0L..99_999L, 100_000L..199_999L, 200_000L..299_999L), server.opens.sortedBy { it.first })
    }

    @Test
    fun `the probe's open connection is used for the first chunk`() {
        val server = FakeServer()
        val probe = server.open(0, 99_999)
        val reader = ParallelRangeReader(server, 0, 200_000, 100_000, 2, executor, null, firstConnection = probe)
        assertArrayEquals(data.copyOfRange(0, 200_000), readAll(reader))
        assertEquals(2, server.opens.size, "probe + chunk 2, no second request for chunk 1")
    }

    @Test
    fun `a dropped connection is resumed from where it stopped`() {
        val server = FakeServer().apply { dropAfterBytes = 30_000 }
        val reader = ParallelRangeReader(server, 0, 200_000, 100_000, 1, executor, null)
        assertArrayEquals(data.copyOfRange(0, 200_000), readAll(reader))
        assertTrue(server.opens.any { it.first in 30_000L..99_999L }, "retry starts mid-chunk: ${server.opens}")
    }

    @Test
    fun `a chunk that keeps failing surfaces an IOException to the reader`() {
        val server = FakeServer().apply { failAlways = true }
        val reader = ParallelRangeReader(server, 0, 200_000, 100_000, 2, executor, null)
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
        val reader = ParallelRangeReader(hanging, 0, 1_000_000, 100_000, 3, executor, null)
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
    fun `the meter sees every downloaded byte`() {
        val meter = ThroughputMeter()
        val reader = ParallelRangeReader(FakeServer(), 0, 200_000, 50_000, 2, executor, meter)
        readAll(reader)
        assertTrue(meter.megabitsPerSecond() > 0)
        assertEquals(0, meter.activeConnections.get())
    }

    @Test
    fun `only real 206 responses with a total length allow splitting`() {
        assertEquals(123_456_789L, partialContentTotalLength(206, "bytes 0-4194303/123456789"))
        assertNull(partialContentTotalLength(200, "bytes 0-4194303/123456789"), "server ignored the range")
        assertNull(partialContentTotalLength(206, null))
        assertNull(partialContentTotalLength(206, "bytes 0-4194303/*"), "unknown total length")
    }
}
