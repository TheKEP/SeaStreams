package com.seastreams.tv.usenet.nntp

/**
 * Rests a failing provider (PRD §12 *Circuit breaker threshold / cooldown*): after [threshold] consecutive
 * connection failures it opens for [cooldownMs]. Once the cooldown passes it is half-open: the next attempt
 * runs, and a failure re-opens it at once while a success closes it.
 */
class CircuitBreaker(
    private val threshold: Int,
    private val cooldownMs: Long,
    private val clock: () -> Long = monotonicMillis,
) {
    private var failures = 0
    private var openUntil = Long.MIN_VALUE

    /** Counts a failure; `true` when this one opened the breaker. */
    @Synchronized
    fun recordFailure(): Boolean {
        failures++
        if (failures < threshold) return false
        val wasOpen = openUntil > clock()
        openUntil = clock() + cooldownMs
        failures = threshold - 1
        return !wasOpen
    }

    @Synchronized
    fun recordSuccess() {
        failures = 0
        openUntil = Long.MIN_VALUE
    }

    @Synchronized
    fun isOpen(): Boolean = openUntil > clock()

    @Synchronized
    fun remainingMs(): Long = (openUntil - clock()).coerceAtLeast(0)
}
