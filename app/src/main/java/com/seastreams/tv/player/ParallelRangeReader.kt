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
 * Reads `[start, end)` of a resource as consecutive byte ranges laid out by [readAhead] (small chunks
 * first, then larger ones) while the caller reads sequentially (PRD §9 Parallel HTTP Range Reader).
 *
 * - Sliding window: up to [connections] chunks download at once, and a worker that finishes a chunk
 *   immediately starts the next one, independently of the caller's read pace, until the
 *   downloaded-but-unread bytes reach [RangeReadAhead.highWaterBytes]. Reading frees room and resumes it.
 * - Memory is strictly bounded: every chunk buffer, in flight or pooled, counts towards the high-water
 *   mark, and buffers are recycled by size.
 * - The reader can consume a chunk while it is still arriving, so start-up latency matches one connection.
 * - [close] is deterministic: it cancels every in-flight connection (unblocking its socket read) and no
 *   chunk is scheduled afterwards. ExoPlayer closes and reopens the data source on seek.
 * - A chunk whose connection drops is retried from where it stopped, [MAX_RETRIES] times.
 */
class ParallelRangeReader(
    private val fetcher: RangeFetcher,
    private val start: Long,
    private val end: Long,
    private val readAhead: RangeReadAhead,
    private val connections: Int,
    private val executor: ExecutorService,
    private val meter: ThroughputMeter?,
    /** Already-open connection for the first chunk (the probe that proved `206` support). */
    firstConnection: RangeConnection? = null,
) : Closeable {

    private val lock = ReentrantLock()
    private val scheduled: Condition = lock.newCondition()
    private val chunkCount: Int
    private val pool = HashMap<Int, ArrayDeque<ByteArray>>()
    private val inFlight = HashMap<Int, Chunk>()
    private var nextToSchedule = 0
    private var downloading = 0
    private var inFlightBytes = 0L
    private var pooledBytes = 0L
    private var readIndex = 0
    private var position = start
    private var pendingFirst: RangeConnection? = firstConnection

    /** Most bytes ever held in chunk buffers (in flight + pooled); never above the high-water mark. */
    @Volatile
    internal var peakRetainedBytes = 0L
        private set

    @Volatile
    private var closed = false

    init {
        require(end > start) { "empty range" }
        require(connections >= 1)
        chunkCount = readAhead.chunkCount(end - start)
        lock.withLock { pump() }
    }

    /** Bytes left to read. */
    val remaining: Long get() = end - position

    /** Chunks scheduled so far (downloading, downloaded or already read). */
    internal val chunksScheduled: Int get() = lock.withLock { nextToSchedule }

    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= end) return END_OF_INPUT
        val index = readIndex
        val chunk = awaitScheduled(index)
        val offsetInChunk = (position - chunk.start).toInt()
        val copied = chunk.lock.withLock {
            while (chunk.filled <= offsetInChunk && chunk.error == null && !closed) {
                awaitSlice(chunk.changed)
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
        val chunks = lock.withLock {
            pendingFirst?.close()
            pendingFirst = null
            inFlight.values.toList().also {
                inFlight.clear()
                pool.clear()
                inFlightBytes = 0
                pooledBytes = 0
                scheduled.signalAll()
            }
        }
        chunks.forEach { chunk ->
            chunk.future?.cancel(true)
            chunk.connection?.cancel()
            chunk.lock.withLock { chunk.changed.signalAll() }
        }
    }

    /** Waits until chunk [index] is scheduled (the worker freeing its slot may still be wrapping up). */
    private fun awaitScheduled(index: Int): Chunk = lock.withLock {
        while (!closed) {
            inFlight[index]?.let { return it }
            awaitSlice(scheduled)
        }
        throw IOException("reader closed")
    }

    private fun awaitSlice(condition: Condition) {
        try {
            condition.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("range read interrupted")
        }
    }

    /** The reader finished [chunk]: recycle its buffer and refill the window. */
    private fun finish(index: Int, chunk: Chunk) {
        lock.withLock {
            readIndex = index + 1
            if (closed) return
            inFlight.remove(index)
            inFlightBytes -= chunk.buffer.size
            pool.getOrPut(chunk.buffer.size) { ArrayDeque() }.addLast(chunk.buffer)
            pooledBytes += chunk.buffer.size
            pump()
        }
    }

    /** Schedules chunks while a connection is free and the window has room. Called with [lock] held. */
    private fun pump() {
        while (!closed && nextToSchedule < chunkCount && downloading < connections) {
            val size = readAhead.chunkSize(nextToSchedule)
            if (inFlightBytes + size > readAhead.highWaterBytes && inFlight.isNotEmpty()) return
            schedule(takeBuffer(size))
        }
    }

    /** A pooled buffer of [size], or a new one after evicting pooled buffers to stay under the mark. */
    private fun takeBuffer(size: Int): ByteArray {
        pool[size]?.removeFirstOrNull()?.let {
            pooledBytes -= size
            return it
        }
        for (queue in pool.values) {
            while (inFlightBytes + pooledBytes + size > readAhead.highWaterBytes) {
                val evicted = queue.removeFirstOrNull() ?: break
                pooledBytes -= evicted.size
            }
        }
        return ByteArray(size)
    }

    /** Called with [lock] held. */
    private fun schedule(buffer: ByteArray) {
        val index = nextToSchedule++
        val chunkStart = start + readAhead.chunkOffset(index)
        val chunkLength = minOf(buffer.size.toLong(), end - chunkStart).toInt()
        val chunk = Chunk(chunkStart, chunkLength, buffer)
        inFlight[index] = chunk
        inFlightBytes += buffer.size
        peakRetainedBytes = maxOf(peakRetainedBytes, inFlightBytes + pooledBytes)
        downloading++
        val preOpened = if (index == 0) pendingFirst.also { pendingFirst = null } else null
        chunk.future = executor.submit { download(chunk, preOpened) }
        scheduled.signalAll()
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
            // The worker slot is free: start the next chunk right away, whatever the reader's pace.
            lock.withLock {
                downloading--
                pump()
            }
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
