package com.seastreams.tv.ui.screens.playback

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/**
 * Stream selection modal: a right-hand panel over a dimmed screen listing every stream the enabled
 * addons returned, in addon order. Focus stays inside the panel; `Back` closes it. Every entry
 * ignores a held `OK` that started before it was focused ([ignoreInitialKeyUp]).
 */
@Composable
fun StreamPickerModal(state: StreamPickerState, onChoose: (StreamSource) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The list's content changes when streams arrive: focus its new first entry each time.
    LaunchedEffect(state.loading, state.streams) { first.requestFocusAfterFrame() }
    val wrapToFirst: () -> Unit = {
        scope.launch {
            listState.scrollToItem(0)
            first.requestFocusAfterFrame()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(PanelWidth)
                .background(SurfaceCard)
                .padding(horizontal = 28.dp, vertical = 32.dp)
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
                Text(state.title, style = MaterialTheme.typography.titleSmall, color = TextSecondary, maxLines = 1)
                state.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    state.loading -> item(key = "loading") {
                        StatusEntry("Finding streams…", onClick = {}, Modifier.focusRequester(first))
                    }
                    state.streams.isEmpty() -> item(key = "empty") {
                        StatusEntry("No streams found. Press OK to close.", onClick = onDismiss, Modifier.focusRequester(first))
                    }
                    else -> itemsIndexed(state.streams, key = { index, stream -> "$index-${stream.url}" }) { index, stream ->
                        StreamEntry(
                            stream = stream,
                            onClick = { onChoose(stream) },
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .then(
                                    if (index == state.streams.lastIndex) {
                                        Modifier.onDpadEdgeWrap(Key.DirectionDown, wrapToFirst)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
            if (state.failedAddons.isNotEmpty()) {
                Text(
                    "Didn't respond: ${state.failedAddons.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun StreamEntry(stream: StreamSource, onClick: () -> Unit, modifier: Modifier) {
    EntrySurface(onClick, modifier) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stream.addonName, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f))
            Text(stream.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (stream.description.isNotBlank()) {
                Text(stream.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StatusEntry(text: String, onClick: () -> Unit, modifier: Modifier) {
    EntrySurface(onClick, modifier) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
    }
}

@Composable
private fun EntrySurface(onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        // Long-pressing Play opens this list while OK is still held: its release must not pick a stream.
        modifier = modifier.ignoreInitialKeyUp().fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = focusPillColors(containerColor = Color.White.copy(alpha = 0.08f)),
    ) {
        content()
    }
}

private val PanelWidth = 520.dp
