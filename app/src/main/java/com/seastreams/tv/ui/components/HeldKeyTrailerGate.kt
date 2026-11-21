package com.seastreams.tv.ui.components

/**
 * Held-key handling for a catalog row's inline trailer (PRD §4). Every held repeat (`repeatCount > 0`)
 * cancels the pending preview; normally the card focus lands on re-arms it ([onCardArmed]). A repeat
 * that moves no focus (held `Left` stopped on item 0, held `Right` on the last card or `View More`, or
 * a repeat the row's throttle swallowed) leaves the settled card un-armed, so the key's release
 * ([onKeyUp]) re-arms the hover delay on it instead. The re-arm only restarts the countdown: moving
 * away right after release cancels it as usual.
 */
class HeldKeyTrailerGate {
    private var cancelledWithoutRearm = false

    /**
     * A key-down reached the row; `true` = a held repeat, so cancel the pending/playing preview now.
     * A fresh press starts a new hold, forgetting any earlier one that ended elsewhere.
     */
    fun onKeyDown(repeatCount: Int): Boolean {
        cancelledWithoutRearm = repeatCount > 0
        return cancelledWithoutRearm
    }

    /** A poster gained focus and restarted its hover delay. */
    fun onCardArmed() {
        cancelledWithoutRearm = false
    }

    /** A D-pad release while the row holds focus; `true` (once) = a held repeat cancelled the preview and nothing re-armed it. */
    fun onKeyUp(): Boolean {
        val rearm = cancelledWithoutRearm
        cancelledWithoutRearm = false
        return rearm
    }
}
