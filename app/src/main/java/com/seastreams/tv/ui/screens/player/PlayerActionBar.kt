package com.seastreams.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.player.subtitle.SubtitleOffset
import com.seastreams.tv.player.subtitle.sync.hudCaption
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import kotlinx.coroutines.launch

/** One control on the bottom action bar, in left-to-right order (PRD §9). */
enum class BarControl {
    PLAY_PAUSE,
    NEXT_EPISODE,
    SUBTITLES,
    SUBTITLE_SYNC,
    AUDIO,
    STREAMS,
    EPISODES,
    INFO,
}

/**
 * The controls currently on the bar, left to right: Play/Pause, Next Episode (episodes only),
 * Subtitles, the `- 0.0s +` sync offset (only while an addon subtitle is on), Audio, Streams, Episodes
 * (episodes only), Info. Trailers show Play/Pause only. Its first and last entries are the wrap edges.
 */
fun actionBarControls(state: PlayerUiState): List<BarControl> = buildList {
    add(BarControl.PLAY_PAUSE)
    if (state.isTrailer) return@buildList
    if (state.isEpisode) add(BarControl.NEXT_EPISODE)
    // Pill visibility is shared with the overlay-close focus restore ([panelReturnFocus]).
    fun pill(control: BarControl, panel: PlayerPanel) {
        if (panel.onActionBar(state)) add(control)
    }
    pill(BarControl.SUBTITLES, PlayerPanel.SUBTITLES)
    if (state.externalSubtitleActive) add(BarControl.SUBTITLE_SYNC)
    pill(BarControl.AUDIO, PlayerPanel.AUDIO)
    pill(BarControl.STREAMS, PlayerPanel.STREAMS)
    pill(BarControl.EPISODES, PlayerPanel.EPISODES)
    pill(BarControl.INFO, PlayerPanel.INFO)
}

/**
 * Bottom action bar ([actionBarControls]). Edge-stop-then-wrap both ways (PRD §9): a fresh `Right` on
 * the last visible control wraps to Play/Pause, a fresh `Left` on Play/Pause wraps to the last visible
 * control; held keys stop at the edge.
 */
@Composable
fun PlayerActionBar(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    playButton: FocusRequester,
    panelButtons: Map<PlayerPanel, FocusRequester>,
) {
    val scope = rememberCoroutineScope()
    val lastControl = remember { FocusRequester() }
    val wrapToFirst: () -> Unit = { scope.launch { playButton.requestFocus() } }
    val wrapToLast: () -> Unit = { scope.launch { lastControl.requestFocus() } }
    val controls = actionBarControls(state)
    // The divider separates the round transport buttons from the pills (none on the trailer HUD).
    val firstPill = controls.indexOfFirst { it !in CircleControls }
    Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(BAR_SPACING), verticalAlignment = Alignment.CenterVertically) {
        controls.forEachIndexed { index, control ->
            if (index == firstPill) Divider()
            val edges = Modifier
                .then(if (index == 0) Modifier.onDpadEdgeWrap(Key.DirectionLeft, wrapToLast) else Modifier)
                .then(
                    if (index == controls.lastIndex) {
                        Modifier.focusRequester(lastControl).onDpadEdgeWrap(Key.DirectionRight, wrapToFirst)
                    } else {
                        Modifier
                    },
                )
            BarControlItem(control, state, viewModel, playButton, panelButtons, edges)
        }
    }
}

