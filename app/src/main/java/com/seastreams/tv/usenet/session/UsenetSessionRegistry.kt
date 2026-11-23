package com.seastreams.tv.usenet.session

import com.seastreams.tv.usenet.nntp.monotonicMillis
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Opens a release for a stream token (blocking). */
fun interface SessionOpener {
    fun open(token: String): UsenetSession
}

/**
 * The open Usenet sessions (ARCHITECTURE §10): at most [maxSessions] (4) releases stay open; ExoPlayer and the
 * loopback proxy open and close many readers over one release, so each [acquire] / [release] pair shares one
 * session per token. Concurrent first opens of a token wait for one open. Past the limit the least recently used
 * idle session is closed (or the least recently used one, if all are busy). The idle reaper closes sessions nobody
 * has read for their *Stream idle timeout*.
 */
@Singleton
class UsenetSessionRegistry internal constructor(
    private val opener: SessionOpener,
    private val clock: () -> Long,
    private val maxSessions: Int,
    private val reapPeriodMs: Long,
) : Closeable {

    @Inject
    constructor(opener: UsenetSessionOpener) : this(opener, monotonicMillis, MAX_SESSIONS, REAP_PERIOD_MS)

    private class Entry {
        val openLock = Any()

        @Volatile
        var session: UsenetSession? = null
        var refs = 0
        var lastUsed = 0L
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var reaper: ScheduledExecutorService? = null

    /** The session for [token], opening it if needed. Pair with [release]. */
    fun acquire(token: String): UsenetSession {
        val entry = synchronized(this) {
            entries.getOrPut(token) { Entry() }.also {
                it.refs++
                it.lastUsed = clock()
                startReaperLocked()
            }
        }
        val session = try {
            synchronized(entry.openLock) {
                entry.session?.takeUnless { it.closed } ?: opener.open(token).also { entry.session = it }
            }
        } catch (e: Throwable) {
            synchronized(this) {
                entry.refs--
                if (entry.refs <= 0 && entry.session == null) entries.remove(token)
            }
            throw e
        }
        session.failure?.let { failure ->
            release(token)
            throw failure
        }
        evictBeyondLimit(keep = token)
        trimIdle(keep = token)
        return session
    }

    fun release(token: String) {
        synchronized(this) {
            val entry = entries[token] ?: return
            entry.refs = (entry.refs - 1).coerceAtLeast(0)
            entry.lastUsed = clock()
        }
    }

    /** Closes sessions unread for their idle timeout (run every [reapPeriodMs] on a daemon thread). */
    fun reap() {
        val now = clock()
        closeAll(selectLocked { entry ->
            val session = entry.session ?: return@selectLocked false
            entry.refs == 0 && session.idleTimeoutMs > 0 && now - entry.lastUsed >= session.idleTimeoutMs
        })
    }

    fun openCount(): Int = synchronized(this) { entries.values.count { it.session?.closed == false } }

    fun isOpen(token: String): Boolean = synchronized(this) { entries[token]?.session?.closed == false }

    override fun close() {
        closeAll(selectLocked { true })
        synchronized(this) { reaper?.shutdownNow().also { reaper = null } }
    }

    private fun evictBeyondLimit(keep: String) {
        val victims = synchronized(this) {
            val open = entries.filter { (_, e) -> e.session?.closed == false }
            val excess = open.size - maxSessions
            if (excess <= 0) return
            open.entries.filter { it.key != keep }
                .sortedWith(compareBy({ it.value.refs > 0 }, { it.value.lastUsed }))
                .take(excess)
                .mapNotNull { (token, entry) -> entries.remove(token)?.let { entry.session } }
        }
        victims.forEach { it.close() }
    }

    /** Idle sessions give their arena memory back while another one plays: at most one full arena at a time. */
    private fun trimIdle(keep: String) {
        val idle = synchronized(this) { entries.filter { (t, e) -> t != keep && e.refs == 0 }.values.mapNotNull { it.session } }
        idle.forEach { it.trimMemory() }
    }

    private fun selectLocked(predicate: (Entry) -> Boolean): List<UsenetSession> = synchronized(this) {
        val chosen = entries.filter { (_, entry) -> predicate(entry) }
        chosen.keys.forEach(entries::remove)
        chosen.values.mapNotNull { it.session }
    }

    private fun closeAll(sessions: List<UsenetSession>) = sessions.forEach { it.close() }

    private fun startReaperLocked() {
        if (reaper != null || reapPeriodMs <= 0) return
        reaper = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "usenet-session-reaper").apply { isDaemon = true } }
            .apply { scheduleWithFixedDelay({ runCatching { reap() } }, reapPeriodMs, reapPeriodMs, TimeUnit.MILLISECONDS) }
    }

    companion object {
        const val MAX_SESSIONS = 4
        private const val REAP_PERIOD_MS = 30_000L
    }
}
