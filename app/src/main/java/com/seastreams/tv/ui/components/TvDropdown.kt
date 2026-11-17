package com.seastreams.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.OutlineDim
import com.seastreams.tv.ui.theme.SurfaceRaised
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

/**
 * D-pad dropdown: a `Label: Value ▾` pill that opens a focusable popup list under it. Focus starts on
 * the current value; OK picks an option, `Back` closes without changing anything. Either way focus
 * returns to the pill.
 */
@Composable
fun <T> TvDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    var refocusAnchor by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        expanded = false
        refocusAnchor = true
    }
    LaunchedEffect(refocusAnchor) {
        if (refocusAnchor) {
            anchor.requestFocusAfterFrame()
            refocusAnchor = false
        }
    }

    val popupOffset = with(LocalDensity.current) { IntOffset(0, (ANCHOR_HEIGHT + 8.dp).roundToPx()) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.height(ANCHOR_HEIGHT).focusRequester(anchor),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
            border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, OutlineDim), shape = RoundedCornerShape(50))),
            colors = focusPillColors(containerColor = Color.White.copy(alpha = 0.08f)),
        ) {
            Row(Modifier.fillMaxHeight().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$label: ", style = MaterialTheme.typography.labelLarge)
                Text(optionLabel(selected), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }

        if (expanded) {
            Popup(
                offset = popupOffset,
                onDismissRequest = close,
                properties = PopupProperties(focusable = true),
            ) {
                DropdownOptions(options, selected, optionLabel) { option ->
                    onSelect(option)
                    close()
                }
            }
        }
    }
}

@Composable
private fun <T> DropdownOptions(options: List<T>, selected: T, optionLabel: (T) -> String, onPick: (T) -> Unit) {
    val selectedOption = remember { FocusRequester() }
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LaunchedEffect(Unit) { selectedOption.requestFocusAfterFrame() }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .width(MENU_WIDTH)
            .heightIn(max = MENU_MAX_HEIGHT)
            .background(SurfaceRaised, RoundedCornerShape(14.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(options) { index, option ->
            val isSelected = index == selectedIndex
            Surface(
                onClick = { onPick(option) },
                modifier = if (isSelected) Modifier.focusRequester(selectedOption) else Modifier,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = focusPillColors(contentColor = if (isSelected) TextPrimary else TextSecondary),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(optionLabel(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (isSelected) Icon(Icons.Filled.Check, contentDescription = "Selected", modifier = Modifier.size(18.dp))
                    else Spacer(Modifier.size(18.dp))
                }
            }
        }
    }
}

private val ANCHOR_HEIGHT = 40.dp
private val MENU_WIDTH = 240.dp
private val MENU_MAX_HEIGHT = 320.dp
