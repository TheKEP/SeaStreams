package com.seastreams.tv.player

import com.seastreams.tv.core.datastore.PlaybackBufferProfile

/**
 * Read-ahead window of the [ParallelRangeReader] (PRD §9): the first [startupBytes] after an open (or
 * seek) are fetched as [startupChunkSize] ranges for a fast first frame, the rest as larger
 * [steadyChunkSize] ranges, and the downloaded-but-unread bytes never exceed [highWaterBytes].
 */
data class RangeReadAhead(
    val startupChunkSize: Int,
    val steadyChunkSize: Int,
    val startupBytes: Long,
    val highWaterBytes: Long,
) {
    init {
        require(startupChunkSize > 0 && steadyChunkSize > 0 && startupBytes >= 0)
        require(highWaterBytes >= maxOf(startupChunkSize, steadyChunkSize)) { "high-water mark below one chunk" }
    }

    private val startupChunks: Long = (startupBytes + startupChunkSize - 1) / startupChunkSize
    private val startupSpan: Long = startupChunks * startupChunkSize

    /** Offset of chunk [index] from the reader's start. */
    fun chunkOffset(index: Int): Long =
        if (index < startupChunks) index.toLong() * startupChunkSize else startupSpan + (index - startupChunks) * steadyChunkSize

    /** Nominal (buffer) size of chunk [index]; the last chunk of a range may be shorter. */
    fun chunkSize(index: Int): Int = if (index < startupChunks) startupChunkSize else steadyChunkSize

    /** Number of chunks covering [length] bytes. */
    fun chunkCount(length: Long): Int {
        val count = if (length <= startupSpan) {
            (length + startupChunkSize - 1) / startupChunkSize
        } else {
            startupChunks + (length - startupSpan + steadyChunkSize - 1) / steadyChunkSize
        }
        return count.toInt()
    }

    companion object {
        const val MEGABYTE = 1024 * 1024
        const val STARTUP_CHUNK_SIZE = 4 * MEGABYTE
        const val STEADY_CHUNK_SIZE = 8 * MEGABYTE
        const val STARTUP_BYTES = 16L * MEGABYTE

        /** Share of the Java heap the ring may take; ExoPlayer's own buffer lives on the same heap. */
        const val MAX_HEAP_SHARE = 4
    }
}

/**
 * The parallel reader's window for this buffer profile: a `24 / 64 / 128 MB` high-water mark, or
 * `null` when [connections] is `1` (no parallel reader, no ring). The mark is clamped to a quarter of
 * [maxHeapBytes] against OOM, but never below `connections × 4 MB`. Steady chunks are 8 MB, shrunk
 * (down to 4 MB) when the mark can't hold one per connection, so every connection can always run.
 */
fun PlaybackBufferProfile.rangeReadAhead(
    connections: Int,
    maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
): RangeReadAhead? {
    if (connections <= 1) return null
    val profileCap = when (this) {
        PlaybackBufferProfile.STANDARD -> 24L
        PlaybackBufferProfile.LARGE -> 64L
        PlaybackBufferProfile.MAX -> 128L
    } * RangeReadAhead.MEGABYTE
    val floor = connections.toLong() * RangeReadAhead.STARTUP_CHUNK_SIZE
    val highWater = minOf(profileCap, maxHeapBytes / RangeReadAhead.MAX_HEAP_SHARE).coerceAtLeast(floor)
    val steady = (highWater / connections).coerceIn(
        RangeReadAhead.STARTUP_CHUNK_SIZE.toLong(),
        RangeReadAhead.STEADY_CHUNK_SIZE.toLong(),
    ).toInt()
    return RangeReadAhead(
        startupChunkSize = RangeReadAhead.STARTUP_CHUNK_SIZE,
        steadyChunkSize = steady,
        startupBytes = RangeReadAhead.STARTUP_BYTES,
        highWaterBytes = highWater,
    )
}
