package com.seastreams.tv.usenet.yenc

import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recycles decode buffers (articles are ~0.7 MB each, dozens a second at full speed). A buffer comes back only
 * through [DecodedSegment.release] once no reader holds it, so a recycled array is never visible to anyone.
 * At most [maxPooledBytes] are kept idle; the rest are left to the GC.
 */
class SegmentBufferPool(private val maxPooledBytes: Long = DEFAULT_POOLED_BYTES) {
    private val free = ArrayList<ByteArray>()
    private var pooledBytes = 0L

    /** A buffer of at least [size] bytes (rounded up to [GRANULE], so one size class serves a whole release). */
    @Synchronized
    fun acquire(size: Int): ByteArray {
        var best = -1
        for (i in free.indices) {
            val candidate = free[i].size
            if (candidate >= size && candidate <= size * 2 && (best < 0 || candidate < free[best].size)) best = i
        }
        if (best >= 0) {
            val buffer = free.removeAt(best)
            pooledBytes -= buffer.size
            return buffer
        }
        val rounded = ((size.toLong() + GRANULE - 1) / GRANULE * GRANULE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return ByteArray(rounded)
    }

    @Synchronized
    fun recycle(buffer: ByteArray) {
        if (pooledBytes + buffer.size > maxPooledBytes) return
        free += buffer
        pooledBytes += buffer.size
    }

    @Synchronized
    fun pooledBytes(): Long = pooledBytes

    companion object {
        const val GRANULE = 64 * 1024
        const val DEFAULT_POOLED_BYTES = 24L * 1024 * 1024
    }
}

/**
 * One decoded article: [length] bytes of the file starting at [begin] (zero-based), out of [fileSize].
 * The bytes are reachable only through [copyTo] / [writeTo] / [toByteArray], which hold a reference while they
 * run, so the owner ([SegmentArena][com.seastreams.tv.usenet.stream.SegmentArena]) can [release] it at any time:
 * a copy that loses that race returns `false` and the caller fetches the segment again.
 */
class DecodedSegment(
    private val data: ByteArray,
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

    val end: Long get() = begin + length

    /** Copies [count] bytes from [offset] (relative to [begin]); `false` when the segment was released. */
    fun copyTo(offset: Int, target: ByteArray, targetOffset: Int, count: Int): Boolean = holding {
        System.arraycopy(data, offset, target, targetOffset, count)
    }

    fun writeTo(out: OutputStream): Boolean = holding { out.write(data, 0, length) }

    fun toByteArray(): ByteArray? {
        var copy: ByteArray? = null
        holding { copy = data.copyOf(length) }
        return copy
    }

    /** Drops the owner's reference; the buffer returns to the pool once no copy is running. */
    fun release() {
        if (refs.decrementAndGet() == 0) pool?.recycle(data)
    }

    val released: Boolean get() = refs.get() <= 0

    private inline fun holding(block: () -> Unit): Boolean {
        while (true) {
            val current = refs.get()
            if (current <= 0) return false
            if (refs.compareAndSet(current, current + 1)) break
        }
        try {
            block()
        } finally {
            release()
        }
        return true
    }
}
