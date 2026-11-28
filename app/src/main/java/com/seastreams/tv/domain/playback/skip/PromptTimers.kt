package com.seastreams.tv.domain.playback.skip

/** A prompt's identity: one kind for one stretch of the file. */
private fun Prompt.key(): Pair<PromptKind, Long> = kind to anchorMs

/**
 * How long a prompt stays on screen (PRD §9): [windowMs] of unpaused, HUD-hidden playback after it first appears.
 * It shows again whenever the HUD is visible, and a counting-down Next episode stays until it acts or is
 * cancelled. A prompt dismissed with `Back` ([dismiss]) never shows again for that stretch of this title, HUD or not.
 * Clock values come from the caller, so tests drive it with virtual time.
 */
class PromptWindow(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private var key: Pair<PromptKind, Long>? = null
    private val dismissed = mutableSetOf<Pair<PromptKind, Long>>()
    private var shownMs = 0L
    private var lastAtMs = 0L

    fun visible(prompt: Prompt?, hudVisible: Boolean, playing: Boolean, countingDown: Boolean, nowMs: Long): Boolean {
        if (prompt == null) {
            key = null
            return false
        }
        if (prompt.key() != key) {
            key = prompt.key()
            shownMs = 0
            lastAtMs = nowMs
        }
        if (!hudVisible && playing) shownMs += (nowMs - lastAtMs).coerceAtLeast(0)
        lastAtMs = nowMs
        if (prompt.key() in dismissed) return false
        return hudVisible || countingDown || shownMs < windowMs
    }

    /** `Back` on [prompt]: hidden for that stretch from now on (a new title resets this). */
    fun dismiss(prompt: Prompt) {
        dismissed += prompt.key()
    }

    fun reset() {
        key = null
        dismissed.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 8_000L
    }
}

/** Auto-skip acts once per stretch: rewinding into an intro that was already auto-skipped plays it. */
class AutoSkipTracker {
    private val done = mutableSetOf<Pair<PromptKind, Long>>()

    /** `true` the first time [prompt] (an auto prompt) is seen; then never again for the same stretch. */
    fun shouldAct(prompt: Prompt): Boolean = prompt.auto && done.add(prompt.key())

    fun reset() = done.clear()
}

/**
 * The Next episode countdown (PRD §9): [totalMs] of unpaused playback, then it fires once. A key press
 * ([cancel]) turns it into a plain button for the rest of that stretch.
 */
class PromptCountdown(private val totalMs: Long = DEFAULT_COUNTDOWN_MS) {
    private var key: Pair<PromptKind, Long>? = null
    private var remainingMs = totalMs
    private var lastAtMs = 0L
    private var cancelled = false
    private var fired = false

    /** Remaining ms while counting, `0` exactly once when it runs out, else `null` (not counting). */
    fun update(prompt: Prompt?, playing: Boolean, nowMs: Long): Long? {
        if (prompt == null || !prompt.countdown) {
            key = null
            return null
        }
        if (prompt.key() != key) {
            key = prompt.key()
            remainingMs = totalMs
            cancelled = false
            fired = false
            lastAtMs = nowMs
        }
        if (cancelled || fired) return null
        if (playing) remainingMs -= (nowMs - lastAtMs).coerceAtLeast(0)
        lastAtMs = nowMs
        if (remainingMs > 0) return remainingMs
        fired = true
        return 0
    }

    /** Whether a countdown is running right now (the next key press cancels it). */
    val counting: Boolean get() = key != null && !cancelled && !fired

    fun cancel() {
        if (key != null) cancelled = true
    }

    fun reset() {
        key = null
    }

    companion object {
        const val DEFAULT_COUNTDOWN_MS = 10_000L
    }
}