@Composable
private fun BarControlItem(
    control: BarControl,
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    playButton: FocusRequester,
    panelButtons: Map<PlayerPanel, FocusRequester>,
    modifier: Modifier,
) {
    when (control) {
        BarControl.PLAY_PAUSE -> CircleButton(
            icon = if (state.playing) SeaIcons.Pause else Icons.Filled.PlayArrow,
            description = if (state.playing) "Pause" else "Play",
            onClick = viewModel::togglePlayPause,
            // The HUD opens on Enter's KeyDown with focus here: its release must not un-pause.
            modifier = modifier.focusRequester(playButton).ignoreInitialKeyUp(),
        )
        BarControl.NEXT_EPISODE -> CircleButton(SeaIcons.SkipNext, "Next episode", viewModel::nextEpisode, modifier)
        BarControl.SUBTITLES -> PanelPill("Subtitles", PlayerPanel.SUBTITLES, state, viewModel, panelButtons, modifier)
        BarControl.SUBTITLE_SYNC -> SubtitleSync(state.subtitleOffsetMs, state.subtitleSyncStatus.hudCaption(), viewModel::stepSubtitleOffset, modifier)
        BarControl.AUDIO -> PanelPill("Audio", PlayerPanel.AUDIO, state, viewModel, panelButtons, modifier)
        BarControl.STREAMS -> PanelPill("Streams", PlayerPanel.STREAMS, state, viewModel, panelButtons, modifier)
        BarControl.EPISODES -> PanelPill("Episodes", PlayerPanel.EPISODES, state, viewModel, panelButtons, modifier)
        BarControl.INFO -> PanelPill("Info", PlayerPanel.INFO, state, viewModel, panelButtons, modifier)
    }
}

private val CircleControls = setOf(BarControl.PLAY_PAUSE, BarControl.NEXT_EPISODE)

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.3f)))
}

@Composable
private fun CircleButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(CIRCLE_SIZE),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = focusPillColors(containerColor = Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(Modifier.size(CIRCLE_SIZE), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun PanelPill(
    label: String,
    panel: PlayerPanel,
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    panelButtons: Map<PlayerPanel, FocusRequester>,
    modifier: Modifier,
) {
    val open = state.panel == panel
    Surface(
        onClick = { if (open) viewModel.closePanel() else viewModel.openPanel(panel) },
        modifier = modifier.then(panelButtons[panel]?.let { Modifier.focusRequester(it) } ?: Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = focusPillColors(containerColor = if (open) Color.White.copy(alpha = 0.25f) else Color.Transparent),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = PILL_PADDING, vertical = 10.dp),
        )
    }
}

/**
 * `-  0.0s  +`: `-` delays subtitles by 0.1 s, `+` shows them 0.1 s earlier (±60 s, session only).
 * Auto-sync's state shows as a small caption under the value (`Syncing…` while scanning, `Auto` once
 * locked), inside the same fixed-width slot, so the bar never grows wider (or taller than its 48 dp
 * buttons).
 */
@Composable
private fun SubtitleSync(offsetMs: Long, caption: String?, onStep: (Int) -> Unit, modifier: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SYNC_SPACING)) {
        SyncButton("−") { onStep(-1) }
        Column(
            Modifier.width(SYNC_LABEL_WIDTH).semantics(mergeDescendants = true) { contentDescription = listOfNotNull(SubtitleOffset.label(offsetMs), caption).joinToString(" · ") },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                SubtitleOffset.label(offsetMs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
        SyncButton("+", modifier) { onStep(1) }
    }
}

// Bar geometry (the 960 dp-wide width budget is checked in PlayerActionBarTest).
internal val BAR_SPACING = 10.dp
internal val CIRCLE_SIZE = 48.dp
internal val PILL_PADDING = 18.dp
internal val SYNC_BUTTON_SIZE = 40.dp
internal val SYNC_SPACING = 6.dp

/** Fits the widest value, `-60.0s`, in bold `titleSmall`; the `Syncing…`/`Auto` caption sits below it, not beside. */
internal val SYNC_LABEL_WIDTH = 64.dp

@Composable
private fun SyncButton(symbol: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(SYNC_BUTTON_SIZE),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = focusPillColors(containerColor = Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(Modifier.size(SYNC_BUTTON_SIZE), contentAlignment = Alignment.Center) {
            Text(symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}
