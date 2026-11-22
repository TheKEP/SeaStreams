package com.seastreams.tv.usenet.stream

import com.seastreams.tv.usenet.yenc.DecodedSegment

/**
 * The in-memory LRU of decoded segments, by message ID (ARCHITECTURE §10 Read-ahead): byte-bounded to
 * [capacityBytes]. It owns the segments it holds and releases them on eviction, which returns their buffers
 * to the pool once no copy is running ([DecodedSegment.copyTo] returns `false` after that).
 */
class SegmentArena(val capacityBytes: Long) {
    private val entries = LinkedHashMap<String, DecodedSegment>(64, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(messageId: String): DecodedSegment? = entries[messageId]?.takeUnless { it.released }

    /**
     * Takes ownership of [segment]. If the ID is already held, the held one stays and [segment] is left
     * unowned (to the GC). Evicts least-recently-used entries, never the one just added.
     */
    fun put(messageId: String, segment: DecodedSegment) {
        val evicted = ArrayList<DecodedSegment>()
        synchronized(this) {
            if (entries.containsKey(messageId)) return
            entries[messageId] = segment
            bytes += segment.length
            val iterator = entries.entries.iterator()
            while (bytes > capacityBytes && entries.size > 1) {
                val eldest = iterator.next()
                if (eldest.key == messageId) continue
                iterator.remove()
                bytes -= eldest.value.length
                evicted += eldest.value
            }
        }
        evicted.forEach(DecodedSegment::release)
    }

    @Synchronized
    fun contains(messageId: String): Boolean = entries.containsKey(messageId)

    @Synchronized
    fun sizeBytes(): Long = bytes

    @Synchronized
    fun count(): Int = entries.size

    fun clear() {
        val all = synchronized(this) { entries.values.toList().also { entries.clear(); bytes = 0 } }
        all.forEach(DecodedSegment::release)
    }

    companion object {
        private const val MB = 1024L * 1024
        const val MIN_BYTES = 64 * MB
        const val MAX_BYTES = 160 * MB
        const val PER_SEGMENT_BYTES = 3 * MB / 2

        /** `clamp(64 MB, n × 1.5 MB, 160 MB)`, and never over a sixth of the heap. */
        fun sizeFor(windowSegments: Int, maxMemory: Long = Runtime.getRuntime().maxMemory()): Long =
            (windowSegments * PER_SEGMENT_BYTES).coerceIn(MIN_BYTES, MAX_BYTES).coerceAtMost(maxMemory / 6)
    }
}
