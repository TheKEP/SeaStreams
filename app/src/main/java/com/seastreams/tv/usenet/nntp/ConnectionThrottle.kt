package com.seastreams.tv.usenet.nntp

/**
 * The AIMD connection-limit throttle (ARCHITECTURE §10): a "too many connections" answer drops the allowed
 * connections to the number open, then one more is allowed every [stepMs] up to [max].
 */
class ConnectionThrottle(
    val max: Int,
    private val stepMs: Long = STEP_MS,
    private val clock: () -> Long = monotonicMillis,
) {
    private var base = max
    private var since = 0L

    @Synchronized
    fun allowed(): Int {
        if (base >= max) return max
        val grown = base + ((clock() - since) / stepMs).toInt()
        if (grown >= max) base = max
        return grown.coerceAtMost(max)
    }

    /** A dial was refused for the connection limit while [open] connections were up. */
    @Synchronized
    fun onLimit(open: Int) {
        base = minOf(allowed(), open).coerceAtLeast(1)
        since = clock()
    }

    val throttled: Boolean get() = allowed() < max

    companion object {
        const val STEP_MS = 5_000L
    }
}
