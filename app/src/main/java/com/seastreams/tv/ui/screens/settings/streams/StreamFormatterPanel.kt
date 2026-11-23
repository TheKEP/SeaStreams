package com.seastreams.tv.ui.screens.settings.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.streams.FormatterId
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.format.FormatterPreview
import com.seastreams.tv.ui.screens.playback.streamCardText
import com.seastreams.tv.ui.screens.settings.PanelColumn
import com.seastreams.tv.ui.screens.settings.RowTrailing
import com.seastreams.tv.ui.screens.settings.SettingsRow
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.focusItem
import com.seastreams.tv.ui.theme.verticalListEdgeWrap

/**
 * `Stream formatter` (`aio-formatter.png`): the predefined formatters as a radio list beside a live preview card that
 * renders the `aio-formatter.png` release with the *focused* formatter, exactly as the stream panel would show it.
 * Picking one saves it and returns to the list ([onDone]); custom templates are typed in the Web Portal.
 *
 * D-pad: opens on the chosen formatter (ignoring the `KeyUp` of the `OK` that opened the panel); two-way
 * edge-stop-then-wrap between the first and last option; `Back` is the Settings screen's panel BackHandler.
 */
@Composable
fun StreamFormatterPanel(choice: StreamFormatterChoice, nowMillis: Long, onPick: (FormatterId) -> Unit, onDone: () -> Unit) {
    val options = FormatterId.entries
    var focused by remember { mutableStateOf(choice.id) }
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    val selected = remember { FocusRequester() }
    val wrap = rememberListEdgeWrap(listState, first, last, options.lastIndex)
    // The chosen option can start below the fold (e.g. Custom, the last one): scroll to it before focusing, or its
    // requester isn't attached yet and nothing gets focus. Falls back to the first option.
    LaunchedEffect(Unit) {
        if (!listState.focusItem(formatterEntryIndex(options, choice.id), selected)) listState.focusItem(0, first)
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.weight(0.45f)) {
            PanelColumn("Stream formatter", listState) {
                itemsIndexed(options, key = { _, id -> id.name }) { index, id ->
                    val isChosen = id == choice.id
                    SettingsRow(
                        icon = if (isChosen) Icons.Filled.Check else null,
                        title = formatterLabel(id, choice),
                        value = if (isChosen) "Selected" else null,
                        trailing = RowTrailing.None,
                        onClick = {
                            onPick(id)
                            onDone()
                        },
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) focused = id }
                            .then(if (isChosen) Modifier.focusRequester(selected).ignoreInitialKeyUp() else Modifier)
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .then(if (index == options.lastIndex) Modifier.focusRequester(last) else Modifier)
                            .verticalListEdgeWrap(index, options.lastIndex, wrap.toFirst, wrap.toLast),
                    )
                }
            }
        }
        FormatterPreview(choice.copy(id = focused), nowMillis, Modifier.weight(0.55f).padding(top = 92.dp))
    }
}

/** The list index the panel opens on: the chosen formatter, else the first. */
internal fun formatterEntryIndex(options: List<FormatterId>, chosen: FormatterId): Int = options.indexOf(chosen).coerceAtLeast(0)

/** The `PREVIEW` card: the sample release as the stream panel would show it with [choice]. */
@Composable
private fun FormatterPreview(choice: StreamFormatterChoice, nowMillis: Long, modifier: Modifier = Modifier) {
    val display = remember(choice, nowMillis / PREVIEW_REFRESH_MS) { previewDisplay(choice, nowMillis) }
    val text = streamCardText(FormatterPreview.SAMPLE.copy(display = display))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PREVIEW", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
        Text(
            if (choice.id == FormatterId.ORIGINAL) "The addon's own text" else "How streams look with ${choice.id.label}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text.header, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(text.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            text.detailLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private const val PREVIEW_REFRESH_MS = 60_000L
