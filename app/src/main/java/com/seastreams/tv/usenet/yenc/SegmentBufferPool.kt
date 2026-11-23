package com.seastreams.tv.usenet.yenc

import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recycles decode buffers (articles are ~0.7 MB each, dozens a second at full speed). A buffer comes back only
 * through [DecodedSegment.release] once no reader holds it, so a recycled array is never visible to anyone.
 * Strictly bounded: at most [maxPooledBytes] and [maxPooledBuffers] are kept idle, and a buffer larger than
 * [MAX_POOLED_BUFFER] is never kept; the rest are left to the GC, so idle arrays can't hoard a TV's heap.
 * [trim] drops every idle buffer (a session closed or gave its memory back).
 */
class SegmentBufferPool(
    private val maxPooledBytes: Long = DEFAULT_POOLED_BYTES,
    private val maxPooledBuffers: Int = DEFAULT_POOLED_BUFFERS,
) {
    private val free = ArrayList<ByteArray>()
    private var pooledBytes = 0L

    /**
     * A buffer of at least [size] bytes (rounded up to [GRANULE], so one size class serves a whole release).
     * When the heap is exhausted the idle buffers are dropped and the allocation is tried once more.
     */
    fun acquire(size: Int): ByteArray {
        reuse(size)?.let { return it }
        val rounded = ((size.toLong() + GRANULE - 1) / GRANULE * GRANULE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return try {
            ByteArray(rounded)
        } catch (e: OutOfMemoryError) {
            trim()
            ByteArray(rounded)
        }
    }

    @Synchronized
    private fun reuse(size: Int): ByteArray? {
        var best = -1
        for (i in free.indices) {
            val candidate = free[i].size
            if (candidate >= size && candidate <= size * 2 && (best < 0 || candidate < free[best].size)) best = i
        }
        if (best < 0) return null
        val buffer = free.removeAt(best)
        pooledBytes -= buffer.size
        return buffer
    }

    @Synchronized
    fun recycle(buffer: ByteArray) {
        if (buffer.size > MAX_POOLED_BUFFER || free.size >= maxPooledBuffers) return
        if (pooledBytes + buffer.size > maxPooledBytes) return
        free += buffer
        pooledBytes += buffer.size
    }

    /** Lets the GC have every idle buffer. */
    @Synchronized
    fun trim() {
        free.clear()
        pooledBytes = 0
    }

    @Synchronized
    fun pooledBytes(): Long = pooledBytes

    @Synchronized
    fun pooledCount(): Int = free.size

    companion object {
        const val GRANULE = 64 * 1024
        const val DEFAULT_POOLED_BYTES = 16L * 1024 * 1024
        const val DEFAULT_POOLED_BUFFERS = 16
        /** Articles are ≤ ~1 MB; anything bigger (a huge single-part post) is not worth keeping idle. */
        const val MAX_POOLED_BUFFER = 2 * 1024 * 1024
    }
}

/**
 * One decoded article: [length] bytes of the file starting at [begin] (zero-based), out of [fileSize].
 * The bytes are reachable only through [copyTo] / [writeTo] / [toByteArray], which hold a reference while they
 * run, so the owner ([SegmentArena][com.seastreams.tv.usenet.stream.SegmentArena]) can [release] it at any time:
 * a copy that loses that race returns `false` and the caller fetches the segment again.
 */
class DecodedSegment(
    data: ByteArray,
    val length: Int,
    val begin: Long,
    val fileSize: Long,
    val name: String,
    /** `=ybegin part=`, `null` for a single-part post. */
    val part: Int?,
    val totalParts: Int?,
    private val pool: SegmentBufferPool? = null,
) {
    private val refs = AtomicInteger(1)

    /** Cleared on the last release, so a segment object still referenced somewhere doesn't pin its array. */
    @Volatile
    private var data: ByteArray? = data

    val end: Long get() = begin + length

    /** Copies [count] bytes from [offset] (relative to [begin]); `false` when the segment was released. */
    fun copyTo(offset: Int, target: ByteArray, targetOffset: Int, count: Int): Boolean = holding { bytes ->
        System.arraycopy(bytes, offset, target, targetOffset, count)
    }

    fun writeTo(out: OutputStream): Boolean = holding { bytes -> out.write(bytes, 0, length) }

    fun toByteArray(): ByteArray? {
        var copy: ByteArray? = null
        holding { bytes -> copy = bytes.copyOf(length) }
        return copy
    }

    /** Drops the owner's reference; the buffer returns to the pool once no copy is running. */
    fun release() {
        if (refs.decrementAndGet() != 0) return
        val bytes = data ?: return
        data = null
        pool?.recycle(bytes)
    }

    val released: Boolean get() = refs.get() <= 0

    private inline fun holding(block: (ByteArray) -> Unit): Boolean {
        while (true) {
            val current = refs.get()
            if (current <= 0) return false
            if (refs.compareAndSet(current, current + 1)) break
        }
        try {
            // Non-null while a reference is held: only the release that reaches zero clears it.
            block(data ?: return false)
        } finally {
            release()
        }
        return true
    }
}
