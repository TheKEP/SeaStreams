package com.seastreams.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.OutlineDim
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TextSecondary

/**
 * D-pad friendly text input. Focus lands on a surface first (so D-pad navigation can pass over
 * the field without popping the keyboard); pressing OK enters edit mode and opens the on-screen
 * keyboard. Done/IME action or moving focus away leaves edit mode.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    val surfaceFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = TextSecondary)
        Surface(
            onClick = { editing = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (label != null) 6.dp else 0.dp)
                .focusRequester(surfaceFocus),
            shape = TextFieldSurface.shape(),
            scale = TextFieldSurface.scale(),
            colors = TextFieldSurface.colors(),
            border = TextFieldSurface.border(),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 18.sp),
                cursorBrush = SolidColor(FocusPill),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    surfaceFocus.requestFocus()
                    editing = false
                    onDone()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .focusRequester(fieldFocus)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged { if (!it.isFocused && editing) editing = false },
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, color = TextSecondary, fontSize = 18.sp)
                    inner()
                },
            )
        }
    }

    LaunchedEffect(editing) {
        if (editing) {
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }
}

/**
 * The look [TvTextField] and the Search field share: a 12dp rounded surface, background → surfaceVariant fill on focus,
 * a 1.02 focus scale and a 1dp [OutlineDim] outline that becomes a 2dp [FocusPill] ring.
 */
internal object TextFieldSurface {
    val Shape = RoundedCornerShape(12.dp)

    @Composable
    fun shape() = ClickableSurfaceDefaults.shape(Shape)

    @Composable
    fun scale() = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)

    @Composable
    fun colors() = ClickableSurfaceDefaults.colors(
        containerColor = MaterialTheme.colorScheme.background,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    )

    @Composable
    fun border() = ClickableSurfaceDefaults.border(
        border = Border(BorderStroke(1.dp, OutlineDim), shape = Shape),
        focusedBorder = Border(BorderStroke(2.dp, FocusPill), shape = Shape),
    )
}
