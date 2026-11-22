package com.seastreams.tv.usenet.cache

/**
 * The byte-bounded LRU index of the segment disk cache: file key → size, least recently used first.
 * Not thread-safe; [SegmentDiskCache] guards it.
 */
class SegmentCacheIndex {
    private val entries = LinkedHashMap<String, Long>(256, 0.75f, true)

    var totalBytes = 0L
        private set

    val size: Int get() = entries.size

    operator fun contains(key: String): Boolean = entries.containsKey(key)

    /** Marks [key] used; `false` when it isn't indexed. */
    fun touch(key: String): Boolean = entries[key] != null

    fun add(key: String, bytes: Long) {
        entries.put(key, bytes)?.let { totalBytes -= it }
        totalBytes += bytes
    }

    fun remove(key: String): Boolean {
        val bytes = entries.remove(key) ?: return false
        totalBytes -= bytes
        return true
    }

    /** Drops least-recently-used keys until at most [capBytes] remain; returns the dropped keys. */
    fun evictTo(capBytes: Long): List<String> {
        val dropped = ArrayList<String>()
        val iterator = entries.entries.iterator()
        while (totalBytes > capBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            totalBytes -= eldest.value
            dropped += eldest.key
        }
        return dropped
    }

    fun clear() {
        entries.clear()
        totalBytes = 0
    }
}
