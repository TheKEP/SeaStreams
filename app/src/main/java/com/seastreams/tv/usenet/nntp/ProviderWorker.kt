package com.seastreams.tv.usenet.nntp

import java.io.IOException

/**
 * One lazy connection slot of a [ProviderWorkerPool]: signs in when there is work, keeps up to `pipelineDepth`
 * commands in flight (answers are read strictly FIFO), sends `DATE` while idle, and leaves once idle for
 * `idleTtlMs`, when the throttle shrinks below the open count, or when the pool closes. A dropped, stalled or
 * desynced connection re-queues everything that was in flight on it.
 */
internal class ProviderWorker(private val pool: ProviderWorkerPool) : Runnable {
    private val settings = pool.settings
    private val flying = ArrayDeque<ArticleRequest>()

    /** Last keep-alive; kept apart from `lastUsedAt` so pings never postpone the idle TTL. */
    private var pingedAt = Long.MIN_VALUE

    override fun run() {
        var connection: NntpConnection? = null
        try {
            while (pool.online) {
                val current = connection ?: (if (pool.hasWork()) pool.dial() else null) ?: return
                connection = current
                try {
                    if (!exchange(current)) return
                } catch (e: IOException) {
                    connection = null
                    val inFlight = flying.toList()
                    flying.clear()
                    pool.dropped(current, inFlight, e)
                }
            }
        } finally {
            connection?.let(pool::retire)
            if (flying.isNotEmpty()) pool.requeue(flying.toList(), NntpException(NntpErrorKind.CONNECTION, "Connection closed"))
            pool.workerExited()
        }
    }

    /** One round: top up the pipeline, then read the oldest answer. `false` = this worker should leave. */
    private fun exchange(connection: NntpConnection): Boolean {
        var wrote = false
        while (flying.size < pool.depth) {
            val waitMs = if (flying.isEmpty() && !wrote) idleWait(connection) else 0
            val request = pool.take(block = flying.isEmpty() && !wrote, waitMs = waitMs) ?: break
            flying.addLast(request)
            when (request.command) {
                ArticleCommand.BODY -> connection.sendBody(request.messageId)
                ArticleCommand.STAT -> connection.sendStat(request.messageId)
            }
            pool.sent()
            wrote = true
        }
        if (flying.isEmpty()) return stayIdle(connection)
        if (wrote) connection.flush()
        pool.answer(connection, flying.first())
        flying.removeFirst()
        return true
    }

    /** No work arrived during the wait: ping when due, leave when past the idle TTL or over the limit. */
    private fun stayIdle(connection: NntpConnection): Boolean {
        if (!pool.online || pool.overLimit()) return false
        val idle = pool.clock() - connection.lastUsedAt
        if (idle >= settings.idleTtlMs) return false
        if (sincePing(connection) >= settings.keepAliveMs && !pool.hasWork()) {
            val status = connection.date()
            if (status.code != 111) throw NntpException(NntpErrorKind.PROTOCOL, "Keep-alive answered ${status.code}", status.code)
            pingedAt = pool.clock()
        }
        return true
    }

    private fun idleWait(connection: NntpConnection): Long {
        val untilPing = settings.keepAliveMs - sincePing(connection)
        val untilTtl = settings.idleTtlMs - (pool.clock() - connection.lastUsedAt)
        return minOf(untilPing.takeIf { it > 0 } ?: untilTtl, untilTtl).coerceIn(1, MAX_WAIT_MS)
    }

    private fun sincePing(connection: NntpConnection): Long =
        pool.clock() - maxOf(connection.lastUsedAt, pingedAt)

    private companion object {
        /** Re-checks the pool's state at least this often while idle. */
        const val MAX_WAIT_MS = 1_000L
    }
}
