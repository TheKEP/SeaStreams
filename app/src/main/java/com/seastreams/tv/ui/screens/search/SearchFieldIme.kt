package com.seastreams.tv.ui.screens.search

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Opens and closes the device keyboard for the native search [EditText] (PRD §7).
 *
 * On Android TV / Fire OS, `showSoftInput(SHOW_IMPLICIT)` after a D-pad focus change is ignored, and
 * on the first frame the field may not yet be the IME's bound input target. So [open] takes focus
 * "from touch", then each attempt rebinds the input (`restartInput`), force-shows the keyboard
 * (`SHOW_FORCED`, plus the window insets controller), and retries every [RETRY_DELAY_MS] while the
 * IME is not yet active on the field ([shouldRetryImeShow]). [hide] uses flags `0`, which also
 * closes a forced keyboard.
 */
class FieldImeOpener(private val view: EditText, private val isEditing: () -> Boolean) {
    private var pending: Runnable? = null

    fun open() {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        if (!view.requestFocusFromTouch()) view.requestFocus()
        cancelPending()
        schedule(attempt = 1, delayMs = 0L)
    }

    fun hide() {
        cancelPending()
        imm()?.hideSoftInputFromWindow(view.windowToken, 0)
        insetsController()?.hide(WindowInsetsCompat.Type.ime())
    }

    private fun schedule(attempt: Int, delayMs: Long) {
        val runnable = Runnable { tryShow(attempt) }
        pending = runnable
        if (delayMs == 0L) view.post(runnable) else view.postDelayed(runnable, delayMs)
    }

    private fun tryShow(attempt: Int) {
        pending = null
        if (!isEditing()) return
        val imm = imm() ?: return
        imm.restartInput(view)
        @Suppress("DEPRECATION") // SHOW_FORCED is the only flag TV IMEs honour after a D-pad focus change
        imm.showSoftInput(view, InputMethodManager.SHOW_FORCED)
        insetsController()?.show(WindowInsetsCompat.Type.ime())
        if (shouldRetryImeShow(attempt, imm.isActive(view), isEditing())) schedule(attempt + 1, RETRY_DELAY_MS)
    }

    private fun cancelPending() {
        pending?.let(view::removeCallbacks)
        pending = null
    }

    private fun imm(): InputMethodManager? = view.context.getSystemService(InputMethodManager::class.java)

    private fun insetsController(): WindowInsetsControllerCompat? {
        val window = view.context.findActivity()?.window ?: return null
        return WindowCompat.getInsetsController(window, view)
    }

    private companion object {
        const val RETRY_DELAY_MS = 50L
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
