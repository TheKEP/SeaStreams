package com.seastreams.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

/**
 * The playing file's total size in bytes, as its own data source reports it (PRD §9 Info `Bitrate (est.)`): the HTTP
 * `Content-Length` / `Content-Range` total through [ParallelRangeDataSource] (addon and Debrid streams) or the assembled
 * Usenet file's length through `NntpDataSource`. Written on loader threads, read by the Info overlay.
 */
class StreamContentLength {
    private val total = AtomicLong(UNKNOWN)

    /** A new stream started: the previous size no longer applies. */
    fun reset() = total.set(UNKNOWN)

    /** An open-ended read from [position] returned [length] bytes to the end of the file. */
    fun record(position: Long, length: Long) {
        if (position < 0 || length <= 0) return
        val end = position + length
        total.accumulateAndGet(end) { old, new -> maxOf(old, new) }
    }

    /** The file size, or `null` while no read has told it. */
    val bytes: Long? get() = total.get().takeIf { it > 0 }

    private companion object {
        const val UNKNOWN = -1L
    }
}

/**
 * Passes every call to [upstream] and, when an open-ended read (`length` unset) gets a known length back, records the
 * file's total size in [sink]. Bounded reads (a range ending before the file does) are not totals and are ignored.
 */
class LengthTrackingDataSource(private val upstream: DataSource, private val sink: StreamContentLength) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        if (dataSpec.length == C.LENGTH_UNSET.toLong() && length != C.LENGTH_UNSET.toLong()) sink.record(dataSpec.position, length)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory, private val sink: StreamContentLength) : DataSource.Factory {
        override fun createDataSource(): DataSource = LengthTrackingDataSource(upstream.createDataSource(), sink)
    }
}
