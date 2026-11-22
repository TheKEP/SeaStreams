package com.seastreams.tv.ui.screens.settings.sources

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
import com.seastreams.tv.ui.screens.settings.PanelColumn
import com.seastreams.tv.ui.screens.settings.SettingsRow
import com.seastreams.tv.ui.screens.settings.SettingsSectionHeader
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.onDpadKeyDown
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/**
 * `Usenet performance` (PRD §12): section labels over stepper / toggle rows. Opens on `Performance
 * profile`. One-way bottom-to-top wrap like the category lists: a fresh `Down` on the last row
 * scrolls to the top and focuses the first row (held `Down` stops); `Up` on the first row is consumed
 * (the tab bar can't take focus while a sub-panel is open). `Back` is the screen's panel BackHandler.
 */
@Composable
fun UsenetPerformancePanel(items: List<PerformanceItem>) {
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val rows = items.filterIsInstance<PerformanceItem.Row>()
    val firstKey = rows.firstOrNull()?.key
    val lastKey = rows.lastOrNull()?.key
    LaunchedEffect(Unit) { first.requestFocusAfterFrame() }
    PanelColumn("Usenet performance", listState) {
        itemsIndexed(items, key = { _, item -> item.key }) { _, item ->
            when (item) {
                is PerformanceItem.Section -> SettingsSectionHeader(item.title)
                is PerformanceItem.Row -> SettingsRow(
                    icon = null,
                    title = item.title,
                    value = item.value,
                    trailing = item.trailing,
                    onClick = item.onClick,
                    modifier = Modifier
                        .then(
                            if (item.key == firstKey) {
                                Modifier.focusRequester(first).ignoreInitialKeyUp().onDpadKeyDown(Key.DirectionUp) { true }
                            } else {
                                Modifier
                            },
                        )
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
