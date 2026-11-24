package com.seastreams.tv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Why the player moved on from a stream. */
enum class FallbackReason { TIMEOUT, ERROR }

/**
 * The Stream Fallback Timer (PRD §9): each started stream must reach `STATE_READY` within
 * [timeoutMs]; if it doesn't, or it reports a playback/decoder error, the next stream in the resolved
 * list is started. When no stream is left, [onExhausted] runs.
 *
 * With *Automatic Stream Failover* off ([failover] returns `false` when a stream starts) no timer runs
 * and nothing advances: an error on the current stream calls [onStopped] with its index instead, and
 * the user picks the next stream by hand.
 *
 * Each callback carries the brief failure detail ([StreamFailureReason]) of the stream that just failed, `null` for a
 * timeout. After exhaustion or a stop there is no current stream, so a burst of late errors reports nothing more.
 *
 * Pure coroutine logic (no ExoPlayer types) so the timing is unit-tested with virtual time. Calls come
 * from the main thread.
 */
class StreamFallbackController(
    private val scope: CoroutineScope,
    private val timeoutMs: () -> Long,
    private val failover: () -> Boolean,
    private val onAdvance: (fromIndex: Int, toIndex: Int, reason: FallbackReason, detail: String?) -> Unit,
    private val onExhausted: (reason: FallbackReason, lastDetail: String?) -> Unit,
    private val onStopped: (index: Int, detail: String?) -> Unit,
) {
    private var timer: Job? = null
    private var current = NO_STREAM
    private var total = 0
    private var readyOnce = false
    private var failoverActive = false

    /** Disabled for trailers: errors and timeouts are left to the caller. */
    var enabled: Boolean = true

    /** Stream [index] of [count] was handed to the player; starts its timeout when failover is on. */
    fun onStreamStarted(index: Int, count: Int) {
        timer?.cancel()
        timer = null
        current = index
        total = count
        readyOnce = false
        failoverActive = enabled && failover()
        if (!failoverActive) return
        val started = index
        timer = scope.launch {
            delay(timeoutMs())
            if (current == started && !readyOnce) advance(FallbackReason.TIMEOUT, detail = null)
        }
    }

    /** The current stream reached `STATE_READY`: it is playable, so the timeout no longer applies. */
    fun onReady() {
        readyOnce = true
        timer?.cancel()
        timer = null
    }

    /**
     * A playback or decoder error on the current stream ([detail]: its brief reason): move on at once, or stop when
     * failover is off.
     */
    fun onError(detail: String? = null) {
        timer?.cancel()
        timer = null
        if (!enabled || current == NO_STREAM) return
        if (failoverActive) {
            advance(FallbackReason.ERROR, detail)
        } else {
            val failed = current
            current = NO_STREAM
            onStopped(failed, detail)
        }
    }

    /** Playback stopped (exit, episode switch): no timer may fire for the old stream. */
    fun cancel() {
        timer?.cancel()
        timer = null
        current = NO_STREAM
    }

    private fun advance(reason: FallbackReason, detail: String?) {
        val from = current
        val next = from + 1
        if (next >= total) {
            current = NO_STREAM
            onExhausted(reason, detail)
        } else {
            // Until the next stream is started, a duplicate error for this one must not advance twice.
            current = NO_STREAM
            onAdvance(from, next, reason, detail)
        }
    }

    private companion object {
        const val NO_STREAM = -1
    }
}
