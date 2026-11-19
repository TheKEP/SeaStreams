package com.seastreams.tv.ui.screens.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.ui.theme.ListEdgeWrapActions
import com.seastreams.tv.ui.theme.OutlineDim
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import com.seastreams.tv.ui.theme.verticalListEdgeWrap
import kotlinx.coroutines.launch

/** What the stream panel shows: a loading entry, an empty entry, or the two columns. */
internal enum class StreamListContent { LOADING, EMPTY, RESULTS }

/** RESULTS whenever there is an addon card to show, even if every addon returned nothing. */
internal fun streamListContent(loading: Boolean, groups: List<AddonGroup>): StreamListContent = when {
    loading -> StreamListContent.LOADING
    groups.isEmpty() -> StreamListContent.EMPTY
    else -> StreamListContent.RESULTS
}

/**
 * The stream selection container shared by the Details stream modal and the in-player `Streams`
 * overlay: a 760dp right-hand panel over a dimmed screen with two columns — the [AddonColumn] (one
 * card per stream addon in [addons], in addon order) and `Choose a stream`, listing the selected
 * addon's [StreamResultCard]s or "<Addon> returned no results". Focusing an addon card switches the
 * list. While loading, or with no addon cards, only the right column shows its status entry.
 * Stream cards keep their **global** index into [streams], so [showRank] (`#1`, `#2`…),
 * [selectedIndex] (playing check), [lastUsedIndex] (`LAST USED` badge) and [onChoose] match the flat
 * list auto-play and fallback use. The panel opens on the `LAST USED` stream's addon and card, else
 * the top addon's top stream (its card when empty) — [initialFocus]. Each column wraps `Up`/`Down`
 * on its own (§1), `Left`/`Right` follow [columnSwitch]; focus never leaves the panel and `Back` is
 * left to the caller. Every entry ignores a held `OK` that started before it was focused.
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
    addons: List<StreamAddon> = emptyList(),
    selectedIndex: Int? = null,
    lastUsedIndex: Int? = null,
    showRank: Boolean = false,
    failedAddons: List<String> = emptyList(),
) {
    val groups = remember(addons, streams) { addonGroups(addons, streams) }
    val content = streamListContent(loading, groups)
    // Computed once per stream list, so a LAST USED that changes while the panel is open never yanks focus.
    val focus = remember(groups) { initialFocus(groups, lastUsedIndex) }
    var selected by remember(groups) { mutableIntStateOf(focus?.addonIndex ?: 0) }
    val addonRequesters = remember { AddonCardRequesters(FocusRequester(), FocusRequester(), FocusRequester()) }
    val streamRequesters = remember { StreamCardRequesters(FocusRequester(), FocusRequester(), FocusRequester()) }
    val addonListState = rememberLazyListState()
    val streamListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val visible = groups.getOrNull(selected)?.indices.orEmpty()
    val addonWrap = rememberListEdgeWrap(addonListState, addonRequesters.first, addonRequesters.last, groups.lastIndex)
    val streamWrap = rememberListEdgeWrap(streamListState, streamRequesters.first, streamRequesters.last, visible.lastIndex)

    // The content changes when streams arrive: select, scroll both columns to and focus the initial entry.
    LaunchedEffect(loading, groups) {
        val position = focus?.streamPosition
        if (content == StreamListContent.RESULTS && focus != null) addonListState.scrollToItem(focus.addonIndex)
        when {
            content != StreamListContent.RESULTS || focus == null -> {
                streamListState.scrollToItem(0)
                retryEachFrame(RESTORE_FRAMES) { streamRequesters.initial.tryRequestFocus() }
            }
            position == null -> retryEachFrame(RESTORE_FRAMES) { addonRequesters.active.tryRequestFocus() }
            else -> {
                streamListState.scrollToItem(position)
                retryEachFrame(RESTORE_FRAMES) { streamRequesters.initial.tryRequestFocus() }
            }
        }
    }
    val move: (ColumnMove) -> Unit = { columnMove ->
        when (columnMove) {
            ColumnMove.TO_FIRST_STREAM -> streamWrap.toFirst()
            ColumnMove.TO_ACTIVE_ADDON -> scope.launch {
                addonListState.scrollToItem(selected)
                addonRequesters.active.requestFocusAfterFrame()
            }
            ColumnMove.HOLD, ColumnMove.PASS -> Unit
        }
    }
    val select: (Int) -> Unit = { index ->
        if (index != selected) {
            selected = index
            scope.launch { streamListState.scrollToItem(0) }
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.CenterEnd) {
        Row(
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
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (content == StreamListContent.RESULTS) {
                AddonColumn(groups, selected, addonListState, addonRequesters, addonWrap, onSelect = select, onMove = move)
                Box(Modifier.fillMaxHeight().width(1.dp).background(OutlineDim))
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Choose a stream", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(title, style = MaterialTheme.typography.titleSmall, color = TextSecondary, maxLines = 1)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1) }
                }
                if (content == StreamListContent.RESULTS && visible.isEmpty()) {
                    val name = groups.getOrNull(selected)?.addon?.name.orEmpty()
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("$name returned no results", style = MaterialTheme.typography.titleSmall, color = TextSecondary, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(
                        state = streamListState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (content) {
                            StreamListContent.LOADING -> item(key = "loading") {
                                StatusEntry("Finding streams…", onClick = {}, Modifier.focusRequester(streamRequesters.initial))
                            }
                            StreamListContent.EMPTY -> item(key = "empty") {
                                StatusEntry(emptyText, onClick = onEmptyClick, Modifier.focusRequester(streamRequesters.initial))
                            }
                            StreamListContent.RESULTS -> streamCards(
                                streams = streams,
                                visible = visible,
                                initialPosition = focus?.streamPosition?.takeIf { focus.addonIndex == selected },
                                requesters = streamRequesters,
                                wrap = streamWrap,
                                onMove = move,
                                onChoose = onChoose,
                                cues = StreamCardCues(selectedIndex, lastUsedIndex, showRank),
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
}

/** The selected addon's first and last stream cards, and the one that takes initial focus. */
private class StreamCardRequesters(val first: FocusRequester, val last: FocusRequester, val initial: FocusRequester)

