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
 * Pure coroutine logic (no ExoPlayer types) so the timing is unit-tested with virtual time. Calls come
 * from the main thread.
 */
class StreamFallbackController(
    private val scope: CoroutineScope,
    private val timeoutMs: () -> Long,
    private val onAdvance: (fromIndex: Int, toIndex: Int, reason: FallbackReason) -> Unit,
    private val onExhausted: (reason: FallbackReason) -> Unit,
) {
    private var timer: Job? = null
    private var current = NO_STREAM
    private var total = 0
    private var readyOnce = false

    /** Disabled for trailers: errors and timeouts are left to the caller. */
    var enabled: Boolean = true

    /** Stream [index] of [count] was handed to the player; starts its timeout. */
    fun onStreamStarted(index: Int, count: Int) {
        timer?.cancel()
        current = index
        total = count
        readyOnce = false
        if (!enabled) return
        val started = index
        timer = scope.launch {
            delay(timeoutMs())
            if (current == started && !readyOnce) advance(FallbackReason.TIMEOUT)
        }
    }

    /** The current stream reached `STATE_READY`: it is playable, so the timeout no longer applies. */
    fun onReady() {
        readyOnce = true
        timer?.cancel()
        timer = null
    }

    /** A playback or decoder error on the current stream: move on at once. */
    fun onError() {
        timer?.cancel()
        timer = null
        if (enabled && current != NO_STREAM) advance(FallbackReason.ERROR)
    }

    /** Playback stopped (exit, episode switch): no timer may fire for the old stream. */
    fun cancel() {
        timer?.cancel()
        timer = null
        current = NO_STREAM
    }

    private fun advance(reason: FallbackReason) {
        val from = current
        val next = from + 1
        if (next >= total) {
            current = NO_STREAM
            onExhausted(reason)
        } else {
            onAdvance(from, next, reason)
        }
    }

    private companion object {
        const val NO_STREAM = -1
    }
}
