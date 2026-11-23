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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.seastreams.tv.domain.streams.SortCriterion
import com.seastreams.tv.domain.streams.SortDirection
import com.seastreams.tv.domain.streams.SortKey
import com.seastreams.tv.domain.streams.SortScope
import com.seastreams.tv.domain.streams.StreamSort
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.screens.settings.PanelColumn
import com.seastreams.tv.ui.screens.settings.RoundIconButton
import com.seastreams.tv.ui.screens.settings.RowTrailing
import com.seastreams.tv.ui.screens.settings.SettingsRow
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.Follow
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.rememberReorderFocus
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import com.seastreams.tv.ui.theme.verticalListEdgeWrap
import kotlinx.coroutines.launch

/**
 * `Stream sorting` (`aio-sorting.png`). The first row picks the list being edited (`‹ Global / Movies / Series /
 * Anime ›`); below it every criterion: the ones in use first, in sort order, each with `Move up`, `Move down` and a
 * direction button, then the unused ones. `OK` on a criterion adds it (at the end) or removes it; focus follows it.
 *
 * D-pad: two-way edge-stop-then-wrap between the list picker and the last criterion; `Back` is the Settings screen's
 * panel BackHandler; the list picker ignores the `KeyUp` of the `OK` press that opened the panel.
 */
@Composable
fun StreamSortPanel(sort: StreamSort, onChange: ((StreamSort) -> StreamSort) -> Unit) {
    var scope by remember { mutableStateOf(SortScope.GLOBAL) }
    val criteria = sort.list(scope)
    val rows = sortRows(criteria)
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    val lastIndex = rows.size
    val wrap = rememberListEdgeWrap(listState, first, last, lastIndex)
    val coroutines = rememberCoroutineScope()
    val requesters = remember { HashMap<SortKey, FocusRequester>() }
    val requesterOf: (SortKey) -> FocusRequester = { requesters.getOrPut(it) { FocusRequester() } }
    LaunchedEffect(Unit) { first.requestFocusAfterFrame() }
    // Move up / Move down keep the moved criterion in view and its button focused (lazy index + 1: the list picker).
    val reorder = rememberReorderFocus<SortKey>(listState)
    // Only the criteria in use move (they lead `rows`), so their order decides the first / last position.
    reorder.Follow(criteria.map { it.key }, lazyKeyOf = { it.name }) { it + 1 }

    val change: (SortKey, List<SortCriterion>) -> Unit = { key, updated ->
        val current = scope
        onChange { it.with(current, updated) }
        val index = sortRows(updated).indexOfFirst { it.key == key } + 1
        coroutines.launch {
            listState.scrollToItem(index)
            if (!requesterOf(key).tryRequestFocus()) requesterOf(key).requestFocusAfterFrame()
        }
    }
    val stepScope: (Int) -> Unit = { delta -> scope = SortScope.entries[(scope.ordinal + delta).mod(SortScope.entries.size)] }
    PanelColumn("Stream sorting", listState) {
        item(key = "scope") {
            SettingsRow(
                icon = SeaIcons.Sort,
                title = "Sort list",
                value = scopeLabel(scope, sort),
                trailing = RowTrailing.Stepper({ stepScope(-1) }, { stepScope(1) }),
                onClick = { stepScope(1) },
                modifier = Modifier
                    .focusRequester(first)
                    .ignoreInitialKeyUp()
                    .verticalListEdgeWrap(0, lastIndex, wrap.toFirst, wrap.toLast),
            )
        }
        itemsIndexed(rows, key = { _, row -> row.key.name }) { index, row ->
            val edge = Modifier.verticalListEdgeWrap(index + 1, lastIndex, wrap.toFirst, wrap.toLast)
            val criterion = row.criterion
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsRow(
                    icon = null,
                    title = row.key.label,
                    value = criterionLabel(row.key, criterion, criteria),
                    trailing = RowTrailing.Toggle(criterion != null),
                    onClick = { change(row.key, toggledCriterion(criteria, row.key)) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(requesterOf(row.key))
                        .then(if (index == rows.lastIndex) Modifier.focusRequester(last) else Modifier)
                        .then(edge),
                )
                if (criterion != null) {
                    RoundIconButton(Icons.Filled.KeyboardArrowUp, "Move up", edge.focusRequester(reorder.up(row.key))) {
                        reorder.onMove(row.key, -1, criteria.map { it.key })
                        val current = scope
                        onChange { it.with(current, movedCriterion(criteria, row.key, -1)) }
                    }
                    RoundIconButton(Icons.Filled.KeyboardArrowDown, "Move down", edge.focusRequester(reorder.down(row.key))) {
                        reorder.onMove(row.key, 1, criteria.map { it.key })
                        val current = scope
                        onChange { it.with(current, movedCriterion(criteria, row.key, 1)) }
                    }
                    RoundIconButton(
                        if (criterion.direction == SortDirection.DESC) SeaIcons.Sort else SeaIcons.SortAscending,
                        "Direction: ${criterion.direction.label}",
                        edge,
                    ) { change(row.key, flipped(criteria, row.key)) }
                }
            }
        }
    }
}

/** One criterion row: [criterion] is `null` while the criterion is not in the list. */
internal data class SortRow(val key: SortKey, val criterion: SortCriterion?)

/** Criteria in use first (in order), then the rest in [SortKey] order. */
internal fun sortRows(criteria: List<SortCriterion>): List<SortRow> =
    criteria.map { SortRow(it.key, it) } + SortKey.entries.filter { key -> criteria.none { it.key == key } }.map { SortRow(it, null) }

internal fun toggledCriterion(criteria: List<SortCriterion>, key: SortKey): List<SortCriterion> =
    if (criteria.any { it.key == key }) criteria.filterNot { it.key == key } else criteria + SortCriterion(key)

internal fun movedCriterion(criteria: List<SortCriterion>, key: SortKey, offset: Int): List<SortCriterion> {
    val from = criteria.indexOfFirst { it.key == key }
    if (from < 0) return criteria
    val to = (from + offset).coerceIn(0, criteria.lastIndex)
    return if (to == from) criteria else criteria.toMutableList().apply { add(to, removeAt(from)) }
}

internal fun flipped(criteria: List<SortCriterion>, key: SortKey): List<SortCriterion> = criteria.map {
    if (it.key != key) it else it.copy(direction = if (it.direction == SortDirection.DESC) SortDirection.ASC else SortDirection.DESC)
}

/** `Movies — empty, Global applies`, `Series — 3 criteria`. */
internal fun scopeLabel(scope: SortScope, sort: StreamSort): String {
    val count = sort.list(scope).size
    return when {
        count > 0 -> "${scope.label} — $count criteri${if (count == 1) "on" else "a"}"
        scope == SortScope.GLOBAL -> "Global — off, addon order"
        else -> "${scope.label} — empty, Global applies"
    }
}

/** `#1 · Descending · Larger streams first`, or the criterion's description while unused. */
internal fun criterionLabel(key: SortKey, criterion: SortCriterion?, criteria: List<SortCriterion>): String =
    if (criterion == null) key.description else "#${criteria.indexOf(criterion) + 1} · ${criterion.direction.label} · ${key.description}"
