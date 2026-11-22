package com.seastreams.tv.usenet.nntp

import com.seastreams.tv.usenet.yenc.DecodedSegment
import java.io.IOException

/** Playback (HIGH) vs. background census / probes (LOW); contended grants are shared at `streamingPriority`. */
enum class FetchPriority { HIGH, LOW }

/**
 * A result that arrives on an engine thread. [await] blocks the caller (a Media3 loader thread); an interrupt
 * surfaces as [java.io.InterruptedIOException] so loader cancellation is clean (ARCHITECTURE §10 Interrupts).
 * Listeners run exactly once, on the thread that completed it (or at once if it already has).
 */
class NntpFuture<T> {
    private val lock = Object()
    private var value: T? = null
    private var error: IOException? = null
    private var done = false
    private val listeners = ArrayList<(NntpFuture<T>) -> Unit>(2)

    @Volatile
    var cancelled = false
        private set

    val isDone: Boolean get() = synchronized(lock) { done }

    /** The value once completed successfully, else `null`. */
    val result: T? get() = synchronized(lock) { value }

    val failure: IOException? get() = synchronized(lock) { error }

    fun complete(result: T): Boolean = finish(result, null)

    fun fail(failure: IOException): Boolean = finish(null, failure)

    /** Calls the fetch off: queued work is skipped, and awaiting callers get [FetchCancelledException]. */
    fun cancel() {
        cancelled = true
        fail(FetchCancelledException())
    }

    fun onDone(listener: (NntpFuture<T>) -> Unit) {
        synchronized(lock) {
            if (!done) {
                listeners += listener
                return
            }
        }
        listener(this)
    }

    /**
     * The value, waiting at most [timeoutMs] (`0` = no limit). A timeout is [NntpErrorKind.TIMEOUT]; the fetch
     * itself keeps running, so a caller that retries finds it.
     */
    fun await(timeoutMs: Long = 0): T {
        val deadline = if (timeoutMs > 0) System.nanoTime() + timeoutMs * 1_000_000 else Long.MAX_VALUE
        synchronized(lock) {
            while (!done) {
                val left = if (timeoutMs > 0) (deadline - System.nanoTime()) / 1_000_000 else 0
                if (timeoutMs > 0 && left <= 0) throw NntpException(NntpErrorKind.TIMEOUT, "The news server took too long to send the video")
                try {
                    if (timeoutMs > 0) lock.wait(left.coerceAtLeast(1)) else lock.wait()
                } catch (e: InterruptedException) {
                    throw interruptedIo(e)
                }
            }
            error?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }

    private fun finish(result: T?, failure: IOException?): Boolean {
        val toRun = synchronized(lock) {
            if (done) return false
            value = result
            error = failure
            done = true
            lock.notifyAll()
            ArrayList(listeners).also { listeners.clear() }
        }
        toRun.forEach { it(this) }
        return true
    }
}

/** A BODY fetch: resolves to the decoded article. */
typealias SegmentFetch = NntpFuture<DecodedSegment>

/** Where segments come from: the [NntpEngine], or a cache in front of it. */
fun interface SegmentSource {
    /** Starts fetching [messageId] (NZB segment [part]) without waiting. */
    fun fetch(messageId: String, part: Int, priority: FetchPriority): SegmentFetch
}

/** What a pooled request asks of a provider. */
enum class ArticleCommand { BODY, STAT }

/**
 * One queued unit of work in a [ProviderWorkerPool]. A BODY resolves [body]; a STAT resolves [stat] (`true` =
 * the provider has it). [attempts] counts re-queues after dropped connections or connection-limit hits.
 */
class ArticleRequest private constructor(
    val command: ArticleCommand,
    val messageId: String,
    /** The NZB segment number, checked against `=ybegin part=` (`wrong_part`). */
    val part: Int?,
    val priority: FetchPriority,
    val body: SegmentFetch?,
    val stat: NntpFuture<Boolean>?,
) {
    var attempts = 0

    val cancelled: Boolean get() = body?.cancelled ?: stat?.cancelled ?: false

    val done: Boolean get() = body?.isDone ?: stat?.isDone ?: true

    fun fail(error: IOException) {
        body?.fail(error)
        stat?.fail(error)
    }

    companion object {
        fun body(messageId: String, part: Int?, priority: FetchPriority) =
            ArticleRequest(ArticleCommand.BODY, messageId, part, priority, SegmentFetch(), null)

        fun stat(messageId: String, priority: FetchPriority) =
            ArticleRequest(ArticleCommand.STAT, messageId, null, priority, null, NntpFuture())
    }
}
