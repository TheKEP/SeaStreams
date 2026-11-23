package com.seastreams.tv.usenet.yenc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class SegmentBufferPoolTest {
    private val article = 700 * 1024

    @Test
    fun `idle buffers never exceed the byte cap or the buffer cap`() {
        val pool = SegmentBufferPool(maxPooledBytes = 3L * 768 * 1024, maxPooledBuffers = 8)
        val buffers = (1..10).map { pool.acquire(article) }
        buffers.forEach(pool::recycle)
        assertEquals(3, pool.pooledCount(), "the byte cap keeps three 768 KB buffers")
        assertTrue(pool.pooledBytes() <= 3L * 768 * 1024)

        val counted = SegmentBufferPool(maxPooledBytes = Long.MAX_VALUE, maxPooledBuffers = 4)
        (1..10).map { counted.acquire(article) }.forEach(counted::recycle)
        assertEquals(4, counted.pooledCount(), "the buffer-count cap holds even with bytes to spare")
    }

    @Test
    fun `the defaults stay small enough for a 512 MB TV heap`() {
        val pool = SegmentBufferPool()
        (1..100).map { pool.acquire(article) }.forEach(pool::recycle)
        assertTrue(pool.pooledBytes() <= SegmentBufferPool.DEFAULT_POOLED_BYTES)
        assertTrue(pool.pooledCount() <= SegmentBufferPool.DEFAULT_POOLED_BUFFERS)
        assertTrue(SegmentBufferPool.DEFAULT_POOLED_BYTES <= 16L * 1024 * 1024)
    }

    @Test
    fun `oversized buffers are never pooled and trim empties the pool`() {
        val pool = SegmentBufferPool()
        pool.recycle(ByteArray(SegmentBufferPool.MAX_POOLED_BUFFER + 1))
        assertEquals(0, pool.pooledCount())
        val reused = pool.acquire(article).also(pool::recycle)
        assertSame(reused, pool.acquire(article), "a fitting idle buffer is handed out again")
        pool.recycle(reused)
        pool.trim()
        assertEquals(0L, pool.pooledBytes())
        assertNotSame(reused, pool.acquire(article))
    }

    @Test
    fun `a released segment returns its buffer once and no longer pins it`() {
        val pool = SegmentBufferPool()
        val data = pool.acquire(1_000)
        val segment = DecodedSegment(data, 1_000, 0, 1_000, "a", null, null, pool)
        segment.release()
        segment.release() // a double release must not recycle twice
        assertEquals(1, pool.pooledCount())
        assertTrue(segment.released)
        assertFalse(segment.copyTo(0, ByteArray(10), 0, 10))
        assertFalse(segment.writeTo(ByteArrayOutputStream()))
        assertNull(segment.toByteArray())
    }

    @Test
    fun `a failed decode gives its buffer straight back`() {
        val pool = SegmentBufferPool()
        val decoder = YencDecoder(pool)
        val body = "=ybegin line=128 size=5000 name=a.bin\r\n" + "a".repeat(100) + "\r\n.\r\n" // no =yend: truncated
        val reader = com.seastreams.tv.usenet.nntp.NntpReader.of(body.toByteArray(Charsets.ISO_8859_1))
        runCatching { decoder.decode(reader, null, verifyCrc = false) }
        assertEquals(1, pool.pooledCount(), "the truncated article's buffer is back in the pool")
    }
}
