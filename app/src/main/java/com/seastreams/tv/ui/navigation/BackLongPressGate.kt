package com.seastreams.tv.ui.navigation

import com.seastreams.tv.ui.screens.playback.PlaybackUiState

/** What the activity does with one key event after asking [BackLongPressGate]. */
enum class BackKeyDecision {
    /** Not ours (or the shortcut is off): dispatch it normally, untouched. */
    PASS,

    /** A fresh `Back` press: hold a copy back and start the long-press timer; consume the original. */
    DEFER,

    /** Swallow it: a held repeat, or the release of a press that already fired the long-press. */
    CONSUME,

    /** A short press ended: dispatch the held `KeyDown` copy, then this `KeyUp`, as if never deferred. */
    REPLAY,
}

/**
 * Tells a short `Back` press from a held one before anything in the UI reacts (PRD §1 Long-press
 * Back → Search). The activity defers a fresh `Back` `KeyDown` while the shortcut is [enabled]:
 * released before the long-press timeout, the press is replayed so every existing short-`Back`
 * action runs unchanged; held past it ([onTimeout], or a repeat flagged as a long press), the
 * shortcut fires once and every later repeat plus the trailing `KeyUp` is consumed, so nothing
 * clicks or pops on the screen the shortcut opened.
 *
 * Pure state, no Android types, so the key handling is unit-tested on the JVM.
 */
class BackLongPressGate {
    private enum class State { IDLE, PENDING, FIRED }

    private var state = State.IDLE

    /** Whether a deferred press is waiting for its release or the timeout. */
    val isPending: Boolean get() = state == State.PENDING

    /**
     * Decides one key event. [enabled] is whether the shortcut may run right now (a profile is open
     * and nothing is playing). [isLongPress] is the platform's long-press flag on a held repeat.
     * [onFire] runs when this event completes a long press.
     */
    fun onKey(
        isBack: Boolean,
        isKeyDown: Boolean,
        repeatCount: Int,
        isLongPress: Boolean,
        isCanceled: Boolean,
        enabled: Boolean,
        onFire: () -> Unit,
    ): BackKeyDecision {
        if (!isBack) return BackKeyDecision.PASS
        return when (state) {
            State.IDLE -> if (isKeyDown && repeatCount == 0 && enabled) {
                state = State.PENDING
                BackKeyDecision.DEFER
            } else {
                // Shortcut off, or the tail of a press we never deferred (e.g. one held in the player).
                BackKeyDecision.PASS
            }
            State.PENDING -> when {
                !isKeyDown -> {
                    state = State.IDLE
                    if (isCanceled) BackKeyDecision.CONSUME else BackKeyDecision.REPLAY
                }
                isLongPress && enabled -> {
                    state = State.FIRED
                    onFire()
                    BackKeyDecision.CONSUME
                }
                else -> BackKeyDecision.CONSUME
            }
            State.FIRED -> {
                if (!isKeyDown) state = State.IDLE
                BackKeyDecision.CONSUME
            }
        }
    }

    /**
     * The long-press timeout passed with `Back` still down. Returns `true` (fire the shortcut) only
     * for a pending press while the shortcut is [enabled]; otherwise the press stays pending, so its
     * release still replays as an ordinary short `Back`.
     */
    fun onTimeout(enabled: Boolean): Boolean {
        if (state != State.PENDING || !enabled) return false
        state = State.FIRED
        return true
    }

    /** Forgets a deferred press without replaying it (window lost focus, activity paused). */
    fun reset() {
        state = State.IDLE
    }
}

/**
 * Where a long `Back` press goes. [ProfileShell] sets [onLongPress] only while the shortcut is allowed
 * ([backLongPressAllowed]); it is `null` on the splash, onboarding and the Profile Gate, so `Back`
 * there reaches the screen untouched and can't skip profile selection.
 */
class BackLongPressRouter {
    var onLongPress: (() -> Unit)? = null

    val enabled: Boolean get() = onLongPress != null
}

/**
 * Long-press `Back` → Search is off while anything playback-related owns the screen: the built-in
 * player (media and full-screen trailers alike), the stream-lookup loading screen, the stream
 * selection modal, and the Continue Watching `Finding streams…` lookup. Those keep their own `Back`.
 */
fun backLongPressAllowed(state: PlaybackUiState): Boolean =
    state.player == null && state.loading == null && state.picker == null && state.busyLabel == null
