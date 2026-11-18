package com.seastreams.tv.ui.screens.player

/** What a fresh `Enter`/`DPAD_CENTER` press does while the Player HUD is hidden (PRD §9). */
enum class HiddenHudSelect {
    /** Media is playing: pause, then open the HUD. */
    PAUSE_AND_SHOW,

    /** Already paused: just open the HUD. */
    SHOW,
}

/**
 * Handles the `Enter` press that opens the Player HUD. The HUD opens on that press's `KeyDown` and
 * focuses `Play/Pause`, and the key is still held at that point. This gate remembers the press so
 * the player root, which sees every key event first, can consume its repeats and its trailing
 * `KeyUp`. Without that, letting go would click `Play/Pause` and un-pause the video.
 */
class PlayerSelectKeyGate {
    private var openingPressHeld = false

    /**
     * Preview pass of a select-key event, before any HUD control sees it. Returns `true` (consume)
     * for the repeats and release of the press that opened the HUD. A fresh press forgets any
     * earlier press (e.g. one whose `KeyUp` never arrived).
     */
    fun consumeHeldPress(isKeyDown: Boolean, repeatCount: Int): Boolean = when {
        isKeyDown && repeatCount == 0 -> {
            openingPressHeld = false
            false
        }
        isKeyDown -> openingPressHeld
        else -> openingPressHeld.also { openingPressHeld = false }
    }

    /** A select-key `KeyDown` reaching the root with the HUD hidden; `null` for a held repeat. */
    fun onHiddenHudSelectDown(repeatCount: Int, playing: Boolean): HiddenHudSelect? {
        if (repeatCount > 0) return null
        openingPressHeld = true
        return if (playing) HiddenHudSelect.PAUSE_AND_SHOW else HiddenHudSelect.SHOW
    }
}
