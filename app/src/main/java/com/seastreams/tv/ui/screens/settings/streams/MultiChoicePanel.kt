package com.seastreams.tv.ui.screens.settings.streams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.seastreams.tv.ui.screens.settings.PanelColumn
import com.seastreams.tv.ui.screens.settings.RoundIconButton
import com.seastreams.tv.ui.screens.settings.RowTrailing
import com.seastreams.tv.ui.screens.settings.SettingsRow
import com.seastreams.tv.ui.theme.Follow
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.rememberReorderFocus
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import com.seastreams.tv.ui.theme.verticalListEdgeWrap
import kotlinx.coroutines.launch

/**
 * A multi-select list (stream filter values, PRD §13): one toggle row per option; `OK` adds or removes it. With
 * [ordered] (Preferred lists, `aio-filters.png` *Preference order*) the chosen values come first, in their order, each
 * with `Move up` / `Move down`; a newly chosen value joins the end. Focus follows a row when it moves.
 *
 * D-pad: two-way edge-stop-then-wrap between the first and last row (every control of those rows owns the edge);
 * `Left`/`Right` move between a row's controls; `Back` is the Settings screen's panel BackHandler. The first row
 * ignores the `KeyUp` of the `OK` press that opened the panel.
 */
@Composable
fun MultiChoicePanel(
    title: String,
    options: List<String>,
    selected: List<String>,
    ordered: Boolean,
    onChange: (List<String>) -> Unit,
) {
    val rows = multiChoiceRows(options, selected, ordered)
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    val lastIndex = rows.lastIndex
    val wrap = rememberListEdgeWrap(listState, first, last, lastIndex)
    val scope = rememberCoroutineScope()
    val requesters = remember { HashMap<String, FocusRequester>() }
    val requesterOf: (String) -> FocusRequester = { requesters.getOrPut(it) { FocusRequester() } }
    LaunchedEffect(Unit) { first.requestFocusAfterFrame() }
    // Move up / Move down keep the moved value in view and its button focused once the new order is saved (the
    // chosen values lead the rows, so a value's position is also its lazy index).
    val reorder = rememberReorderFocus<String>(listState)
    val chosen = rows.filter { it.selected }.map { it.value }
    if (ordered) reorder.Follow(chosen)
    val move: (String, Int) -> Unit = { value, offset ->
        reorder.onMove(value, offset, chosen)
        onChange(moved(selected, value, offset))
    }

    val change: (String, List<String>) -> Unit = { focusValue, updated ->
        onChange(updated)
        val index = multiChoiceRows(options, updated, ordered).indexOfFirst { it.value == focusValue }
        if (ordered && index >= 0) {
            scope.launch {
                listState.scrollToItem(index)
                if (!requesterOf(focusValue).tryRequestFocus()) requesterOf(focusValue).requestFocusAfterFrame()
            }
        }
    }
    PanelColumn(title, listState) {
        itemsIndexed(rows, key = { _, row -> row.value }) { index, row ->
            val edge = Modifier.verticalListEdgeWrap(index, lastIndex, wrap.toFirst, wrap.toLast)
            val position = selected.indexOf(row.value)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsRow(
                    icon = null,
                    title = row.value,
                    value = if (ordered && row.selected) "#${position + 1}" else null,
                    trailing = RowTrailing.Toggle(row.selected),
                    onClick = { change(row.value, toggled(selected, row.value)) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(requesterOf(row.value))
                        .then(if (index == 0) Modifier.focusRequester(first).ignoreInitialKeyUp() else Modifier)
                        .then(if (index == lastIndex) Modifier.focusRequester(last) else Modifier)
                        .then(edge),
                )
                if (ordered && row.selected) {
                    RoundIconButton(Icons.Filled.KeyboardArrowUp, "Move up", edge.focusRequester(reorder.up(row.value))) {
                        move(row.value, -1)
                    }
                    RoundIconButton(Icons.Filled.KeyboardArrowDown, "Move down", edge.focusRequester(reorder.down(row.value))) {
                        move(row.value, 1)
                    }
                }
            }
        }
    }
}

/** One row of a [MultiChoicePanel]. */
internal data class MultiChoiceRow(val value: String, val selected: Boolean)

/** Ordered: chosen values first (in order), then the rest; otherwise the options in their own order. */
internal fun multiChoiceRows(options: List<String>, selected: List<String>, ordered: Boolean): List<MultiChoiceRow> {
    val chosen = selected.filter { it in options }
    return if (ordered) {
        chosen.map { MultiChoiceRow(it, true) } + options.filter { it !in chosen }.map { MultiChoiceRow(it, false) }
    } else {
        options.map { MultiChoiceRow(it, it in chosen) }
    }
}

/** [value] removed when chosen, else added at the end. */
internal fun toggled(selected: List<String>, value: String): List<String> =
    if (value in selected) selected - value else selected + value

/** [value] moved one place by [offset] (clamped at the ends). */
internal fun moved(selected: List<String>, value: String, offset: Int): List<String> {
    val from = selected.indexOf(value)
    if (from < 0) return selected
    val to = (from + offset).coerceIn(0, selected.lastIndex)
    if (to == from) return selected
    return selected.toMutableList().apply { add(to, removeAt(from)) }
}
