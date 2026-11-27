package com.seastreams.tv.usenet.nntp

/**
 * Deterministic HIGH/LOW share: when both classes wait, `1 - highShare` of picks go to LOW (rolling
 * accumulator), so background work progresses without starving playback. `highShare = 1` = strict priority.
 */
internal class PriorityShare(highShare: Double) {
    private val lowOdds = Math.round((1 - highShare.coerceIn(0.0, 1.0)) * 100).toInt()
    private var accumulator = 0

    /** Whether this contended pick serves LOW. Not thread-safe: callers hold their own lock. */
    fun pickLow(): Boolean {
        if (lowOdds <= 0) return false
        accumulator += lowOdds
        if (accumulator < 100) return false
        accumulator -= 100
        return true
    }
}

/** One granted permit; [release] is idempotent. */
class Permit internal constructor(private val owner: PrioritySemaphore) {
    @Volatile
    private var released = false

    fun release() {
        if (released) return
        synchronized(this) {
            if (released) return
            released = true
        }
        owner.release()
    }
}

/** A queued [PrioritySemaphore.acquire]; [cancel] withdraws it (no effect once granted). */
fun interface Cancellable {
    fun cancel()
}

/**
 * The global download budget (`maxConcurrentDownloads`, ARCHITECTURE §10): a counting semaphore whose waiters
 * are served HIGH before LOW, FIFO within a class, with contended grants shared at `streamingPriority`.
 * [throttleTo] lowers the effective ceiling (≥ 1) without preempting held permits; [restore] raises it back.
 * Grants are delivered by callback outside the lock, so an engine thread never blocks on it.
 */
class PrioritySemaphore(val capacity: Int, highShare: Double = 1.0) {
    private inner class Waiter(val priority: FetchPriority, val onGrant: (Permit) -> Unit)

    private val high = ArrayDeque<Waiter>()
    private val low = ArrayDeque<Waiter>()
    private val share = PriorityShare(highShare)
    private var limit = capacity.coerceAtLeast(1)
    private var used = 0

    val inUse: Int @Synchronized get() = used
    val waiting: Int @Synchronized get() = high.size + low.size
    val effectiveLimit: Int @Synchronized get() = limit

    fun acquire(priority: FetchPriority, onGrant: (Permit) -> Unit): Cancellable {
        val waiter = Waiter(priority, onGrant)
        val granted = synchronized(this) {
            if (used < limit && high.isEmpty() && low.isEmpty()) {
                used++
                true
            } else {
                (if (priority == FetchPriority.HIGH) high else low).addLast(waiter)
                false
            }
        }
        if (granted) onGrant(Permit(this))
        return Cancellable { synchronized(this) { high.remove(waiter) || low.remove(waiter) } }
    }

    fun throttleTo(permits: Int) {
        val grants = synchronized(this) {
            limit = permits.coerceIn(1, capacity.coerceAtLeast(1))
            drainLocked()
        }
        grants.forEach { it.onGrant(Permit(this)) }
    }

    fun restore() = throttleTo(capacity)

    internal fun release() {
        val grants = synchronized(this) {
            used--
            drainLocked()
        }
        grants.forEach { it.onGrant(Permit(this)) }
    }

    private fun drainLocked(): List<Waiter> {
        val out = ArrayList<Waiter>()
        while (used < limit) {
            val next = next() ?: break
            used++
            out += next
        }
        return out
    }

    private fun next(): Waiter? = when {
        high.isNotEmpty() && low.isNotEmpty() -> if (share.pickLow()) low.removeFirst() else high.removeFirst()
        high.isNotEmpty() -> high.removeFirst()
        else -> low.removeFirstOrNull()
    }
}
