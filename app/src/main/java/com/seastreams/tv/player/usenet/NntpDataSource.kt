package com.seastreams.tv.player.usenet

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.seastreams.tv.usenet.session.UsenetSession
import com.seastreams.tv.usenet.session.UsenetSessionRegistry
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import java.io.IOException

/**
 * Reads a `seastreams-nntp://<token>` stream for ExoPlayer (ARCHITECTURE §10 ExoPlayer). Every open shares the
 * token's [UsenetSession] through the [UsenetSessionRegistry], so ExoPlayer's many range opens (and seeks) reuse
 * one release, its read-ahead and its arena: seeking inside what is already cached is instant. The length is always
 * known. Blocking on the loader thread; interrupts surface as `InterruptedIOException`.
 */
class NntpDataSource(private val sessions: UsenetSessionRegistry) : BaseDataSource(true) {
    private var session: UsenetSession? = null
    private var token: String? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val key = UsenetStreamRegistry.tokenOf(dataSpec.uri.toString()) ?: throw IOException("Not a Usenet stream")
        val current = sessions.acquire(key)
        token = key
        session = current
        if (dataSpec.position > current.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        val left = current.size - position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) left else minOf(dataSpec.length, left)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val current = session ?: throw IOException("The Usenet stream isn't open")
        val n = current.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        token?.let(sessions::release)
        token = null
        session = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val sessions: UsenetSessionRegistry, private val listener: TransferListener? = null) : DataSource.Factory {
        override fun createDataSource(): DataSource = NntpDataSource(sessions).also { source -> listener?.let(source::addTransferListener) }
    }
}
