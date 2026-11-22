package com.seastreams.tv.usenet.nntp

import java.io.IOException

/**
 * Failover order and the negative cache (ARCHITECTURE §10 Failover): primaries first, by priority group
 * (lower = preferred), then backups the same way. Inside a group, healthy providers come first and load is
 * shared by [ProviderWorkerPool.load]. A provider that answered 430 for an article is skipped for it for
 * [negativeTtlMs].
 */
class ProviderSelector(
    private val clock: () -> Long = monotonicMillis,
    private val negativeTtlMs: Long = NEGATIVE_TTL_MS,
    private val maxNegativeEntries: Int = MAX_NEGATIVE_ENTRIES,
) {
    private val negative = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > maxNegativeEntries
    }

    fun order(pools: List<ProviderWorkerPool>): List<ProviderWorkerPool> {
        val (backups, primaries) = pools.partition { it.endpoint.isBackup }
        return tier(primaries) + tier(backups)
    }

    private fun tier(pools: List<ProviderWorkerPool>): List<ProviderWorkerPool> =
        pools.groupBy { it.endpoint.priority }
            .toSortedMap()
            .values
            .flatMap { group -> group.sortedWith(compareBy<ProviderWorkerPool>({ !it.healthy }, { it.load() })) }

    @Synchronized
    fun isKnownMissing(pool: ProviderWorkerPool, messageId: String): Boolean {
        val key = key(pool, messageId)
        val until = negative[key] ?: return false
        if (until > clock()) return true
        negative.remove(key)
        return false
    }

    @Synchronized
    fun recordMissing(pool: ProviderWorkerPool, messageId: String) {
        negative[key(pool, messageId)] = clock() + negativeTtlMs
    }

    /**
     * Runs [submit] against each candidate in turn until one succeeds, completing [outer]. A 430 is remembered
     * and moves on; any other failure (auth, limit, breaker, CRC) moves on too. With none left, the result is
     * [ArticleNotFoundException] if any provider answered 430 (`allProviders` when all did), else the last error.
     */
    fun <T> failover(
        outer: NntpFuture<T>,
        messageId: String,
        candidates: List<ProviderWorkerPool>,
        submit: (ProviderWorkerPool) -> NntpFuture<T>,
    ) = attempt(outer, messageId, candidates, 0, Outcome(), submit)

    private class Outcome {
        var sawMissing = false
        var allMissing = true
        var lastError: IOException? = null
    }

    private fun <T> attempt(
        outer: NntpFuture<T>,
        messageId: String,
        candidates: List<ProviderWorkerPool>,
        from: Int,
        outcome: Outcome,
        submit: (ProviderWorkerPool) -> NntpFuture<T>,
    ) {
        var index = from
        while (index < candidates.size && isKnownMissing(candidates[index], messageId)) {
            outcome.sawMissing = true
            index++
        }
        if (outer.isDone) return
        if (index >= candidates.size) {
            outer.fail(
                when {
                    outcome.sawMissing -> ArticleNotFoundException(messageId, outcome.allMissing)
                    else -> outcome.lastError ?: NntpException(NntpErrorKind.NO_PROVIDERS, "No Usenet provider is enabled")
                },
            )
            return
        }
        val pool = candidates[index]
        val inner = submit(pool)
        outer.onDone { if (it.cancelled) inner.cancel() }
        inner.onDone { done ->
            val value = done.result
            val error = done.failure
            when {
                error == null -> outer.complete(checkNotNullResult(value))
                outer.cancelled -> Unit
                error is ProviderMissingException -> {
                    recordMissing(pool, messageId)
                    outcome.sawMissing = true
                    attempt(outer, messageId, candidates, index + 1, outcome, submit)
                }
                else -> {
                    outcome.allMissing = false
                    outcome.lastError = error
                    attempt(outer, messageId, candidates, index + 1, outcome, submit)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> checkNotNullResult(value: T?): T = value as T

    private fun key(pool: ProviderWorkerPool, messageId: String) = pool.endpoint.fingerprint + '|' + messageId

    companion object {
        const val NEGATIVE_TTL_MS = 60_000L
        const val MAX_NEGATIVE_ENTRIES = 20_000
    }
}
