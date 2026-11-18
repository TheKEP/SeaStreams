package com.seastreams.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.player.subtitle.SubtitleOffset
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import kotlinx.coroutines.launch

/**
 * Bottom action bar, left to right (PRD §9): Play/Pause, Next Episode (episodes only), Subtitles, the
 * `- 0.0s +` sync offset (only while an addon subtitle is on), Audio, Streams, Episodes (episodes
 * only), Info. Trailers show Play/Pause only. A fresh `Right` on the last button wraps to the first.
 */
@Composable
fun PlayerActionBar(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    playButton: FocusRequester,
    panelButtons: Map<PlayerPanel, FocusRequester>,
) {
    val scope = rememberCoroutineScope()
    val wrapToFirst: () -> Unit = { scope.launch { playButton.requestFocus() } }
    val items = buildList<@Composable (Modifier) -> Unit> {
        add { modifier ->
            CircleButton(
                icon = if (state.playing) SeaIcons.Pause else Icons.Filled.PlayArrow,
                description = if (state.playing) "Pause" else "Play",
                onClick = viewModel::togglePlayPause,
                // The HUD opens on Enter's KeyDown with focus here: its release must not un-pause.
                modifier = modifier.focusRequester(playButton).ignoreInitialKeyUp(),
            )
        }
        if (state.isTrailer) return@buildList
        if (state.isEpisode) add { modifier -> CircleButton(SeaIcons.SkipNext, "Next episode", viewModel::nextEpisode, modifier) }
        add { modifier -> PanelPill("Subtitles", PlayerPanel.SUBTITLES, state, viewModel, panelButtons, modifier) }
        if (state.externalSubtitleActive) {
            add { modifier -> SubtitleSync(state.subtitleOffsetMs, viewModel::stepSubtitleOffset, modifier) }
        }
        add { modifier -> PanelPill("Audio", PlayerPanel.AUDIO, state, viewModel, panelButtons, modifier) }
        add { modifier -> PanelPill("Streams", PlayerPanel.STREAMS, state, viewModel, panelButtons, modifier) }
        if (state.isEpisode) add { modifier -> PanelPill("Episodes", PlayerPanel.EPISODES, state, viewModel, panelButtons, modifier) }
        add { modifier -> PanelPill("Info", PlayerPanel.INFO, state, viewModel, panelButtons, modifier) }
    }
    Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val firstPill = if (state.isEpisode) 2 else 1
        items.forEachIndexed { index, item ->
            if (index == firstPill) Divider()
            item(if (index == items.lastIndex) Modifier.onDpadEdgeWrap(Key.DirectionRight, wrapToFirst) else Modifier)
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.3f)))
}

@Composable
private fun CircleButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = focusPillColors(containerColor = Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
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
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** `-  0.0s  +`: `-` delays subtitles by 0.1 s, `+` shows them 0.1 s earlier (±60 s, session only). */
@Composable
private fun SubtitleSync(offsetMs: Long, onStep: (Int) -> Unit, modifier: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SyncButton("−") { onStep(-1) }
        Text(
            SubtitleOffset.label(offsetMs),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.width(64.dp).padding(horizontal = 4.dp),
            textAlign = TextAlign.Center,
        )
        SyncButton("+", modifier) { onStep(1) }
    }
}

@Composable
private fun SyncButton(symbol: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = focusPillColors(containerColor = Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Text(symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}
