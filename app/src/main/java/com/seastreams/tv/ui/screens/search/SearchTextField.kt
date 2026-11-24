package com.seastreams.tv.ui.screens.search

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.OutlineDim
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.awaitNextFrame
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.tryRequestFocus
import kotlinx.coroutines.launch

/** Edit-mode state of the Search field, shared with the screen so the voice fallback can open it. */
@Stable
class SearchFieldInput {
    /** `true` while the native `EditText` holds focus for typing / dictation. */
    var editing by mutableStateOf(false)
        internal set

    /** Bumped per open request, so a repeat request while already editing re-shows the keyboard. */
    internal var openRequests by mutableIntStateOf(0)
        private set

    /** Enters edit mode: focuses the `EditText` and opens the device keyboard (FireTVIME on Fire OS). */
    fun openKeyboard() {
        editing = true
        openRequests++
    }
}

/**
 * Search's text field (PRD §7): the `TvTextField` look, hosting a native [EditText] so every TV
 * keyboard (FireTVIME on Fire OS: QWERTY + phone QR input) binds to it with a "Search" action key.
 * D-pad focus rests on the surface; OK (or [SearchFieldInput.openKeyboard]) enters edit mode and
 * opens the keyboard ([FieldImeOpener]). Edit-mode keys follow [SearchFieldKeyRouter]; an exit
 * first asks [onExitDirection] (top-bar routing; `true` = handled) and else moves spatially.
 */
@Composable
fun SearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    input: SearchFieldInput,
    onExitDirection: (FieldExitDirection) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val surfaceFocus = remember { FocusRequester() }
    val editFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val currentOnExitDirection by rememberUpdatedState(onExitDirection)
    val field = remember(input) {
        NativeSearchField(input).apply {
            onUserText = { currentOnValueChange(it) }
            onExit = { direction, submit ->
                endEditing()
                surfaceFocus.tryRequestFocus()
                view?.let { if (it.hasFocus()) it.clearFocus() }
                if (submit) currentOnSubmit()
                if (direction != null) {
                    scope.launch {
                        awaitNextFrame()
                        if (!currentOnExitDirection(direction)) focusManager.moveFocus(direction.focus())
                    }
                }
            }
        }
    }
    LaunchedEffect(input.openRequests) {
        if (!input.editing) return@LaunchedEffect
        awaitNextFrame()
        field.showKeyboard(editFocus)
    }
    // A force-shown keyboard outlives the field unless closed: leaving Search mid-edit closes it.
    DisposableEffect(field) { onDispose { if (input.editing) field.endEditing() } }

    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = input::openKeyboard,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(surfaceFocus)
            .ignoreInitialKeyUp()
            // The EditText holds focus while editing, so keep the focus ring drawn on the field.
            .then(if (input.editing) Modifier.border(2.dp, FocusPill, shape) else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, OutlineDim), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, FocusPill), shape = shape),
        ),
    ) {
        AndroidView(
            factory = { context -> field.create(context, placeholder) },
            update = { view ->
                field.applyExternal(value)
                view.hint = placeholder
                view.isFocusable = input.editing
                view.isFocusableInTouchMode = input.editing
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .focusRequester(editFocus)
                .focusProperties { canFocus = input.editing },
        )
    }
}

/** Owns the native [EditText], its listeners and the IME calls; callbacks are wired by the composable. */
private class NativeSearchField(private val input: SearchFieldInput) {
    private val sync = ExternalTextSync()
    private val router = SearchFieldKeyRouter()
    private var suppressWatcher = false

    var view: EditText? = null
        private set
    private var ime: FieldImeOpener? = null
    var onUserText: (String) -> Unit = {}

    /** Leaves edit mode; then moves focus [FieldExitDirection] (null: stays on the field) and/or submits. */
    var onExit: (direction: FieldExitDirection?, submit: Boolean) -> Unit = { _, _ -> }

    fun create(context: Context, placeholder: String): EditText = SearchEditText(context).apply {
        isSingleLine = true
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setImeActionLabel(SEARCH_ACTION_LABEL, EditorInfo.IME_ACTION_SEARCH)
        // Never "nm": that legacy private option means "no microphone" and hides the IME's voice key.
        privateImeOptions = null
        background = null
        setPadding(0, 0, 0, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
        setTextColor(TextPrimary.toArgb())
        setHintTextColor(TextSecondary.toArgb())
        hint = placeholder
        contentDescription = placeholder
        isFocusable = false
        isFocusableInTouchMode = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val caretWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics).toInt()
            textCursorDrawable = GradientDrawable().apply {
                setColor(FocusPill.toArgb())
                setSize(caretWidth, 0)
            }
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val text = s?.toString().orEmpty()
                sync.onUserEdit(text)
                onUserText(text)
            }
        })
        setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId in SUBMIT_ACTIONS || (actionId == EditorInfo.IME_NULL && isEnter)) {
                onExit(null, true)
                true
            } else {
                false
            }
        }
        setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            val edit = v as EditText
            val action = router.route(keyCode, event.action == KeyEvent.ACTION_DOWN, edit.selectionStart, edit.selectionEnd, edit.length())
            when (action) {
                FieldKeyAction.Pass -> false
                FieldKeyAction.Consume -> true
                FieldKeyAction.Submit -> true.also { onExit(null, true) }
                is FieldKeyAction.Exit -> true.also { onExit(action.direction, false) }
            }
        }
        // Focus taken elsewhere while editing (e.g. the top bar's `Down` handler focused chip #1).
        setOnFocusChangeListener { _, hasFocus -> if (!hasFocus && input.editing) endEditing() }
    }.also {
        view = it
        ime = FieldImeOpener(it, isEditing = { input.editing })
    }

    /** Writes an external query change (clear X, voice partial, recent chip) without echoing it back. */
    fun applyExternal(value: String) {
        val v = view ?: return
        val next = sync.textToApply(value, v.text.toString()) ?: return
        suppressWatcher = true
        v.setText(next)
        v.setSelection(next.length)
        suppressWatcher = false
    }

    fun showKeyboard(editFocus: FocusRequester) {
        val v = view ?: return
        router.reset()
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        // Compose focus first (keeps the interop node focused), then the forced IME sequence.
        editFocus.tryRequestFocus()
        v.setSelection(v.length())
        ime?.open()
    }

    fun endEditing() {
        router.reset()
        ime?.hide()
        input.editing = false
    }

    private companion object {
        const val TEXT_SIZE_SP = 18f
        const val SEARCH_ACTION_LABEL = "Search"

        // NEXT too: an IME that still offers it must search, never move focus out of the field.
        val SUBMIT_ACTIONS = setOf(
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_NEXT,
        )
    }
}

/**
 * `TextView.onCreateInputConnection` adds `IME_FLAG_NAVIGATE_NEXT` whenever a forward focusable exists
 * (always, inside Compose), and FireTVIME then labels its action key "Next". The editor info handed
 * to the keyboard is normalised to a plain Search action ([searchEditorImeOptions]).
 */
private class SearchEditText(context: Context) : EditText(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = searchEditorImeOptions(outAttrs.imeOptions)
        return connection
    }
}

private fun FieldExitDirection.focus(): FocusDirection = when (this) {
    FieldExitDirection.LEFT -> FocusDirection.Left
    FieldExitDirection.RIGHT -> FocusDirection.Right
    FieldExitDirection.UP -> FocusDirection.Up
    FieldExitDirection.DOWN -> FocusDirection.Down
}
