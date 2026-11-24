package com.seastreams.tv.ui.screens.search

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchFieldKeysTest {

    private fun SearchFieldKeyRouter.key(code: Int, down: Boolean, caret: Int = 3, length: Int = 6, selEnd: Int = caret) =
        route(code, isDown = down, selectionStart = caret, selectionEnd = selEnd, textLength = length)

    @Test
    fun `enter and center submit on release after their press`() {
        for (code in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SEARCH)) {
            val router = SearchFieldKeyRouter()
            assertEquals(FieldKeyAction.Consume, router.key(code, down = true))
            assertEquals(FieldKeyAction.Submit, router.key(code, down = false))
        }
    }

    @Test
    fun `an orphan release never submits`() {
        val router = SearchFieldKeyRouter()
        assertEquals(FieldKeyAction.Consume, router.key(KeyEvent.KEYCODE_DPAD_CENTER, down = false))
        router.key(KeyEvent.KEYCODE_DPAD_CENTER, down = true)
        router.reset()
        assertEquals(FieldKeyAction.Consume, router.key(KeyEvent.KEYCODE_DPAD_CENTER, down = false))
    }

    @Test
    fun `left exits only at the very start without a selection`() {
        val router = SearchFieldKeyRouter()
        assertEquals(FieldKeyAction.Exit(FieldExitDirection.LEFT), router.key(KeyEvent.KEYCODE_DPAD_LEFT, down = true, caret = 0))
        assertEquals(FieldKeyAction.Exit(FieldExitDirection.LEFT), router.key(KeyEvent.KEYCODE_DPAD_LEFT, down = true, caret = 0, length = 0))
        assertEquals(FieldKeyAction.Pass, router.key(KeyEvent.KEYCODE_DPAD_LEFT, down = true, caret = 3))
        assertEquals(FieldKeyAction.Pass, router.key(KeyEvent.KEYCODE_DPAD_LEFT, down = true, caret = 0, selEnd = 4))
    }

    @Test
    fun `right exits only at the very end`() {
        val router = SearchFieldKeyRouter()
        assertEquals(FieldKeyAction.Exit(FieldExitDirection.RIGHT), router.key(KeyEvent.KEYCODE_DPAD_RIGHT, down = true, caret = 6))
        assertEquals(FieldKeyAction.Pass, router.key(KeyEvent.KEYCODE_DPAD_RIGHT, down = true, caret = 2))
    }

    @Test
    fun `up and down leave edit mode in their direction`() {
        val router = SearchFieldKeyRouter()
        assertEquals(FieldKeyAction.Exit(FieldExitDirection.UP), router.key(KeyEvent.KEYCODE_DPAD_UP, down = true))
        assertEquals(FieldKeyAction.Exit(FieldExitDirection.DOWN), router.key(KeyEvent.KEYCODE_DPAD_DOWN, down = true))
        assertEquals(FieldKeyAction.Pass, router.key(KeyEvent.KEYCODE_DPAD_DOWN, down = false))
    }

    @Test
    fun `back closes edit mode on release and never leaks out`() {
        val router = SearchFieldKeyRouter()
        assertEquals(FieldKeyAction.Consume, router.key(KeyEvent.KEYCODE_BACK, down = true))
        assertEquals(FieldKeyAction.Exit(null), router.key(KeyEvent.KEYCODE_BACK, down = false))
    }

    @Test
    fun `letters pass to the edit text`() {
        val router = SearchFieldKeyRouter()
        assertEquals(FieldKeyAction.Pass, router.key(KeyEvent.KEYCODE_A, down = true))
        assertEquals(FieldKeyAction.Pass, router.key(KeyEvent.KEYCODE_A, down = false))
    }

    @Test
    fun `echo of the user's own edit is ignored`() {
        val sync = ExternalTextSync()
        assertNull(sync.textToApply("", ""))
        sync.onUserEdit("a")
        assertNull(sync.textToApply("a", "a"))
    }

    @Test
    fun `stale interleaved echoes never overwrite typing`() {
        val sync = ExternalTextSync()
        sync.textToApply("", "")
        sync.onUserEdit("a")
        sync.onUserEdit("ab")
        sync.onUserEdit("abc")
        assertNull(sync.textToApply("ab", "abc"))
        assertNull(sync.textToApply("abc", "abc"))
    }

    @Test
    fun `an unchanged external value never overwrites`() {
        val sync = ExternalTextSync()
        sync.textToApply("star", "star")
        // The user kept typing; a recomposition still carrying the old query must not reset the field.
        sync.onUserEdit("star w")
        assertNull(sync.textToApply("star", "star w"))
    }

    @Test
    fun `external clear and voice partials are applied`() {
        val sync = ExternalTextSync()
        sync.textToApply("", "")
        sync.onUserEdit("dune")
        sync.textToApply("dune", "dune")
        assertEquals("", sync.textToApply("", "dune"))
        assertEquals("star wars", sync.textToApply("star wars", ""))
    }

    @Test
    fun `a dictated phrase committed in one edit is the user's own echo`() {
        // FireTVIME dictation: InputConnection.commitText fires the TextWatcher once, no key events.
        val sync = ExternalTextSync()
        sync.textToApply("", "")
        sync.onUserEdit("star wars")
        assertNull(sync.textToApply("star wars", "star wars"))
    }

    @Test
    fun `keyboard show retries only while editing and the IME is inactive, up to the cap`() {
        assertTrue(shouldRetryImeShow(attempt = 1, imeActive = false, editing = true))
        assertTrue(shouldRetryImeShow(attempt = MAX_IME_SHOW_ATTEMPTS - 1, imeActive = false, editing = true))
        assertFalse(shouldRetryImeShow(attempt = MAX_IME_SHOW_ATTEMPTS, imeActive = false, editing = true))
        assertFalse(shouldRetryImeShow(attempt = 1, imeActive = true, editing = true))
        assertFalse(shouldRetryImeShow(attempt = 1, imeActive = false, editing = false))
    }

    @Test
    fun `the keyboard always gets a plain Search action, never Next`() {
        val fromTextView = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NAVIGATE_NEXT or
            EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        assertEquals(EditorInfo.IME_ACTION_SEARCH, searchEditorImeOptions(fromTextView))
        assertEquals(EditorInfo.IME_ACTION_SEARCH, searchEditorImeOptions(EditorInfo.IME_ACTION_NEXT))
        assertEquals(EditorInfo.IME_ACTION_SEARCH, searchEditorImeOptions(EditorInfo.IME_ACTION_UNSPECIFIED))
        // Unrelated flags survive.
        val noFullscreen = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_FULLSCREEN
        assertEquals(EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN, searchEditorImeOptions(noFullscreen))
    }

    @Test
    fun `an initial query restores into an empty field`() {
        assertEquals("alien", ExternalTextSync().textToApply("alien", ""))
    }
}
