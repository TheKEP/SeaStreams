package com.seastreams.tv.ui.screens.player

import androidx.compose.ui.input.key.Key

/**
 * Keys that mean `Back` to the player. Compose turns an unconsumed `Back`/`Escape` `KeyDown` into a
 * `FocusDirection.Exit` focus move, which would park focus on the (invisible) player root and swallow
 * the press before `BackHandler` sees it. The player root consumes them all in its preview pass instead.
 */
val PlayerBackKeys: Set<Key> = setOf(Key.Back, Key.Escape)

/** What one `Back` press does in the player (PRD §9), media and trailers alike. */
enum class PlayerBackAction {
    /** Tier 1, a sub-overlay is open: close it and refocus its bar button; playback continues. */
    CLOSE_PANEL,

    /** Tier 2, the main HUD is showing: hide only the HUD; playback continues. */
    HIDE_HUD,

    /** Tier 3, the HUD is hidden: leave playback. */
    EXIT,
}

fun playerBackAction(state: PlayerUiState): PlayerBackAction = when {
    state.panel != null -> PlayerBackAction.CLOSE_PANEL
    state.hudVisible -> PlayerBackAction.HIDE_HUD
    else -> PlayerBackAction.EXIT
}

/**
 * Acts on a `Back` press exactly once. The player root consumes every `Back` event (so focus never
 * moves to the root and the trailing `KeyUp` never reaches the screen underneath), but only a fresh
 * `KeyDown` acts; held repeats and the release do nothing.
 */
class PlayerBackKeyGate {
    fun onBackKey(isKeyDown: Boolean, repeatCount: Int): Boolean = isKeyDown && repeatCount == 0
}

/** Whether this overlay's pill is on the bottom action bar: never for trailers, Episodes only for episodes. */
fun PlayerPanel.onActionBar(state: PlayerUiState): Boolean = when {
    state.isTrailer -> false
    this == PlayerPanel.EPISODES -> state.isEpisode
    else -> true
}

/** The bar button that takes focus when [closed] closes, or `null` for `Play/Pause` (its pill isn't shown). */
fun panelReturnFocus(closed: PlayerPanel, state: PlayerUiState): PlayerPanel? = closed.takeIf { it.onActionBar(state) }
