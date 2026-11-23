package com.seastreams.tv.usenet.verify

import com.seastreams.tv.usenet.nntp.monotonicMillis
import javax.inject.Inject
import javax.inject.Singleton

/** What is known about one release (by stream token). */
sealed interface ReleaseHealth {
    data object Healthy : ReleaseHealth

    /** Articles are missing beyond the damage policy (census or playback found it). */
    data class Broken(val reason: String) : ReleaseHealth

    /** Compressed / encrypted / 7z / no video: it can never stream. */
    data class NotStreamable(val reason: String) : ReleaseHealth
}

/**
 * Remembers which releases proved broken or not streamable (ARCHITECTURE §10 Damage), so the next search drops them
 * and failover never tries them twice. In memory, [ttlMillis] (6 h: providers backfill, posts get repaired), bounded.
 */
@Singleton
class ReleaseHealthCache internal constructor(private val clock: () -> Long, private val ttlMillis: Long) {

    @Inject
    constructor() : this(monotonicMillis, TTL_MILLIS)

    private val entries = object : LinkedHashMap<String, Pair<Long, ReleaseHealth>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ReleaseHealth>>?) = size > MAX_ENTRIES
    }

    @Synchronized
    fun record(token: String, health: ReleaseHealth) {
        entries[token] = clock() to health
    }

    @Synchronized
    fun get(token: String): ReleaseHealth? {
        val (at, health) = entries[token] ?: return null
        if (clock() - at > ttlMillis) {
            entries.remove(token)
            return null
        }
        return health
    }

    /** Known broken or not streamable: dropped from search results. */
    fun unplayable(token: String): Boolean = get(token).let { it is ReleaseHealth.Broken || it is ReleaseHealth.NotStreamable }

    private companion object {
        const val TTL_MILLIS = 6L * 60 * 60 * 1000
        const val MAX_ENTRIES = 1_000
    }
}