/** Per-card marks, all by global stream index: the playing check, the `LAST USED` badge and `#rank`. */
private class StreamCardCues(val selectedIndex: Int?, val lastUsedIndex: Int?, val showRank: Boolean)

/** One card per global index in [visible], wrapping `Up`/`Down` two ways; `Left`/`Right` go to [onMove]. */
private fun LazyListScope.streamCards(
    streams: List<StreamSource>,
    visible: List<Int>,
    initialPosition: Int?,
    requesters: StreamCardRequesters,
    wrap: ListEdgeWrapActions,
    onMove: (ColumnMove) -> Unit,
    onChoose: (index: Int, stream: StreamSource) -> Unit,
    cues: StreamCardCues,
) {
    itemsIndexed(visible, key = { _, index -> "$index-${streams[index].url}" }) { position, index ->
        val stream = streams[index]
        StreamResultCard(
            stream = stream,
            onClick = { onChoose(index, stream) },
            modifier = Modifier
                .then(if (position == 0) Modifier.focusRequester(requesters.first) else Modifier)
                .then(if (position == visible.lastIndex) Modifier.focusRequester(requesters.last) else Modifier)
                .then(if (position == initialPosition) Modifier.focusRequester(requesters.initial) else Modifier)
                .verticalListEdgeWrap(position, visible.lastIndex, wrap.toFirst, wrap.toLast)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val columnMove = columnSwitch(PanelColumn.STREAMS, event.key, addonHasStreams = true)
                    if (columnMove != ColumnMove.PASS) onMove(columnMove)
                    columnMove != ColumnMove.PASS
                },
            rank = if (cues.showRank) index + 1 else null,
            selected = index == cues.selectedIndex,
            lastUsed = index == cues.lastUsedIndex,
        )
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

private val PanelWidth = 760.dp
