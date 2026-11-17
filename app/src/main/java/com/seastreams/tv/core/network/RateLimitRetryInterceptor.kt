package com.seastreams.tv.core.network

import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.Interceptor
import okhttp3.Response

/** Blocking delay used between retries; replaced by a recording fake in tests. */
fun interface Sleeper {
    @Throws(InterruptedException::class)
    fun sleep(millis: Long)

    companion object {
        val THREAD: Sleeper = Sleeper { Thread.sleep(it) }
    }
}

/**
 * OkHttp application interceptor that retries HTTP `429 Too Many Requests` with exponential
 * backoff (`baseDelayMs * 2^attempt`, capped at [maxDelayMs]).
 *
 * A numeric `Retry-After` header (seconds) takes precedence over the computed backoff, but is still
 * capped at [maxDelayMs] so a hostile header cannot stall the UI. After [maxRetries] retries the
 * final 429 response is returned to the caller unchanged.
 */
class RateLimitRetryInterceptor(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val sleeper: Sleeper = Sleeper.THREAD,
) : Interceptor {

    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(baseDelayMs > 0) { "baseDelayMs must be > 0" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be >= baseDelayMs" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0
        while (response.code == HTTP_TOO_MANY_REQUESTS && attempt < maxRetries) {
            val delayMs = delayFor(attempt, response.header("Retry-After"))
            response.close()
            try {
                sleeper.sleep(delayMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while backing off from HTTP 429").apply {
                    initCause(interrupted)
                }
            }
            if (chain.call().isCanceled()) throw IOException("Canceled while backing off from HTTP 429")
            attempt++
            response = chain.proceed(request)
        }
        return response
    }

    /** Backoff for the given zero-based retry [attempt]. */
    fun delayFor(attempt: Int, retryAfterHeader: String?): Long {
        val retryAfterMs = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(MILLIS_PER_SECOND)
        if (retryAfterMs != null) return retryAfterMs.coerceAtMost(maxDelayMs)
        val shift = attempt.coerceAtMost(MAX_SHIFT)
        return (baseDelayMs shl shift).coerceAtMost(maxDelayMs)
    }

    companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_BASE_DELAY_MS = 1_000L
        const val DEFAULT_MAX_DELAY_MS = 16_000L

        // Keeps `baseDelayMs shl shift` far from Long overflow.
        private const val MAX_SHIFT = 20
    }
}
