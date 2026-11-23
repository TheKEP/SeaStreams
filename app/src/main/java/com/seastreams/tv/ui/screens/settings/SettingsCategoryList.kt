package com.seastreams.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.seastreams.tv.ui.theme.OutlineDim
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.onDpadEdgeWrap

/**
 * One Settings category (`settings-top-nav.jpg`): an uppercase header with its icon, then the rows
 * in a dark rounded `#161A23` card with thin dividers. Wrap is one-way bottom-to-top (§1/§11): a fresh
 * `Down` on the last row wraps to the first, a fresh `Up` on the first row runs [onUpFromFirst] (focus
 * the active category pill), and held keys stop on the edge row ([settingsRowEdges]). Edges are
 * recomputed from [items] on every change, so they follow conditional sub-settings. [SettingsItem.Section]
 * subheadings (`USENET`) split the list into one card per section; the wrap still spans the whole tab.
 */
@Composable
fun SettingsCategoryList(
    category: SettingsCategory,
    items: List<SettingsItem>,
    listState: LazyListState,
    requesterFor: (String) -> FocusRequester,
    onFocusInListChanged: (Boolean) -> Unit,
    onUpFromFirst: () -> Unit,
    swatches: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordinals = remember(items) { settingsEntryOrdinals(items) }
    val lastOrdinal = ordinals.maxOrNull() ?: -1
    val firstKey = firstEntryKey(items)
    val lastKey = lastEntryKey(items)
    val wrap = rememberListEdgeWrap(listState, requesterFor(firstKey), requesterFor(lastKey), items.lastIndex)
    Column(modifier) {
        SettingsCategoryHeader(category)
        // With subheadings each section is its own card; otherwise the whole list sits in one card.
        val sectioned = items.any { it is SettingsItem.Section }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .then(if (sectioned) Modifier else Modifier.background(SurfaceCard, CardShape)),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusInListChanged(it.hasFocus) },
                contentPadding = if (sectioned) PaddingValues(bottom = 10.dp) else PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (sectioned) 0.dp else 2.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                    if (item is SettingsItem.Section) {
                        SettingsSectionHeader(item.title)
                        return@itemsIndexed
                    }
                    val segment = sectionSegment(items, index)
                    Column(if (sectioned) Modifier.sectionCard(segment) else Modifier) {
                        if (!segment.first) RowDivider()
                        when (item) {
                            is SettingsItem.Section -> Unit
                            SettingsItem.Swatches -> swatches()
                            is SettingsItem.Entry -> SettingsRow(
                                icon = item.icon,
                                title = item.title,
                                value = item.value,
                                trailing = item.trailing,
                                onClick = item.onClick,
                                indented = item.indented,
                                colors = settingsCardRowColors(),
                                modifier = Modifier
                                    .focusRequester(requesterFor(item.key))
                                    .rowEdges(settingsRowEdges(ordinals[index], lastOrdinal), wrap.toFirst, onUpFromFirst),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Whether row [index] opens and/or closes its section's card (a section starts after a subheading or at the top). */
internal data class SectionSegment(val first: Boolean, val last: Boolean)

internal fun sectionSegment(items: List<SettingsItem>, index: Int) = SectionSegment(
    first = index == 0 || items[index - 1] is SettingsItem.Section,
    last = index == items.lastIndex || items[index + 1] is SettingsItem.Section,
)

/** One slice of a section card: rounded only at the section's ends, padded like the single-card list. */
private fun Modifier.sectionCard(segment: SectionSegment): Modifier {
    val top = if (segment.first) CARD_RADIUS else 0.dp
    val bottom = if (segment.last) CARD_RADIUS else 0.dp
    return background(SurfaceCard, RoundedCornerShape(top, top, bottom, bottom))
        .padding(start = 10.dp, end = 10.dp, top = if (segment.first) 10.dp else 1.dp, bottom = if (segment.last) 10.dp else 1.dp)
}

/** Key of the category's first focusable row (the target of `Down` from the tab bar and of the bottom wrap). */
internal fun firstEntryKey(items: List<SettingsItem>): String =
    items.firstOrNull { it is SettingsItem.Entry }?.key ?: PROFILES_ROW_KEY

/** Key of the category's last focusable row (the target of the top wrap). */
internal fun lastEntryKey(items: List<SettingsItem>): String =
    items.lastOrNull { it is SettingsItem.Entry }?.key ?: PROFILES_ROW_KEY

private fun Modifier.rowEdges(edges: Map<Key, SettingsRowEdge>, toFirst: () -> Unit, toTabBar: () -> Unit): Modifier =
    edges.entries.fold(this) { modifier, (key, edge) ->
        modifier.onDpadEdgeWrap(key) {
            when (edge) {
                SettingsRowEdge.TO_TAB_BAR -> toTabBar()
                SettingsRowEdge.WRAP_TO_FIRST -> toFirst()
            }
        }
    }

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 1.dp)
            .height(1.dp)
            .background(OutlineDim),
    )
}

private val CARD_RADIUS = 22.dp
private val CardShape = RoundedCornerShape(CARD_RADIUS)
