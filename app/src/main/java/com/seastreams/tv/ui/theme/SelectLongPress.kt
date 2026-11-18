package com.seastreams.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement

/** What a select-key `KeyDown` does on a control with [Modifier.selectLongPress]. */
enum class LongPressAction {
    /** Let the control see it (a fresh press: the Surface shows its pressed state). */
    PASS,

    /** Swallow it (a held repeat after the long-press fired, or one from a press that began elsewhere). */
    CONSUME,

    /** Swallow it and run the long-press action. */
    LONG_PRESS,
}

/**
 * Long-press detection for a select key (`OK`/`Enter`) whose state lives only as long as focus stays
 * on the control (PRD §1). tv-material `Surface`'s own `onLongClick` keeps a long-click flag that is
 * cleared only by a `KeyUp` on that Surface: when the long-press opens a modal that takes focus, the
 * release lands in the modal, the flag stays set, and the next click on the Surface is swallowed. Here
 * any focus change forgets the press, and the Surface never sees a repeat at all.
 */
class SelectLongPressGuard {
    private var pressSeen = false
    private var longPressFired = false

    fun onFocusChanged() {
        pressSeen = false
        longPressFired = false
    }

    fun onSelectKeyDown(repeatCount: Int): LongPressAction = when {
        repeatCount == 0 -> {
            pressSeen = true
            longPressFired = false
            LongPressAction.PASS
        }
        pressSeen && !longPressFired -> {
            longPressFired = true
            LongPressAction.LONG_PRESS
        }
        else -> LongPressAction.CONSUME
    }

    /**
     * Called from the control's `onClick` (on `KeyUp`): `true` when this release ends a press whose
     * long-press already ran, so the click must not run too. Either way the press is over.
     */
    fun consumeClick(): Boolean {
        val suppress = longPressFired
        pressSeen = false
        longPressFired = false
        return suppress
    }
}

/** A [SelectLongPressGuard] that lives as long as the control. */
@Composable
fun rememberSelectLongPress(): SelectLongPressGuard = remember { SelectLongPressGuard() }

/**
 * Runs [onLongClick] when `OK`/`Enter` is held on this control. Use instead of a tv `Surface`'s
 * `onLongClick`, and wrap the Surface's `onClick` as `{ if (!guard.consumeClick()) onClick() }`.
 * A `null` [onLongClick] leaves the control untouched.
 */
fun Modifier.selectLongPress(guard: SelectLongPressGuard, onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick == null) this else this then SelectLongPressElement(guard, onLongClick)

private data class SelectLongPressElement(val guard: SelectLongPressGuard, val onLongClick: () -> Unit) :
    ModifierNodeElement<SelectLongPressNode>() {
    override fun create() = SelectLongPressNode(guard, onLongClick)

    override fun update(node: SelectLongPressNode) {
        node.guard = guard
        node.onLongClick = onLongClick
    }
}

private class SelectLongPressNode(var guard: SelectLongPressGuard, var onLongClick: () -> Unit) :
    Modifier.Node(), KeyInputModifierNode, FocusEventModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused != focused) {
            focused = focusState.isFocused
            guard.onFocusChanged()
        }
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key !in SelectKeys) return false
        return when (guard.onSelectKeyDown(event.nativeKeyEvent.repeatCount)) {
            LongPressAction.PASS -> false
            LongPressAction.CONSUME -> true
            LongPressAction.LONG_PRESS -> {
                onLongClick()
                true
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = false
}
