package com.seastreams.tv.domain.playback.skip

/** What one tick of the player's prompts does. */
sealed interface PromptStep {
    /** Show [prompt] (`null`: nothing on screen); [countdownMs] while a Next episode countdown runs. */
    data class Show(val prompt: Prompt?, val countdownMs: Long? = null) : PromptStep

    /** Act on [prompt] without a press: auto-skip, or a countdown that ran out. */
    data class Act(val prompt: Prompt) : PromptStep
}

/**
 * The prompt state machine behind `PlayerPrompts` (PRD §9 *Skip & Next Episode Prompts*), free of Android so it
 * is unit-tested with virtual time: auto-skip once per stretch, the Next episode countdown, the visibility window,
 * and `Back` dismissal. [current] is the prompt on screen, the one `OK` acts on and `Back` dismisses.
 */
class PromptPresenter(
    private val window: PromptWindow = PromptWindow(),
    private val autoSkip: AutoSkipTracker = AutoSkipTracker(),
    private val countdown: PromptCountdown = PromptCountdown(),
) {
    var current: Prompt? = null
        private set

    /**
     * One tick for the policy's [prompt]. [countdownAllowed] = Auto-Play Next Episode applies, so a countdown that
     * runs out plays the next episode; [panelOpen] = a right-hand overlay is open (no prompt over it).
     */
    fun step(prompt: Prompt?, hudVisible: Boolean, playing: Boolean, panelOpen: Boolean, countdownAllowed: Boolean, nowMs: Long): PromptStep {
        if (prompt != null && autoSkip.shouldAct(prompt)) {
            current = null
            return PromptStep.Act(prompt)
        }
        // An auto stretch already skipped once (the user went back into it) is offered as a button instead.
        val shown = prompt?.copy(auto = false)
        val remaining = countdown.update(shown, playing, nowMs)
        if (remaining == 0L && shown != null && countdownAllowed) {
            current = null
            return PromptStep.Act(shown.copy(auto = true))
        }
        val visible = !panelOpen && window.visible(shown, hudVisible, playing, countdown.counting, nowMs)
        current = shown?.takeIf { visible }
        return PromptStep.Show(current, remaining?.takeIf { visible && it > 0 })
    }

    /**
     * `Back` on the prompt on screen: it stays hidden for the rest of its stretch (also with the HUD open), and a
     * running Next episode countdown stops. Returns whether a prompt was dismissed.
     */
    fun dismiss(): Boolean {
        val prompt = current ?: return false
        window.dismiss(prompt)
        countdown.cancel()
        current = null
        return true
    }

    /** Any remote key: a running countdown becomes a plain button. */
    fun cancelCountdown() = countdown.cancel()

    /** Nothing may show right now (loading, failed stream, switch in flight). */
    fun hide() {
        current = null
    }

    /** A new title or the end of the session. */
    fun reset() {
        window.reset()
        autoSkip.reset()
        countdown.reset()
        current = null
    }
}
