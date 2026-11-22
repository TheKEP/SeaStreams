package com.seastreams.tv.usenet.nntp

import com.seastreams.tv.usenet.yenc.YencDecoder
import com.seastreams.tv.usenet.yenc.YencException
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One provider's connections (ARCHITECTURE §10 Scheduling). Slots are lazy: a [ProviderWorker] thread is started
 * only while queued work needs one (up to the [ConnectionThrottle]'s allowance), each owning one connection that
 * keeps `pipelineDepth` commands in flight, and leaves after `idleTtlMs` without work. HIGH and LOW queues are
 * shared at `streamingPriority`. A "too many connections" refusal lowers the allowance (AIMD) and never marks
 * the account bad; a bad login does, failing everything queued; repeated connection failures open the
 * [CircuitBreaker], which fails work fast until its cooldown passes.
 */
class ProviderWorkerPool(
    val endpoint: NntpEndpoint,
    internal val settings: NntpEngineSettings,
    private val decoder: YencDecoder,
    private val opener: SocketOpener = DefaultSocketOpener,
    private val warm: WarmConnections? = null,
    internal val clock: () -> Long = monotonicMillis,
) : Closeable {
    enum class State { ONLINE, AUTH_FAILED, CLOSED }

    private val lock = Object()
    private val high = ArrayDeque<ArticleRequest>()
    private val low = ArrayDeque<ArticleRequest>()
    private val share = PriorityShare(settings.streamingShare)
    private val live = ConcurrentHashMap.newKeySet<NntpConnection>()
    private val inFlight = AtomicInteger()
    private val workerIds = AtomicInteger()
    private var workers = 0
    private var dialing = 0
    private var limitStrikes = 0
    private var dialStrikes = 0

    val breaker = CircuitBreaker(settings.breakerThreshold, settings.breakerCooldownMs, clock)
    val throttle = ConnectionThrottle(endpoint.maxConnections, clock = clock)
    val depth: Int = endpoint.pipelineDepth

    @Volatile
    var state = State.ONLINE
        private set

    /** Why the last dial failed, for the provider status line. */
    @Volatile
    var lastError: NntpException? = null
        private set

    @Volatile
    var peakConnections = 0
        private set

    val openConnections: Int get() = live.size

    /** Usable right now: signed in fine and not resting. */
    val healthy: Boolean get() = state == State.ONLINE && !breaker.isOpen()

    /** Work per available pipeline slot; the selector shares a priority group by it. */
    fun load(): Double = (inFlight.get() + queued()).toDouble() / (throttle.allowed() * depth)

    fun queued(): Int = synchronized(lock) { high.size + low.size }

    fun body(messageId: String, part: Int?, priority: FetchPriority): SegmentFetch {
        val request = ArticleRequest.body(messageId, part, priority)
        submit(request)
        return checkNotNull(request.body)
    }

    fun stat(messageId: String, priority: FetchPriority): NntpFuture<Boolean> {
        val request = ArticleRequest.stat(messageId, priority)
        submit(request)
        return checkNotNull(request.stat)
    }

    fun submit(request: ArticleRequest) {
        val rejection: IOException? = synchronized(lock) {
            when {
                state == State.CLOSED -> NntpException(NntpErrorKind.NO_PROVIDERS, "${endpoint.name} is closed")
                state == State.AUTH_FAILED -> lastError ?: authFailed()
                breaker.isOpen() -> resting()
                else -> {
                    (if (request.priority == FetchPriority.HIGH) high else low).addLast(request)
                    spawnLocked()
                    null
                }
            }
        }
        rejection?.let(request::fail)
    }

    // ---- worker side -------------------------------------------------------------------------------------

    internal fun hasWork(): Boolean = synchronized(lock) { state == State.ONLINE && (high.isNotEmpty() || low.isNotEmpty()) }

    /** More connections are open than the throttle now allows: a worker without work in flight should leave. */
    internal fun overLimit(): Boolean = live.size > throttle.allowed()

    internal val online: Boolean get() = state == State.ONLINE

    /** Next request, waiting up to [waitMs] when [block]; `null` on timeout, close or auth failure. */
    internal fun take(block: Boolean, waitMs: Long): ArticleRequest? = synchronized(lock) {
        pullLocked()?.let { return it }
        if (!block || state != State.ONLINE) return null
        try {
            lock.wait(waitMs.coerceAtLeast(1))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        pullLocked()
    }

    internal fun sent() {
        inFlight.incrementAndGet()
    }

    /** A worker's sign-in: a warm connection when one is held, else a fresh dial. `null` = failed (handled here). */
    internal fun dial(): NntpConnection? {
        synchronized(lock) { dialing++ }
        val connection = warm?.take(endpoint) ?: try {
            NntpConnection.connect(endpoint, settings, opener, clock)
        } catch (e: NntpException) {
            val othersDialing = synchronized(lock) { --dialing > 0 }
            onDialError(e, othersDialing)
            return null
        }
        synchronized(lock) { dialing-- }
        if (state != State.ONLINE) {
            connection.close()
            return null
        }
        live += connection
        peakConnections = maxOf(peakConnections, live.size)
        synchronized(lock) {
            limitStrikes = 0
            dialStrikes = 0
        }
        breaker.recordSuccess()
        return connection
    }

    /** Reads the answer to [request] (the oldest in flight). Throws only when the connection itself is unusable. */
    internal fun answer(connection: NntpConnection, request: ArticleRequest) {
        val status = connection.readStatus()
        val code = status.code
        when (request.command) {
            ArticleCommand.BODY -> when {
                code == 222 -> if (request.cancelled) {
                    connection.skipBody()
                } else {
                    try {
                        val segment = connection.readBody(decoder, request.part, settings.verifyChecksums)
                        if (request.body?.complete(segment) != true) segment.release()
                    } catch (e: YencException) {
                        request.fail(e)
                    }
                }
                code == 430 || code == 423 -> request.fail(ProviderMissingException(request.messageId, endpoint.name, code))
                code in 400..499 -> request.fail(NntpException(classifyStatus(code, status.text), "${endpoint.name} answered $code", code))
                else -> throw unexpected(status)
            }
            ArticleCommand.STAT -> when {
                code == 223 -> request.stat?.complete(true)
                code == 430 || code == 423 -> request.stat?.complete(false)
                code in 400..499 -> request.fail(NntpException(classifyStatus(code, status.text), "${endpoint.name} answered $code", code))
                else -> throw unexpected(status)
            }
        }
        inFlight.decrementAndGet()
        connection.lastUsedAt = clock()
        breaker.recordSuccess()
    }

    /** A connection dropped, stalled or desynced: re-queue what was in flight on it (oldest first). */
    internal fun dropped(connection: NntpConnection, flying: List<ArticleRequest>, error: IOException) {
        retire(connection)
        inFlight.addAndGet(-flying.size)
        val limit = (error as? NntpException)?.kind == NntpErrorKind.CONNECTION_LIMIT
        if (limit) throttle.onLimit(live.size)
        if (!limit && breaker.recordFailure()) {
            flying.forEach { it.fail(resting()) }
            failAll(resting())
            return
        }
        requeue(flying, error)
    }

    /** Puts [requests] back at the front, in order; each may be re-queued [MAX_ATTEMPTS] times. */
    internal fun requeue(requests: List<ArticleRequest>, error: IOException) {
        val failed = ArrayList<ArticleRequest>()
        synchronized(lock) {
            for (request in requests.asReversed()) {
                when {
                    request.done -> Unit
                    state != State.ONLINE || ++request.attempts > MAX_ATTEMPTS -> failed += request
                    else -> (if (request.priority == FetchPriority.HIGH) high else low).addFirst(request)
                }
            }
            spawnLocked()
        }
        failed.forEach { it.fail(if (state == State.CLOSED) NntpException(NntpErrorKind.NO_PROVIDERS, "Closed") else error) }
    }

    internal fun retire(connection: NntpConnection) {
        live -= connection
        connection.close()
    }

    internal fun workerExited() = synchronized(lock) {
        workers--
        spawnLocked()
    }

    override fun close() {
        synchronized(lock) {
            state = State.CLOSED
            lock.notifyAll()
        }
        failAll(NntpException(NntpErrorKind.NO_PROVIDERS, "${endpoint.name} is closed"))
        live.forEach { it.close() }
    }

    // ---- internals ---------------------------------------------------------------------------------------

    private fun onDialError(error: NntpException, othersDialing: Boolean) {
        lastError = error
        val backoff: Long
        when (error.kind) {
            NntpErrorKind.AUTH_FAILED -> {
                synchronized(lock) { state = State.AUTH_FAILED }
                failAll(error)
                return
            }
            NntpErrorKind.CONNECTION_LIMIT -> {
                throttle.onLimit(live.size)
                // Open connections (or sign-ins still under way) carry the queue; only a lone refusal is a strike.
                if (live.isNotEmpty() || othersDialing) return
                val strikes = synchronized(lock) { ++limitStrikes }
                if (strikes >= LIMIT_STRIKES) {
                    synchronized(lock) { limitStrikes = 0 }
                    failAll(error)
                }
                backoff = settings.dialBackoffMs * strikes
            }
            else -> {
                if (breaker.recordFailure()) {
                    failAll(resting())
                    return
                }
                backoff = settings.dialBackoffMs * synchronized(lock) { ++dialStrikes }.coerceAtMost(4)
            }
        }
        try {
            Thread.sleep(backoff)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun spawnLocked() {
        if (state != State.ONLINE) return
        val queued = high.size + low.size
        if (queued == 0) return
        lock.notifyAll()
        val demand = (inFlight.get() + queued + depth - 1) / depth
        if (workers < minOf(throttle.allowed(), demand)) {
            workers++
            Thread(ProviderWorker(this), "nntp-${endpoint.id}-${workerIds.incrementAndGet()}").apply { isDaemon = true }.start()
        }
    }

    private fun pullLocked(): ArticleRequest? {
        while (true) {
            val next = when {
                high.isNotEmpty() && low.isNotEmpty() -> if (share.pickLow()) low.removeFirst() else high.removeFirst()
                high.isNotEmpty() -> high.removeFirst()
                else -> low.removeFirstOrNull()
            } ?: return null
            if (!next.cancelled && !next.done) return next
        }
    }

    private fun failAll(error: IOException) {
        val all = synchronized(lock) { (high + low).also { high.clear(); low.clear() } }
        all.forEach { it.fail(error) }
    }

    private fun resting() = NntpException(NntpErrorKind.CONNECTION, "${endpoint.name} is resting after repeated failures")

    private fun authFailed() = NntpException(NntpErrorKind.AUTH_FAILED, "${endpoint.name} rejected the username or password")

    private fun unexpected(status: NntpStatus): NntpException {
        val kind = if (isConnectionLimitText(status.text)) NntpErrorKind.CONNECTION_LIMIT else NntpErrorKind.PROTOCOL
        return NntpException(kind, "${endpoint.name} answered ${status.code} out of step", status.code)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val LIMIT_STRIKES = 3
    }
}
