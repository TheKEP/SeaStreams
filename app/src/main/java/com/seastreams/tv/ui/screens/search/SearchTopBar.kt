package com.seastreams.tv.ui.screens.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.components.MicIcon
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.components.TvTextField
import com.seastreams.tv.ui.theme.ErrorRed
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.OnFocusPill
import com.seastreams.tv.ui.theme.OutlineDim
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.onDpadKeyDown

/**
 * `search-screen.jpg` top area: microphone button (initial focus), wide search field, a clear (`X`)
 * button while there is a query, and the recent-search chips with their own clear-all `X`.
 *
 * `Down` from the bottom-most control row (the chips when shown, else the mic / field row) first
 * asks [onDownToContent]; returning `true` consumes the press so the caller can route focus itself.
 */
@Composable
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClearQuery: () -> Unit,
    voice: VoiceSearchState,
    micFocusRequester: FocusRequester,
    recentSearches: List<String>,
    onRecentClick: (String) -> Unit,
    onClearRecents: () -> Unit,
    onDownToContent: () -> Boolean,
) {
    val hasRecents = recentSearches.isNotEmpty()
    val downToContent = Modifier.onDpadKeyDown(Key.DirectionDown, onDownToContent)
    Column(Modifier.padding(horizontal = RowGutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = if (hasRecents) Modifier else downToContent,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RoundIconButton(
                icon = MicIcon,
                contentDescription = if (voice.listening) "Stop voice search" else "Search by voice",
                onClick = voice::toggle,
                size = 56.dp,
                active = voice.listening,
                modifier = Modifier.focusRequester(micFocusRequester),
            )
            TvTextField(
                value = query,
                onValueChange = onQueryChange,
                label = null,
                placeholder = "Search movies & TV",
                onDone = onSubmit,
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                RoundIconButton(icon = Icons.Filled.Close, contentDescription = "Clear search", onClick = onClearQuery, size = 48.dp)
            }
        }
        voice.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
        if (hasRecents) {
            RecentSearchChips(recentSearches, onRecentClick, onClearRecents, downToContent)
        }
    }
}

@Composable
private fun RecentSearchChips(recent: List<String>, onClick: (String) -> Unit, onClearAll: () -> Unit, modifier: Modifier) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(recent, key = { it }) { text ->
            val shape = RoundedCornerShape(50)
            Surface(
                onClick = { onClick(text) },
                shape = ClickableSurfaceDefaults.shape(shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, TextSecondary), shape = shape)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = TextPrimary,
                    focusedContainerColor = FocusPill,
                    focusedContentColor = OnFocusPill,
                ),
            ) {
                Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        item(key = CLEAR_ALL_KEY) {
            RoundIconButton(icon = Icons.Filled.Close, contentDescription = "Clear recent searches", onClick = onClearAll, size = 32.dp)
        }
    }
}

/** Circular icon button; [active] tints it (e.g. the mic while listening). */
@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, OutlineDim), shape = CircleShape)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) ErrorRed else Color.White.copy(alpha = 0.10f),
            contentColor = TextPrimary,
            focusedContainerColor = if (active) ErrorRed else FocusPill,
            focusedContentColor = if (active) TextPrimary else OnFocusPill,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.align(Alignment.Center).size(size * ICON_FRACTION))
    }
}

private const val CLEAR_ALL_KEY = "clear-recent"
private const val ICON_FRACTION = 0.5f
