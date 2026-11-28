package com.seastreams.tv.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.domain.playback.skip.PromptKind
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.screens.playback.RESTORE_FRAMES
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import kotlinx.coroutines.delay

/**
 * The Skip / Next episode prompt (PRD §9 *Skip & Next Episode Prompts*), drawn inside the player's HDR-dimmed UI
 * layer. With the HUD hidden it sits bottom-right and takes focus when it appears (never within
 * [FOCUS_QUIET_MS] of a key press, so seeking is never interrupted); `OK` acts, and every other key bubbles to the
 * player root (`Left`/`Right` seek and open the HUD, `Up`/`Down` open it; `Back` dismisses the prompt first, see `playerBackAction`). With the
 * HUD visible it sits above the seek row and is reached with `Up` from the seek bar. `ignoreInitialKeyUp` means
 * the release of a press that started elsewhere (the `OK` that opened the HUD) never fires it.
 */
@Composable
fun PlayerPromptOverlay(
    prompt: PlayerPromptUi?,
    hudVisible: Boolean,
    hudBottomBlock: Dp?,
    requester: FocusRequester,
    lastKeyAtMs: () -> Long,
    nowMs: () -> Long,
    onFocusedChange: (Boolean) -> Unit,
    onAct: () -> Unit,
) {
    val current by rememberUpdatedState(prompt)
    LaunchedEffect(prompt?.kind, hudVisible) {
        if (prompt == null || hudVisible) return@LaunchedEffect
        while (nowMs() - lastKeyAtMs() < FOCUS_QUIET_MS) delay(FOCUS_POLL_MS)
        retryEachFrame(RESTORE_FRAMES) { requester.tryRequestFocus() }
    }
    if (prompt == null) return
    val bottom = if (hudVisible) (hudBottomBlock ?: HUD_FALLBACK_HEIGHT) + HUD_GAP else HIDDEN_BOTTOM
    Box(Modifier.fillMaxSize()) {
        Surface(
            onClick = onAct,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = SIDE_PADDING, bottom = bottom)
                .focusRequester(requester)
                // A focus loss while the prompt is going away is not a move to another control.
                .onFocusChanged { if (it.isFocused) onFocusedChange(true) else if (current != null) onFocusedChange(false) }
                .ignoreInitialKeyUp(),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            colors = focusPillColors(containerColor = Color.Black.copy(alpha = 0.7f)),
        ) {
            Row(
                Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(SeaIcons.SkipNext, contentDescription = null, modifier = Modifier.size(22.dp))
                Column {
                    Text(prompt.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    promptCaption(prompt)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

/** `S1:E4 · Playing in 7s` under Next episode; nothing under a skip. */
internal fun promptCaption(prompt: PlayerPromptUi): String? {
    if (prompt.kind != PromptKind.NEXT_EPISODE) return null
    val countdown = prompt.countdownSeconds?.let { "Playing in ${it}s" }
    return listOfNotNull(prompt.detail, countdown).joinToString(" · ").ifEmpty { null }
}

/** No focus grab this soon after a key press (seeking, scrubbing, opening the HUD). */
internal const val FOCUS_QUIET_MS = 1_500L
private const val FOCUS_POLL_MS = 250L
private val SIDE_PADDING = 48.dp
private val HIDDEN_BOTTOM = 56.dp
private val HUD_GAP = 12.dp
private val HUD_FALLBACK_HEIGHT = 150.dp
