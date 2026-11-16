package com.seastreams.tv.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async

/**
 * Runs each keyed load once on [scope] and hands every caller the same [Deferred], so a prefetch
 * started on one screen is simply awaited by the next instead of being repeated.
 *
 * Loads live in [scope], not in the caller: a caller that stops waiting does not cancel the load.
 * Failed loads are evicted immediately so the next caller retries; successful ones expire after
 * [ttlMillis] (the HTTP cache underneath keeps the network cost low after that).
 */
class SharedLoadCache<K : Any, V>(
    private val scope: CoroutineScope,
    private val ttlMillis: Long,
    private val nowMillis: () -> Long,
) {
    private class Entry<V>(val value: Deferred<V>, val createdAtMillis: Long)

    private val lock = Any()
    private val entries = HashMap<K, Entry<V>>()

    /** The running or finished load for [key], starting [load] if there is no usable one. */
    fun start(key: K, load: suspend () -> V): Deferred<V> = synchronized(lock) {
        entries[key]?.takeIf { isUsable(it) }?.let { return it.value }
        val deferred = scope.async { load() }
        entries[key] = Entry(deferred, nowMillis())
        deferred.invokeOnCompletion { cause ->
            if (cause != null) synchronized(lock) { if (entries[key]?.value === deferred) entries.remove(key) }
        }
        deferred
    }

    /**
     * The result of a finished, still-fresh load for [key], or `null` when there is none yet. Never
     * starts a load or suspends, so a screen can use it to draw already-loaded content on its first frame.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompleted
    fun peek(key: K): V? = synchronized(lock) {
        val entry = entries[key]?.takeIf { isUsable(it) && it.value.isCompleted } ?: return null
        entry.value.getCompleted()
    }

    /** Awaits the shared load for [key]. */
    suspend fun get(key: K, load: suspend () -> V): V = start(key, load).await()

    /**
     * Awaits the shared load for [key] and removes it, so the next caller gets a fresh load. Used
     * for one-shot results such as a shuffled hero.
     */
    suspend fun take(key: K, load: suspend () -> V): V {
        val deferred = start(key, load)
        synchronized(lock) { if (entries[key]?.value === deferred) entries.remove(key) }
        return deferred.await()
    }

    /** A cancelled or failed load (`isCancelled` covers both) or an expired one is never reused. */
    private fun isUsable(entry: Entry<V>): Boolean =
        !entry.value.isCancelled && nowMillis() - entry.createdAtMillis < ttlMillis
}
