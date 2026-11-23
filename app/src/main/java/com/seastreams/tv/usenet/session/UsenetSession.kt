package com.seastreams.tv.usenet.session

import com.seastreams.tv.usenet.archive.RandomAccessData
import com.seastreams.tv.usenet.verify.ContentSniffer
import com.seastreams.tv.usenet.verify.MediaContainer
import java.io.Closeable
import java.io.IOException

/**
 * One opened Usenet release (ARCHITECTURE §10 Playback): the media bytes, their name and container, and everything
 * that must be closed with it (posted files, the segment arena, a census, an addon's private engine). Reads are
 * serialised: ExoPlayer's loader and the loopback proxy may both read, but one release reads one place at a time.
 * [fail] (a census that proved the release broken) makes every later read throw, so failover moves on early.
 */
class UsenetSession(
    val token: String,
    val name: String,
    private val data: RandomAccessData,
    private val resources: List<Closeable>,
    /** `0` = never closed for idling (PRD §12 *Stream idle timeout*). */
    val idleTimeoutMs: Long,
    container: MediaContainer? = null,
    /** Frees the decoded segments held for this session (they are fetched again, usually from disk, when read). */
    private val trim: () -> Unit = {},
) : Closeable {
    private val lock = Any()

    @Volatile
    var failure: IOException? = null
        private set

    @Volatile
    var closed = false
        private set

    val size: Long = data.size()

    val container: MediaContainer = container ?: ContentSniffer.byName(name) ?: MediaContainer.UNKNOWN

    /** `name` safe for a URL path segment and a player's title bar. */
    val urlName: String get() = name.substringAfterLast('/').ifBlank { "video.${container.extension}" }

    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        failure?.let { throw it }
        if (closed) throw IOException("The Usenet stream was closed")
        synchronized(lock) {
            return data.read(position, target, offset, length)
        }
    }

    /** Drops this idle session's in-memory segments so only the session being watched holds a full arena. */
    fun trimMemory() {
        if (!closed) trim()
    }

    fun fail(error: IOException) {
        failure = error
    }

    override fun close() {
        if (closed) return
        closed = true
        resources.forEach { runCatching { it.close() } }
    }
}
