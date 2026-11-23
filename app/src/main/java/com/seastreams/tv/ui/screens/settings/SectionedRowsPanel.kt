package com.seastreams.tv.ui.screens.settings

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.onDpadKeyDown
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/** One entry of a [SectionedRowsPanel]: a non-focusable section label or a setting row. */
sealed interface PanelItem {
    val key: String

    data class Section(override val key: String, val title: String) : PanelItem

    /** [onClick] = OK: toggles a switch, steps a value forward and wraps, or opens a sub-panel. */
    data class Row(
        override val key: String,
        val title: String,
        val value: String,
        val trailing: RowTrailing,
        val onClick: () -> Unit,
    ) : PanelItem
}

/**
 * A settings sub-panel of section labels over rows (`Usenet performance`, `Stream filters`). Opens on [initialKey]'s
 * row (the row a nested panel was opened from, when returning to it), else the first row; that row ignores the `KeyUp`
 * of the `OK` press that opened the panel. One-way bottom-to-top wrap like the category lists: a fresh `Down` on the
 * last row scrolls to the top and focuses the first row (held `Down` stops); `Up` on the first row is consumed (the
 * tab bar can't take focus while a sub-panel is open). `Back` is the Settings screen's panel BackHandler.
 */
@Composable
fun SectionedRowsPanel(title: String, items: List<PanelItem>, initialKey: String? = null) {
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val initial = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val rows = items.filterIsInstance<PanelItem.Row>()
    val firstKey = rows.firstOrNull()?.key
    val lastKey = rows.lastOrNull()?.key
    val startKey = initialKey?.takeIf { key -> rows.any { it.key == key } } ?: firstKey
    LaunchedEffect(Unit) {
        val index = items.indexOfFirst { it.key == startKey }
        if (index > 0) listState.scrollToItem(index)
        initial.requestFocusAfterFrame()
    }
    PanelColumn(title, listState) {
        itemsIndexed(items, key = { _, item -> item.key }) { _, item ->
            when (item) {
                is PanelItem.Section -> SettingsSectionHeader(item.title)
                is PanelItem.Row -> SettingsRow(
                    icon = null,
                    title = item.title,
                    value = item.value,
                    trailing = item.trailing,
                    onClick = item.onClick,
                    modifier = Modifier
                        .then(if (item.key == startKey) Modifier.focusRequester(initial).ignoreInitialKeyUp() else Modifier)
                        .then(if (item.key == firstKey) Modifier.focusRequester(first).onDpadKeyDown(Key.DirectionUp) { true } else Modifier)
                        .then(
                            if (item.key == lastKey) {
                                Modifier.onDpadEdgeWrap(Key.DirectionDown) {
                                    scope.launch {
                                        listState.scrollToItem(0)
                                        first.requestFocusAfterFrame()
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }
}
