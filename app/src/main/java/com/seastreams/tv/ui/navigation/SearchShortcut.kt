package com.seastreams.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * The overlay stack after a long `Back` press opens Search (PRD §1): Search on top of whatever page
 * was showing, so a later `Back` from Search returns there. Already on Search, the stack is unchanged
 * (no pop, no second Search) and only the microphone is refocused.
 */
fun overlaysForSearch(overlays: List<Overlay>): List<Overlay> =
    if (overlays.lastOrNull() == Overlay.Search) overlays else overlays + Overlay.Search

/**
 * Points [router] at [action] while [allowed], and clears it otherwise or when this leaves composition
 * (profile switch), so the activity never defers `Back` where the shortcut must not run.
 */
@Composable
fun RegisterBackLongPress(router: BackLongPressRouter, allowed: Boolean, action: () -> Unit) {
    val currentAction by rememberUpdatedState(action)
    DisposableEffect(router, allowed) {
        val handler = { currentAction() }
        router.onLongPress = if (allowed) handler else null
        onDispose { if (router.onLongPress === handler) router.onLongPress = null }
    }
}
