package com.seastreams.tv.player

import com.seastreams.tv.core.datastore.PlaybackBufferProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RangeReadAheadTest {

    private val mb = 1024L * 1024
    private val bigHeap = 1024 * mb

    @Test
    fun `high-water mark scales with the buffer profile`() {
        assertEquals(24 * mb, PlaybackBufferProfile.STANDARD.rangeReadAhead(3, bigHeap)?.highWaterBytes)
        assertEquals(64 * mb, PlaybackBufferProfile.LARGE.rangeReadAhead(4, bigHeap)?.highWaterBytes)
        assertEquals(128 * mb, PlaybackBufferProfile.MAX.rangeReadAhead(2, bigHeap)?.highWaterBytes)
    }

    @Test
    fun `a single connection has no read-ahead ring`() {
        PlaybackBufferProfile.entries.forEach { assertNull(it.rangeReadAhead(1, bigHeap)) }
    }

    @Test
    fun `start-up chunks are 4 MB for the first 16 MB, then 8 MB`() {
        val window = PlaybackBufferProfile.LARGE.rangeReadAhead(4, bigHeap)
        assertEquals(RangeReadAhead(4 * mb.toInt(), 8 * mb.toInt(), 16 * mb, 64 * mb), window)
    }

    @Test
    fun `steady chunks shrink so every connection fits under a small mark`() {
        val window = PlaybackBufferProfile.STANDARD.rangeReadAhead(4, bigHeap)
        assertEquals(24 * mb, window?.highWaterBytes)
        assertEquals(6 * mb.toInt(), window?.steadyChunkSize, "4 × 6 MB fit in 24 MB")
    }

    @Test
    fun `the mark is clamped to a quarter of the heap but keeps one start-up chunk per connection`() {
        assertEquals(50 * mb, PlaybackBufferProfile.MAX.rangeReadAhead(2, maxHeapBytes = 200 * mb)?.highWaterBytes)
        val tiny = PlaybackBufferProfile.MAX.rangeReadAhead(4, maxHeapBytes = 32 * mb)
        assertEquals(16 * mb, tiny?.highWaterBytes)
        assertEquals(4 * mb.toInt(), tiny?.steadyChunkSize)
    }

    @Test
    fun `chunk layout covers ranges shorter and longer than the start-up span`() {
        val window = RangeReadAhead(10, 20, 40, 1_000)
        assertEquals(listOf(0L, 10L, 20L, 30L, 40L, 60L), (0..5).map(window::chunkOffset))
        assertEquals(listOf(10, 10, 10, 10, 20, 20), (0..5).map(window::chunkSize))
        assertEquals(1, window.chunkCount(1))
        assertEquals(3, window.chunkCount(25))
        assertEquals(4, window.chunkCount(40))
        assertEquals(5, window.chunkCount(41))
        assertEquals(6, window.chunkCount(80))
    }

    @Test
    fun `a mark smaller than one chunk is rejected`() {
        assertThrows<IllegalArgumentException> { RangeReadAhead(10, 20, 40, 15) }
    }
}
