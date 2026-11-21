package com.seastreams.tv.ui.screens.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.ListEdgeWrapActions
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.verticalListEdgeWrap

/** The `Addons` column's first, last and selected cards (focus targets for wraps and `Left` from the streams). */
internal class AddonCardRequesters(val first: FocusRequester, val last: FocusRequester, val active: FocusRequester)

/**
 * The stream panel's left `Addons` column: a header styled like `Choose a stream` over a two-way
 * wrapping list of [AddonFilterCard]s, one per group in addon order ([wrap] scrolls + focuses the
 * first/last card; held `Up`/`Down` stops at the edge). Focusing a card selects it ([onSelect]);
 * [selected] is the addon whose streams the right column lists. `Left`/`Right` on a card go to
 * [onMove] with [columnSwitch]'s decision; `OK` behaves like `Right` (into its streams).
 */
@Composable
internal fun AddonColumn(
    groups: List<AddonGroup>,
    selected: Int,
    listState: LazyListState,
    requesters: AddonCardRequesters,
    wrap: ListEdgeWrapActions,
    onSelect: (Int) -> Unit,
    onMove: (ColumnMove) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(AddonColumnWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Addons", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(groups, key = { _, group -> group.addon.url }) { index, group ->
                val hasStreams = group.indices.isNotEmpty()
                AddonFilterCard(
                    name = group.addon.name,
                    count = group.count,
                    selected = index == selected,
                    onFocused = { onSelect(index) },
                    onClick = { onMove(columnSwitch(PanelColumn.ADDONS, Key.DirectionRight, hasStreams)) },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(requesters.first) else Modifier)
                        .then(if (index == groups.lastIndex) Modifier.focusRequester(requesters.last) else Modifier)
                        .then(if (index == selected) Modifier.focusRequester(requesters.active) else Modifier)
                        .verticalListEdgeWrap(index, groups.lastIndex, wrap.toFirst, wrap.toLast)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val move = columnSwitch(PanelColumn.ADDONS, event.key, hasStreams)
                            if (move != ColumnMove.PASS) onMove(move)
                            move != ColumnMove.PASS
                        },
                )
            }
        }
    }
}

/**
 * A compact full-width addon pill reading `Name (count)`. Focused: the `#E5E9F0` pill with dark text.
 * Selected (its streams are listed while focus is in the stream column): a `#E5E9F0` outline over a
 * lighter tint. Otherwise translucent. The name stays on one line and scrolls (marquee) while focused
 * or selected when it doesn't fit; the `(count)` sits after it, outside the marquee, so it is never
 * scrolled or truncated away. Both take the pill's content colour. A held `OK` that started before
 * the card was focused never clicks it.
 */
@Composable
private fun AddonFilterCard(
    name: String,
    count: Int,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .ignoreInitialKeyUp(),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = focusPillColors(containerColor = Color.White.copy(alpha = if (selected) 0.16f else 0.08f)),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, FocusPill), shape = shape) else Border.None,
        ),
    ) {
        val scrolling = focused || selected
        val weight = if (selected) FontWeight.Bold else FontWeight.Medium
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = weight,
                maxLines = 1,
                softWrap = false,
                overflow = if (scrolling) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(if (scrolling) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
            )
            Spacer(Modifier.width(6.dp))
            Text(addonResultCount(count), style = MaterialTheme.typography.labelLarge, fontWeight = weight, maxLines = 1, softWrap = false)
        }
    }
}

internal val AddonColumnWidth = 220.dp
