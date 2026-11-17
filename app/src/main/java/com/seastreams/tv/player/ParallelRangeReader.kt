package com.seastreams.tv.player

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** An open HTTP response body for one byte range. [read] returns `-1` at the end of the range. */
interface RangeConnection : Closeable {
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Aborts a blocked [read] from another thread (closing the socket). */
    fun cancel()
}

/** Opens `Range: bytes=[start]-[endInclusive]` of one resource. */
fun interface RangeFetcher {
    @Throws(IOException::class)
    fun open(start: Long, endInclusive: Long): RangeConnection
}

/**
 * Reads `[start, end)` of a resource as consecutive [chunkSize] byte ranges, with up to [connections]
 * of them downloading at once on [executor] while the caller reads sequentially (PRD §9 Parallel HTTP
 * Range Reader).
 *
 * - Memory is bounded: at most [connections] chunk buffers exist, recycled through a small pool.
 * - The reader can consume a chunk while it is still arriving, so start-up latency matches one connection.
 * - [close] is deterministic: it cancels every in-flight connection (unblocking its socket read) and
 *   no worker touches a buffer afterwards. ExoPlayer closes and reopens the data source on seek.
 * - A chunk whose connection drops is retried from where it stopped, [MAX_RETRIES] times.
 */
class ParallelRangeReader(
    private val fetcher: RangeFetcher,
    private val start: Long,
    private val end: Long,
    private val chunkSize: Int,
    private val connections: Int,
    private val executor: ExecutorService,
    private val meter: ThroughputMeter?,
    /** Already-open connection for the first chunk (the probe that proved `206` support). */
    firstConnection: RangeConnection? = null,
) : Closeable {

    private val lock = Any()
    private val chunkCount = ((end - start + chunkSize - 1) / chunkSize).toInt()
    private val pool = ArrayDeque<ByteArray>()
    private val inFlight = HashMap<Int, Chunk>()
    private var nextToSchedule = 0
    private var position = start

    @Volatile
    private var closed = false

    init {
        require(end > start) { "empty range" }
        require(connections >= 1 && chunkSize > 0)
        synchronized(lock) {
            val initial = minOf(connections, chunkCount)
            for (i in 0 until initial) schedule(if (i == 0) firstConnection else null)
        }
        if (chunkCount == 0) firstConnection?.close()
    }

    /** Bytes left to read. */
    val remaining: Long get() = end - position

    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= end) return END_OF_INPUT
        val index = ((position - start) / chunkSize).toInt()
        val chunk = synchronized(lock) { inFlight[index] } ?: throw IOException("chunk $index not scheduled")
        val offsetInChunk = (position - chunk.start).toInt()
        val copied = chunk.lock.withLock {
            while (chunk.filled <= offsetInChunk && chunk.error == null && !closed) {
                try {
                    chunk.changed.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("range read interrupted")
                }
            }
            if (closed) throw IOException("reader closed")
            if (chunk.filled <= offsetInChunk) throw chunk.error ?: IOException("chunk $index failed")
            val count = minOf(length, chunk.filled - offsetInChunk)
            System.arraycopy(chunk.buffer, offsetInChunk, buffer, offset, count)
            count
        }
        position += copied
        if (position - chunk.start >= chunk.length) finish(index, chunk)
        return copied
    }

    override fun close() {
        if (closed) return
        closed = true
        val chunks = synchronized(lock) { inFlight.values.toList().also { inFlight.clear(); pool.clear() } }
        chunks.forEach { chunk ->
            chunk.future?.cancel(true)
            chunk.connection?.cancel()
            chunk.lock.withLock { chunk.changed.signalAll() }
        }
    }

    /** The reader finished [chunk]: recycle its buffer and start the next chunk in line. */
    private fun finish(index: Int, chunk: Chunk) {
        synchronized(lock) {
            if (closed) return
            inFlight.remove(index)
            pool.addLast(chunk.buffer)
            if (nextToSchedule < chunkCount) schedule(null)
        }
    }

    /** Called with [lock] held. */
    private fun schedule(preOpened: RangeConnection?) {
        val index = nextToSchedule++
        val chunkStart = start + index.toLong() * chunkSize
        val chunkLength = minOf(chunkSize.toLong(), end - chunkStart).toInt()
        val buffer = pool.removeFirstOrNull() ?: ByteArray(chunkSize)
        val chunk = Chunk(chunkStart, chunkLength, buffer)
        inFlight[index] = chunk
        chunk.future = executor.submit { download(chunk, preOpened) }
    }

    private fun download(chunk: Chunk, preOpened: RangeConnection?) {
        var attempt = 0
        var pending = preOpened
        meter?.activeConnections?.incrementAndGet()
        try {
            while (!closed && chunk.filled < chunk.length) {
                try {
                    val connection = pending ?: fetcher.open(chunk.start + chunk.filled, chunk.start + chunk.length - 1)
                    pending = null
                    chunk.connection = connection
                    connection.use { transfer(chunk, it) }
                } catch (e: IOException) {
                    if (closed) return
                    attempt++
                    if (attempt > MAX_RETRIES) {
                        fail(chunk, e)
                        return
                    }
                } finally {
                    chunk.connection = null
                }
            }
        } finally {
            pending?.close()
            meter?.activeConnections?.decrementAndGet()
        }
    }

    private fun transfer(chunk: Chunk, connection: RangeConnection) {
        while (!closed && chunk.filled < chunk.length) {
            val read = connection.read(chunk.buffer, chunk.filled, minOf(READ_SLICE, chunk.length - chunk.filled))
            if (read == END_OF_INPUT) throw EOFException("range ended after ${chunk.filled} of ${chunk.length} bytes")
            if (read == 0) continue
            meter?.record(read)
            chunk.lock.withLock {
                chunk.filled += read
                chunk.changed.signalAll()
            }
        }
    }

    private fun fail(chunk: Chunk, error: IOException) {
        chunk.lock.withLock {
            chunk.error = error
            chunk.changed.signalAll()
        }
    }

    private class Chunk(val start: Long, val length: Int, val buffer: ByteArray) {
        val lock = ReentrantLock()
        val changed: Condition = lock.newCondition()

        /** Bytes written into [buffer]; only grows. Guarded by [lock]. */
        @Volatile
        var filled = 0

        @Volatile
        var error: IOException? = null

        @Volatile
        var connection: RangeConnection? = null

        @Volatile
        var future: Future<*>? = null
    }

    companion object {
        const val END_OF_INPUT = -1
        const val DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024
        const val MAX_RETRIES = 2
        private const val READ_SLICE = 64 * 1024
        private const val WAIT_SLICE_MS = 250L
    }
}

/**
 * Whether a ranged response really is partial content we can split: `206` with a
 * `Content-Range: bytes a-b/total` header. Returns the total length, or `null` (single-stream fallback).
 */
fun partialContentTotalLength(responseCode: Int, contentRange: String?): Long? {
    if (responseCode != HTTP_PARTIAL_CONTENT || contentRange == null) return null
    val match = CONTENT_RANGE.matchEntire(contentRange.trim()) ?: return null
    return match.groupValues[3].toLongOrNull()?.takeIf { it > 0 }
}

private const val HTTP_PARTIAL_CONTENT = 206
private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
