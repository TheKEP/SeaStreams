package com.seastreams.tv.usenet.archive

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * A read-only [SeekableByteChannel] over [RandomAccessData] for commons-compress `SevenZFile` (ARCHITECTURE §10).
 * The first exception the *source* throws (network trouble, a missing article) is kept in [sourceFailure], so
 * [SevenZipArchive] can rethrow it unchanged (the player may retry) instead of mistaking it for a damaged archive.
 * Closing the channel never closes the source: the NNTP files belong to the session.
 */
internal class SevenZipChannel(private val source: RandomAccessData) : SeekableByteChannel {
    private val size = source.size()
    private var position = 0L
    private var open = true
    private val buffer = ByteArray(BUFFER_BYTES)

    @Volatile
    var sourceFailure: Throwable? = null
        private set

    override fun isOpen(): Boolean = open

    override fun position(): Long {
        ensureOpen()
        return position
    }

    override fun position(newPosition: Long): SeekableByteChannel {
        ensureOpen()
        require(newPosition >= 0) { "Negative position" }
        position = newPosition
        return this
    }

    override fun size(): Long {
        ensureOpen()
        return size
    }

    override fun read(dst: ByteBuffer): Int {
        ensureOpen()
        if (!dst.hasRemaining()) return 0
        if (position >= size) return -1
        val wanted = minOf(buffer.size.toLong(), dst.remaining().toLong(), size - position).toInt()
        val got = try {
            source.read(position, buffer, 0, wanted)
        } catch (e: IOException) {
            if (sourceFailure == null) sourceFailure = e
            throw e
        } catch (e: RuntimeException) {
            if (sourceFailure == null) sourceFailure = e
            throw e
        }
        if (got <= 0) return -1
        dst.put(buffer, 0, got)
        position += got
        return got
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    override fun close() {
        open = false
    }

    private fun ensureOpen() {
        if (!open) throw ClosedChannelException()
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
