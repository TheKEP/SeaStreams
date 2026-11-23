package com.seastreams.tv.ui.screens.settings.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.screens.settings.AddonRemovalFocus
import com.seastreams.tv.ui.screens.settings.PanelColumn
import com.seastreams.tv.ui.screens.settings.RoundIconButton
import com.seastreams.tv.ui.screens.settings.RowTrailing
import com.seastreams.tv.ui.screens.settings.SettingsRow
import com.seastreams.tv.ui.screens.settings.addonRemovalFocus
import com.seastreams.tv.ui.screens.settings.addonsPanelLastIndex
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import com.seastreams.tv.ui.theme.verticalListEdgeWrap
import kotlinx.coroutines.launch

/** One provider or indexer row of a [SourceListPanel]. */
data class SourceRow(val id: Long, val title: String, val subtitle: String, val enabled: Boolean)

/**
 * The `Usenet providers` / `Indexers` sub-panel (PRD §11 `Sources`). It follows the *Manage addons*
 * rules: each row = toggle pill + (when [onTest] is set) `Test connection` + `Move up` /
 * `Move down` / `Remove`, closed by an `Add …` row that
 * opens the Web Portal (credentials are typed on a phone). Two-way §1 edge-stop-then-wrap between
 * every control of the first row and the `Add …` row; `Remove` moves focus to the next row's `Remove`
 * (or `Add …` after the last row) *before* removing, so focus is never lost.
 */
@Composable
fun SourceListPanel(
    title: String,
    icon: ImageVector,
    rows: List<SourceRow>,
    addTitle: String,
    addHint: String,
    onToggle: (SourceRow) -> Unit,
    onMove: (SourceRow, Int) -> Unit,
    onRemove: (SourceRow) -> Unit,
    onAdd: () -> Unit,
    onTest: ((SourceRow) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    val lastIndex = addonsPanelLastIndex(rows.size)
    val wrap = rememberListEdgeWrap(listState, first, last, lastIndex)
    LaunchedEffect(Unit) { first.requestFocusAfterFrame() }
    val scope = rememberCoroutineScope()
    val removeRequesters = remember { HashMap<Long, FocusRequester>() }
    val removeOf: (SourceRow) -> FocusRequester = { removeRequesters.getOrPut(it.id) { FocusRequester() } }
    val removeKeepingFocus: (Int, SourceRow) -> Unit = { index, row ->
        val (targetIndex, target) = when (val next = addonRemovalFocus(index, rows.size) { true }) {
            is AddonRemovalFocus.Row -> next.index to removeOf(rows[next.index])
            AddonRemovalFocus.InstallMore -> lastIndex to last
        }
        scope.launch {
            if (!target.tryRequestFocus()) {
                listState.scrollToItem(targetIndex)
                target.requestFocusAfterFrame()
            }
            onRemove(row)
        }
    }
    PanelColumn(title, listState) {
        itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
            val edge = Modifier.verticalListEdgeWrap(index, lastIndex, wrap.toFirst, wrap.toLast)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsRow(
                    icon = icon,
                    title = row.title,
                    value = row.subtitle,
                    trailing = RowTrailing.Toggle(row.enabled),
                    onClick = { onToggle(row) },
                    modifier = Modifier
                        .weight(1f)
                        .then(if (index == 0) Modifier.focusRequester(first).ignoreInitialKeyUp() else Modifier)
                        .then(edge),
                )
                if (onTest != null) RoundIconButton(Icons.Filled.Refresh, "Test connection", edge) { onTest(row) }
                RoundIconButton(Icons.Filled.KeyboardArrowUp, "Move up", edge) { onMove(row, -1) }
                RoundIconButton(Icons.Filled.KeyboardArrowDown, "Move down", edge) { onMove(row, 1) }
                RoundIconButton(Icons.Filled.Close, "Remove", edge.focusRequester(removeOf(row))) { removeKeepingFocus(index, row) }
            }
        }
        item(key = "add") {
            SettingsRow(
                icon = SeaIcons.QrCode,
                title = addTitle,
                value = addHint,
                trailing = RowTrailing.Chevron,
                onClick = onAdd,
                modifier = Modifier
                    .focusRequester(last)
                    // With no rows this is the only row, so it is also the first (initial focus + wraps).
                    .then(if (rows.isEmpty()) Modifier.focusRequester(first).ignoreInitialKeyUp() else Modifier)
                    .verticalListEdgeWrap(lastIndex, lastIndex, wrap.toFirst, wrap.toLast),
            )
        }
    }
}
