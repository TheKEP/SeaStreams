package com.seastreams.tv.ui.screens.search

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/** Where focus goes after leaving the search field's edit mode (replayed from the field's surface). */
enum class FieldExitDirection { LEFT, RIGHT, UP, DOWN }

/** What a key does while the native search `EditText` is in edit mode. */
sealed interface FieldKeyAction {
    /** Let the `EditText` handle it (characters, caret moves inside the text). */
    data object Pass : FieldKeyAction

    /** Swallow it (a submit/Back press whose release does the work, or an orphan release). */
    data object Consume : FieldKeyAction

    /** Run the search and leave edit mode. */
    data object Submit : FieldKeyAction

    /** Leave edit mode; with a [direction], move focus that way from the field (null: stay on it). */
    data class Exit(val direction: FieldExitDirection?) : FieldKeyAction
}

/**
 * Hardware-key routing for the search field's edit mode (PRD §7, CLAUDE.md Rule 4):
 * - Enter / OK / Search submit on **release**, and only after their press was seen here, so neither
 *   a trailing release reaching the field afterwards nor an orphan release (from the IME or another
 *   window) ever clicks anything.
 * - `Back` leaves edit mode on release without leaving Search.
 * - `Left` at the very start, `Right` at the very end, `Up` and `Down` leave edit mode and replay
 *   the direction from the field, so the top bar's existing focus rules apply unchanged.
 */
class SearchFieldKeyRouter {
    private var submitPressSeen = false

    fun route(keyCode: Int, isDown: Boolean, selectionStart: Int, selectionEnd: Int, textLength: Int): FieldKeyAction {
        val noSelection = selectionStart == selectionEnd
        return when (keyCode) {
            in SUBMIT_KEYS -> if (isDown) {
                submitPressSeen = true
                FieldKeyAction.Consume
            } else if (submitPressSeen) {
                submitPressSeen = false
                FieldKeyAction.Submit
            } else {
                FieldKeyAction.Consume
            }
            KeyEvent.KEYCODE_BACK -> if (isDown) FieldKeyAction.Consume else FieldKeyAction.Exit(null)
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (isDown && noSelection && selectionStart <= 0) FieldKeyAction.Exit(FieldExitDirection.LEFT) else FieldKeyAction.Pass
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (isDown && noSelection && selectionEnd >= textLength) FieldKeyAction.Exit(FieldExitDirection.RIGHT) else FieldKeyAction.Pass
            KeyEvent.KEYCODE_DPAD_UP -> if (isDown) FieldKeyAction.Exit(FieldExitDirection.UP) else FieldKeyAction.Pass
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isDown) FieldKeyAction.Exit(FieldExitDirection.DOWN) else FieldKeyAction.Pass
            else -> FieldKeyAction.Pass
        }
    }

    /** Edit mode ended or restarted: a release from before must not submit. */
    fun reset() {
        submitPressSeen = false
    }

    private companion object {
        val SUBMIT_KEYS = setOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SEARCH,
        )
    }
}

/**
 * The editor `imeOptions` handed to the keyboard: a plain Search action. Clears any other action
 * and the `NAVIGATE_NEXT` / `NAVIGATE_PREVIOUS` flags `TextView` adds when focusables exist around
 * it (FireTVIME labels its action key "Next" from them), plus `NO_EXTRACT_UI`; keeps other flags.
 */
fun searchEditorImeOptions(imeOptions: Int): Int {
    val stripped = EditorInfo.IME_MASK_ACTION or EditorInfo.IME_FLAG_NAVIGATE_NEXT or
        EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    return (imeOptions and stripped.inv()) or EditorInfo.IME_ACTION_SEARCH
}

/** Upper bound of keyboard-show attempts per open (~200 ms of 50 ms retries). */
const val MAX_IME_SHOW_ATTEMPTS = 5

/**
 * Whether [FieldImeOpener] tries to show the keyboard again after [attempt]: only while the field is
 * still [editing], the IME is not yet active on it, and fewer than [MAX_IME_SHOW_ATTEMPTS] ran.
 */
fun shouldRetryImeShow(attempt: Int, imeActive: Boolean, editing: Boolean): Boolean =
    editing && !imeActive && attempt < MAX_IME_SHOW_ATTEMPTS

/**
 * Two-way sync between the ViewModel query and the native `EditText` without feedback loops,
 * caret jumps or lost keystrokes. The `TextWatcher` reports each user edit ([onUserEdit]); the
 * composable's update pass asks [textToApply] whether the external query must be written into
 * the `EditText`. Only a change of the external value itself that is not an echo of a recent user
 * edit is applied (clear `X`, voice partials, recent chips); stale or interleaved echoes from the
 * asynchronous state collection never overwrite what the user is typing.
 */
class ExternalTextSync {
    private val echoes = ArrayDeque<String>()
    private var lastExternal: String? = null

    fun onUserEdit(text: String) {
        echoes.addLast(text)
        while (echoes.size > MAX_ECHOES) echoes.removeFirst()
    }

    /** The text to write into the `EditText` for [external], or null to leave [current] alone. */
    fun textToApply(external: String, current: String): String? {
        if (external == lastExternal) return null
        lastExternal = external
        if (external == current) {
            echoes.clear()
            return null
        }
        val echo = echoes.indexOf(external)
        if (echo >= 0) {
            repeat(echo + 1) { echoes.removeFirst() }
            return null
        }
        echoes.clear()
        return external
    }

    private companion object {
        const val MAX_ECHOES = 64
    }
}
