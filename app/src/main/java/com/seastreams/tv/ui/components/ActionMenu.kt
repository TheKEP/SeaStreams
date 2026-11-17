package com.seastreams.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.tryRequestFocus

/** One choice in an [ActionMenu]. */
data class MenuAction(val label: String, val onClick: () -> Unit)

/** A long-press menu: [actions] are fixed when it opens; [returnFocus] is the focusable that opened it. */
class ActionMenuRequest(
    val title: String,
    val subtitle: String?,
    val actions: List<MenuAction>,
    val returnFocus: FocusRequester,
)

/**
 * Centered long-press menu over a dimmed screen. Focus starts on the first action and can't leave the
 * menu; a fresh `Down` on the last action wraps to the first. Choosing an action or pressing `Back`
 * hands focus back to [ActionMenuRequest.returnFocus] before [onClose] removes the menu, so the
 * opener is focused again (or the stream list, opened by the action, takes over from there).
 *
 * The `OK` press that opened the menu is still held when it appears: its repeats and release are
 * swallowed until a fresh key press, so letting go never picks the first action by accident.
 */
@Composable
fun ActionMenu(request: ActionMenuRequest, onClose: () -> Unit) {
    val close: () -> Unit = {
        request.returnFocus.tryRequestFocus()
        onClose()
    }
    BackHandler(onBack = close)
    val first = remember { FocusRequester() }
    val armed = remember { mutableStateOf(false) }
    LaunchedEffect(request) { first.requestFocusAfterFrame() }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(MenuWidth)
                .background(SurfaceCard, RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection != FocusDirection.Enter && requestedFocusDirection != FocusDirection.Exit) {
                            cancelFocusChange()
                        }
                    }
                }
                .onPreviewKeyEvent { event -> swallowOpeningPress(event, armed) }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(request.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            request.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            request.actions.forEachIndexed { index, action ->
                key(action.label) {
                    MenuEntry(
                        label = action.label,
                        onClick = {
                            close()
                            action.onClick()
                        },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.padding(top = 8.dp).focusRequester(first) else Modifier)
                            .then(
                                if (index == request.actions.lastIndex) {
                                    Modifier.onDpadEdgeWrap(Key.DirectionDown) { first.tryRequestFocus() }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** `true` (consumed) for the held opening `OK` press; any fresh key press arms the menu. */
private fun swallowOpeningPress(event: KeyEvent, armed: MutableState<Boolean>): Boolean {
    if (armed.value) return false
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
        armed.value = true
        return false
    }
    return event.key in SelectKeys
}

@Composable
private fun MenuEntry(label: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = focusPillColors(containerColor = Color.White.copy(alpha = 0.08f)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

private val MenuWidth = 420.dp
private val SelectKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
