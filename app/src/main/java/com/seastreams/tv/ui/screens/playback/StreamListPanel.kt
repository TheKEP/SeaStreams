package com.seastreams.tv.ui.screens.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import com.seastreams.tv.ui.theme.verticalListEdgeWrap

/** What the stream list shows: a loading entry, an empty entry, or the stream cards. */
internal enum class StreamListContent { LOADING, EMPTY, RESULTS }

internal fun streamListContent(loading: Boolean, streams: List<StreamSource>): StreamListContent = when {
    loading -> StreamListContent.LOADING
    streams.isEmpty() -> StreamListContent.EMPTY
    else -> StreamListContent.RESULTS
}

/** The card that takes initial focus: the `LAST USED` stream when it is listed, else the first. */
internal fun initialStreamIndex(lastUsedIndex: Int?, count: Int): Int =
    lastUsedIndex?.takeIf { it in 0 until count } ?: 0

/**
 * The stream selection container shared by the Details stream modal and the in-player `Streams`
 * overlay: a 520dp right-hand panel over a dimmed screen with a "Choose a stream" header and a list
 * of [StreamResultCard]s. Focus stays inside the panel and wraps both ways (§1): a fresh `Down` on the
 * last card goes to the first, a fresh `Up` on the first goes to the last; a held key stops at the
 * edge. `Back` is left to the caller. Every entry ignores a held `OK` that started before it was
 * focused, including the auto-focused one. [showRank] prefixes `#1`, `#2`…; [selectedIndex] marks
 * the playing stream with a check; [lastUsedIndex] badges the `LAST USED` stream and opens the list
 * scrolled to and focused on it (the first card when it isn't listed).
 */
@Composable
fun StreamListPanel(
    title: String,
    subtitle: String?,
    loading: Boolean,
    streams: List<StreamSource>,
    emptyText: String,
    onEmptyClick: () -> Unit,
    onChoose: (index: Int, stream: StreamSource) -> Unit,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    lastUsedIndex: Int? = null,
    showRank: Boolean = false,
    failedAddons: List<String> = emptyList(),
) {
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    val initial = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val content = streamListContent(loading, streams)
    val initialIndex = if (content == StreamListContent.RESULTS) initialStreamIndex(lastUsedIndex, streams.size) else 0
    val currentInitialIndex by rememberUpdatedState(initialIndex)
    // The list's content changes when streams arrive: scroll to and focus its initial entry each time.
    LaunchedEffect(loading, streams) {
        listState.scrollToItem(currentInitialIndex)
        retryEachFrame(RESTORE_FRAMES) { initial.tryRequestFocus() }
    }
    val wrap = rememberListEdgeWrap(listState, first, last, streams.lastIndex)

    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(PanelWidth)
                .background(SurfaceCard)
                .padding(horizontal = 20.dp, vertical = 32.dp)
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection != FocusDirection.Enter && requestedFocusDirection != FocusDirection.Exit) {
                            cancelFocusChange()
                        }
                    }
                }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Choose a stream", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(title, style = MaterialTheme.typography.titleSmall, color = TextSecondary, maxLines = 1)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (content) {
                    StreamListContent.LOADING -> item(key = "loading") {
                        StatusEntry("Finding streams…", onClick = {}, Modifier.focusRequester(initial))
                    }
                    StreamListContent.EMPTY -> item(key = "empty") {
                        StatusEntry(emptyText, onClick = onEmptyClick, Modifier.focusRequester(initial))
                    }
                    StreamListContent.RESULTS -> itemsIndexed(streams, key = { index, stream -> "$index-${stream.url}" }) { index, stream ->
                        StreamResultCard(
                            stream = stream,
                            onClick = { onChoose(index, stream) },
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .then(if (index == streams.lastIndex) Modifier.focusRequester(last) else Modifier)
                                .then(if (index == initialIndex) Modifier.focusRequester(initial) else Modifier)
                                .verticalListEdgeWrap(index, streams.lastIndex, wrap.toFirst, wrap.toLast),
                            rank = if (showRank) index + 1 else null,
                            selected = index == selectedIndex,
                            lastUsed = index == lastUsedIndex,
                        )
                    }
                }
            }
            if (failedAddons.isNotEmpty()) {
                Text("Didn't respond: ${failedAddons.joinToString()}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun StatusEntry(text: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        // Long-pressing Play opens this list while OK is still held: its release must not pick a stream.
        modifier = modifier.ignoreInitialKeyUp().fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = focusPillColors(containerColor = Color.White.copy(alpha = 0.08f)),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
    }
}

private val PanelWidth = 520.dp
