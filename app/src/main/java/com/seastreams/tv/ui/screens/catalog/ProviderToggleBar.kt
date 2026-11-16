package com.seastreams.tv.ui.screens.catalog

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.StreamingProvider
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.OnFocusPill
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/**
 * `Streaming services` chips (PRD §5): OK toggles a service's row on or off. The choice is global
 * and persisted, so Movies and TV Shows share it. The last chip follows edge-stop-then-wrap.
 *
 * `Down` into the bar always lands on the first chip with the bar scrolled to its start, never on
 * the chip that happens to sit under the card above. [focusable] = false keeps every chip out of
 * focus (the page uses it so the bar can't take the initial focus while the rows load).
 */
@Composable
fun ProviderToggleBar(
    enabled: List<StreamingProvider>,
    onToggle: (StreamingProvider, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusable: Boolean = true,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstChip = remember { FocusRequester() }
    val providers = StreamingProvider.entries
    val wrapToFirst: () -> Unit = {
        scope.launch {
            listState.scrollToItem(0)
            firstChip.requestFocusAfterFrame()
        }
    }
    Column(modifier) {
        Text(
            "Streaming services",
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary,
            modifier = Modifier.padding(start = RowGutter, bottom = 8.dp),
        )
        LazyRow(
            state = listState,
            modifier = Modifier
                .focusProperties {
                    onEnter = {
                        val firstVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == 0 }
                        when (providerBarEntry(focusable, requestedFocusDirection == FocusDirection.Down, firstVisible)) {
                            ProviderBarEntry.BLOCK -> cancelFocusChange()
                            ProviderBarEntry.FOCUS_FIRST -> firstChip.requestFocus()
                            ProviderBarEntry.SCROLL_THEN_FOCUS_FIRST -> {
                                cancelFocusChange()
                                wrapToFirst()
                            }
                            ProviderBarEntry.DEFAULT -> Unit
                        }
                    }
                }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = RowGutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(providers, key = { _, p -> p.name }) { index, provider ->
                val isOn = provider in enabled
                ProviderChip(
                    provider = provider,
                    isOn = isOn,
                    onClick = { onToggle(provider, !isOn) },
                    modifier = Modifier
                        .focusProperties { canFocus = focusable }
                        .then(if (index == 0) Modifier.focusRequester(firstChip) else Modifier)
                        .then(if (index == providers.lastIndex) Modifier.onDpadEdgeWrap(Key.DirectionRight, wrapToFirst) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun ProviderChip(provider: StreamingProvider, isOn: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isOn) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
            contentColor = if (isOn) TextPrimary else TextSecondary,
            focusedContainerColor = FocusPill,
            focusedContentColor = OnFocusPill,
        ),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (isOn) Icons.Filled.Check else Icons.Filled.Add,
                contentDescription = if (isOn) "Row shown" else "Row hidden",
                modifier = Modifier.size(16.dp),
            )
            Text(provider.displayName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** How focus entering the `Streaming services` bar is handled. */
internal enum class ProviderBarEntry { BLOCK, FOCUS_FIRST, SCROLL_THEN_FOCUS_FIRST, DEFAULT }

/**
 * Entry rule for the bar: `Down` from the row above lands on chip 0 (scrolling the bar back to its
 * start first when chip 0 is off screen); other entries (e.g. programmatic) keep their target.
 */
internal fun providerBarEntry(focusable: Boolean, fromAbove: Boolean, firstChipVisible: Boolean): ProviderBarEntry = when {
    !focusable -> ProviderBarEntry.BLOCK
    !fromAbove -> ProviderBarEntry.DEFAULT
    firstChipVisible -> ProviderBarEntry.FOCUS_FIRST
    else -> ProviderBarEntry.SCROLL_THEN_FOCUS_FIRST
}
