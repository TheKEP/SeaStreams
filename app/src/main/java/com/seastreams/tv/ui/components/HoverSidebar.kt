package com.seastreams.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.rememberSelectLongPress
import com.seastreams.tv.ui.theme.selectLongPress
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/**
 * Hover-activated vertical list (Search categories, Library filters; `search-screen.jpg`): moving
 * D-pad focus onto an entry selects it immediately via [onSelect], no click needed.
 *
 * - The selected entry keeps a translucent pill while focus is elsewhere; the focused one is white.
 * - Entering the list from the side restores focus to the selected entry ([selectedFocusRequester]).
 * - Edge-stop-then-wrap: holding `Down` stops on the last entry; a fresh `Down` there wraps to the first.
 * - [onLongClick] (optional) runs when `OK` is held on an entry, with that entry's focus requester.
 */
@Composable
fun <T> HoverSidebar(
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    selectedFocusRequester: FocusRequester = remember { FocusRequester() },
    firstEntry: FocusRequester = remember { FocusRequester() },
    /** Optional right-aligned detail per entry, e.g. `10 episodes` on a season pill. */
    trailing: (T) -> String? = { null },
    onLongClick: ((entry: T, requester: FocusRequester) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val wrapToFirst: () -> Unit = {
        scope.launch {
            listState.scrollToItem(0)
            firstEntry.requestFocusAfterFrame()
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.focusRestorer(selectedFocusRequester),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(entries, key = { _, entry -> label(entry) }) { index, entry ->
            val isSelected = entry == selected
            val requester = remember { FocusRequester() }
            SidebarEntry(
                text = label(entry),
                trailing = trailing(entry),
                selected = isSelected,
                onFocused = { if (!isSelected) onSelect(entry) },
                onLongClick = onLongClick?.let { { it(entry, requester) } },
                modifier = Modifier
                    .focusRequester(requester)
                    .then(if (isSelected) Modifier.focusRequester(selectedFocusRequester) else Modifier)
                    .then(if (index == 0) Modifier.focusRequester(firstEntry) else Modifier)
                    .then(if (index == entries.lastIndex) Modifier.onDpadEdgeWrap(Key.DirectionDown, wrapToFirst) else Modifier),
            )
        }
    }
}

@Composable
private fun SidebarEntry(
    text: String,
    trailing: String?,
    selected: Boolean,
    onFocused: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(50)
    // Long-press state must reset when the menu it opens takes focus (not the Surface's onLongClick).
    val longPress = rememberSelectLongPress()
    Surface(
        onClick = { if (!longPress.consumeClick()) onFocused() },
        modifier = modifier
            .selectLongPress(longPress, onLongClick)
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = focusPillColors(
            containerColor = if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (selected) TextPrimary else TextSecondary,
        ),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}
