package com.seastreams.tv.usenet.nntp

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Connections signed in before they are needed (PRD §12 *Warm connections*): opened when a stream search starts,
 * so pressing play skips the 1–3 s TLS + login. Held ones get a `DATE` every `keepAliveMs` and are closed once
 * unused for `idleTtlMs`, so they never hold the account's slots for long. A pool [take]s one before dialling.
 */
class WarmConnections(
    private val opener: SocketOpener = DefaultSocketOpener,
    private val clock: () -> Long = monotonicMillis,
    /** Runs a dial off the caller's thread; tests may run it inline. */
    private val dialer: (Runnable) -> Unit = { Thread(it, "nntp-warm").apply { isDaemon = true }.start() },
) : Closeable {
    private class Held(val connection: NntpConnection, val since: Long, var pingedAt: Long)

    private val held = HashMap<String, ArrayDeque<Held>>()
    private val opening = HashMap<String, Int>()
    private var settings = NntpEngineSettings()
    private var sweeper: ScheduledExecutorService? = null
    private var closed = false

    /** Opens connections to [endpoint] until [count] are held or opening. */
    fun warm(endpoint: NntpEndpoint, count: Int, settings: NntpEngineSettings) {
        val key = endpoint.fingerprint
        val need = synchronized(this) {
            if (closed) return
            this.settings = settings
            startSweeperLocked()
            val have = (held[key]?.size ?: 0) + (opening[key] ?: 0)
            val need = (minOf(count, endpoint.maxConnections) - have).coerceAtLeast(0)
            if (need > 0) opening[key] = (opening[key] ?: 0) + need
            need
        }
        repeat(need) { dialer(Runnable { dial(endpoint, key, settings) }) }
    }

    /** A held connection for [endpoint], or `null`. */
    fun take(endpoint: NntpEndpoint): NntpConnection? = synchronized(this) {
        val queue = held[endpoint.fingerprint] ?: return null
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst().connection
            if (!next.closed) return next
        }
        null
    }

    fun heldCount(endpoint: NntpEndpoint): Int = synchronized(this) { held[endpoint.fingerprint]?.size ?: 0 }

    /** Keep-alive + expiry pass; runs every few seconds on the sweeper, and directly in tests. */
    fun sweep() {
        val now = clock()
        val (expired, ping) = synchronized(this) {
            val expired = ArrayList<NntpConnection>()
            val ping = ArrayList<Held>()
            for (queue in held.values) {
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    when {
                        entry.connection.closed -> iterator.remove()
                        now - entry.since >= settings.idleTtlMs -> {
                            iterator.remove()
                            expired += entry.connection
                        }
                        now - entry.pingedAt >= settings.keepAliveMs -> ping += entry
                    }
                }
            }
            expired to ping
        }
        expired.forEach { it.close() }
        for (entry in ping) {
            val alive = synchronized(this) { held.values.any { it.contains(entry) } && removeEntry(entry) }
            if (!alive) continue
            val ok = runCatching { entry.connection.date().code == 111 }.getOrDefault(false)
            if (!ok) {
                entry.connection.close()
                continue
            }
            entry.pingedAt = clock()
            synchronized(this) {
                if (closed) entry.connection.close() else held.getOrPut(entry.connection.endpoint.fingerprint) { ArrayDeque() }.addLast(entry)
            }
        }
    }

    /** Closes the warm connections of providers whose fingerprint is not in [keep]. */
    fun retain(keep: Set<String>) {
        val dropped = synchronized(this) {
            val gone = held.keys.filter { it !in keep }
            gone.flatMap { held.remove(it).orEmpty().map(Held::connection) }
        }
        dropped.forEach { it.close() }
    }

    override fun close() {
        val all = synchronized(this) {
            closed = true
            sweeper?.shutdownNow()
            sweeper = null
            held.values.flatten().map(Held::connection).also { held.clear() }
        }
        all.forEach { it.close() }
    }

    private fun removeEntry(entry: Held): Boolean = held.values.any { it.remove(entry) }

    private fun dial(endpoint: NntpEndpoint, key: String, settings: NntpEngineSettings) {
        val connection = try {
            NntpConnection.connect(endpoint, settings, opener, clock)
        } catch (e: java.io.IOException) {
            null // No warm connection then: the pool signs in as usual.
        }
        synchronized(this) {
            opening[key] = ((opening[key] ?: 1) - 1).coerceAtLeast(0)
            if (connection == null) return
            if (closed) {
                connection.close()
                return
            }
            val now = clock()
            held.getOrPut(key) { ArrayDeque() }.addLast(Held(connection, now, now))
        }
    }

    private fun startSweeperLocked() {
        if (sweeper != null) return
        sweeper = Executors.newSingleThreadScheduledExecutor { Thread(it, "nntp-warm-sweep").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching { sweep() } }, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS) }
    }

    private companion object {
        const val SWEEP_MS = 5_000L
    }
}
