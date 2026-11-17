package com.seastreams.tv.player

import java.util.concurrent.atomic.AtomicInteger

/**
 * Rolling download throughput over the last [windowMs] (2 seconds) for the Info overlay, plus the
 * number of HTTP range connections currently downloading. Bytes are counted into [BUCKET_MS] buckets,
 * so memory stays constant however fast the stream arrives. Thread-safe: loader threads record,
 * the UI reads.
 */
class ThroughputMeter(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private val bucketCount = (windowMs / BUCKET_MS).toInt().coerceAtLeast(1)
    private val bucketBytes = LongArray(bucketCount)
    private val bucketStart = LongArray(bucketCount) { Long.MIN_VALUE }

    /** HTTP connections currently transferring bytes. */
    val activeConnections = AtomicInteger(0)

    @Synchronized
    fun record(bytes: Int) {
        if (bytes <= 0) return
        val now = nowMs()
        val slotStart = now - Math.floorMod(now, BUCKET_MS)
        val slot = ((slotStart / BUCKET_MS) % bucketCount).toInt()
        if (bucketStart[slot] != slotStart) {
            bucketStart[slot] = slotStart
            bucketBytes[slot] = 0
        }
        bucketBytes[slot] += bytes.toLong()
    }

    /** Megabits per second averaged over the window. */
    @Synchronized
    fun megabitsPerSecond(): Double {
        val now = nowMs()
        var total = 0L
        for (i in 0 until bucketCount) {
            if (now - bucketStart[i] < windowMs) total += bucketBytes[i]
        }
        return total * BITS_PER_BYTE / MEGA / (windowMs / MILLIS_PER_SECOND)
    }

    @Synchronized
    fun reset() {
        bucketBytes.fill(0)
        bucketStart.fill(Long.MIN_VALUE)
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2_000L
        private const val BUCKET_MS = 100L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val BITS_PER_BYTE = 8.0
        private const val MEGA = 1_000_000.0
        private const val MILLIS_PER_SECOND = 1_000.0
    }
}
