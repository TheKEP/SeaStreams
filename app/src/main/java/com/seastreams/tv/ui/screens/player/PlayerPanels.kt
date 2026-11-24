package com.seastreams.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.core.datastore.AudioSettings
import com.seastreams.tv.core.datastore.DialogueBoost
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.screens.playback.StreamListPanel
import com.seastreams.tv.ui.screens.settings.RowTrailing
import com.seastreams.tv.ui.screens.settings.SettingsRow
import com.seastreams.tv.ui.screens.settings.SettingsSectionHeader
import com.seastreams.tv.ui.screens.settings.audioDelayLabel
import com.seastreams.tv.ui.screens.settings.dialogueLabel
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

/**
 * The open overlay, as a right-hand panel over the still-playing video. [hudBottomBlock] is the measured height of the
 * HUD's seek row + action bar block (`null` before it is measured), which the Info panel reaches down to.
 */
@Composable
fun PlayerPanels(panel: PlayerPanel, state: PlayerUiState, viewModel: PlayerViewModel, hudBottomBlock: Dp?, modifier: Modifier) {
    when (panel) {
        PlayerPanel.SUBTITLES -> SubtitlesPanel(state, viewModel, modifier)
        PlayerPanel.AUDIO -> PanelScaffold("Audio", modifier) { first -> audioItems(state, viewModel, first) }
        // Same container as the Details stream modal; Back is handled by the player (focus → Streams button).
        PlayerPanel.STREAMS -> StreamListPanel(
            title = state.title,
            subtitle = if (state.isTrailer) "Trailer" else state.subtitle,
            loading = false,
            streams = state.streams,
            emptyText = "No other streams",
            onEmptyClick = {},
            onChoose = { index, _ -> viewModel.selectStream(index) },
            modifier = modifier,
            selectedIndex = state.streamIndex,
            lastUsedIndex = state.lastUsedIndex,
            addons = state.streamAddons,
            showRank = true,
        )
        PlayerPanel.EPISODES -> EpisodesPanel(state, viewModel, modifier)
        PlayerPanel.INFO -> InfoPanel(viewModel, infoPanelBottomInset(hudBottomBlock), modifier)
    }
}

/**
 * Right-hand panel: focus starts on its first entry and can't wander back to the HUD with the D-pad;
 * `Back` closes it (handled by the player) and returns focus to the bar button.
 */
@Composable
fun PanelScaffold(title: String, modifier: Modifier, content: LazyListScope.(first: FocusRequester) -> Unit) {
    val first = remember { FocusRequester() }
    PanelScaffold(title, modifier, rememberLazyListState(), onOpen = { first.requestFocusAfterFrame() }) { content(first) }
}

/** [PanelScaffold] with its own [listState] and [onOpen] focus action (e.g. focus the active row). */
@Composable
fun PanelScaffold(title: String, modifier: Modifier, listState: LazyListState, onOpen: suspend () -> Unit, content: LazyListScope.() -> Unit) {
    LaunchedEffect(title) { onOpen() }
    Column(
        modifier
            .padding(bottom = PANEL_BOTTOM_INSET)
            .fillMaxHeight()
            .width(PanelWidth)
            .background(SurfaceCard.copy(alpha = 0.94f))
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .trapPanelFocus(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

fun Modifier.trapPanelFocus(): Modifier = focusProperties {
    onExit = {
        if (requestedFocusDirection != FocusDirection.Enter && requestedFocusDirection != FocusDirection.Exit) cancelFocusChange()
    }
}.focusGroup()

/** A selectable entry with a check mark when [selected]. */
@Composable
fun OptionRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsRow(
        icon = if (selected) Icons.Filled.Check else null,
        title = label,
        value = detail,
        trailing = RowTrailing.None,
        onClick = onClick,
        modifier = modifier,
    )
}

private fun LazyListScope.audioItems(state: PlayerUiState, viewModel: PlayerViewModel, first: FocusRequester) {
    if (state.audioOptions.isEmpty()) {
        item(key = "none") { OptionRow("No audio tracks yet", null, selected = false, onClick = {}, modifier = Modifier.focusRequester(first)) }
    }
    state.audioOptions.forEachIndexed { index, option ->
        item(key = "audio-${option.id}") {
            OptionRow(
                option.label, null, option.id == state.selectedAudioId, { viewModel.selectAudio(option.id) },
                if (index == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
    item(key = "h-dsp") { SettingsSectionHeader("Sound (this video only)") }
    item(key = "passthrough") {
        SettingsRow(
            SeaIcons.Volume, "Audio passthrough", passthroughDetail(state), RowTrailing.Toggle(state.audio.passthrough),
            { viewModel.updateSessionAudio { it.copy(passthrough = !it.passthrough) } },
        )
    }
    val audio = state.audio
    item(key = "boost") {
        val step: (Int) -> Unit = { direction ->
            val all = DialogueBoost.entries
            viewModel.updateSessionAudio { it.copy(dialogueBoost = all[(all.indexOf(it.dialogueBoost) + direction).coerceIn(0, all.lastIndex)]) }
        }
        SettingsRow(SeaIcons.Equalizer, "Dialogue boost", dialogueLabel(audio.dialogueBoost.gainDb), RowTrailing.Stepper({ step(-1) }, { step(1) }), { step(1) })
    }
    item(key = "night") {
        SettingsRow(SeaIcons.Moon, "Night mode", null, RowTrailing.Toggle(audio.nightMode), { viewModel.updateSessionAudio { it.copy(nightMode = !it.nightMode) } })
    }
    item(key = "delay") {
        val step: (Int) -> Unit = { direction ->
            viewModel.updateSessionAudio { it.copy(delayMs = SettingsDataStore.snapAudioDelay(it.delayMs + direction * AudioSettings.AUDIO_DELAY_STEP_MS)) }
        }
        SettingsRow(SeaIcons.Timer, "Audio delay", audioDelayLabel(audio.delayMs), RowTrailing.Stepper({ step(-1) }, { step(1) }), {
            viewModel.updateSessionAudio { it.copy(delayMs = 0) }
        })
    }
}

/** The session passthrough row's detail: while bitstreaming, the effects below don't apply yet. */
internal fun passthroughDetail(state: PlayerUiState): String = when {
    !state.audio.passthrough -> "Off: effects below apply"
    state.dspAvailable -> "On: this track plays as PCM, effects apply"
    else -> "On: sent to your receiver untouched. Turning on an effect switches this off"
}

internal val PanelWidth = 500.dp

/**
 * Right-hand panels end above the HUD's bottom block (24 dp padding + seek row + 14 dp + 48 dp action
 * bar ≈ 134 dp), so they never cover the action bar.
 */
internal val PANEL_BOTTOM_INSET = 150.dp

/** The HUD's bottom block a panel must clear: vertical padding, seek row (~24 dp), spacing, action bar. */
internal val HUD_BOTTOM_BLOCK = 24.dp + 24.dp + 14.dp + CIRCLE_SIZE + 24.dp

/** The HUD bottom block's vertical padding (above the seek row and below the action bar). */
internal val HUD_BOTTOM_PADDING = 24.dp

/** Clear space between the Info card and the seek row / remaining-time text below it. */
internal val INFO_PANEL_GAP = 12.dp

/**
 * Info's bottom inset before the HUD block is measured: bottom padding + action bar + spacing + seek row (~24 dp) +
 * [INFO_PANEL_GAP]. It sits just above the seek row, lower than the other panels' [PANEL_BOTTOM_INSET].
 */
internal val INFO_PANEL_BOTTOM_INSET = HUD_BOTTOM_PADDING + CIRCLE_SIZE + 14.dp + 24.dp + INFO_PANEL_GAP

/**
 * The Info card ends [INFO_PANEL_GAP] above the top of the HUD's seek row: the measured block ([measured], padding
 * included) minus its top padding. Measured, so a larger font scale (a taller time label) lifts the card instead of
 * overlapping the `-2:44:28 / 2:52:35` text.
 */
internal fun infoPanelBottomInset(measured: Dp?): Dp =
    measured?.takeIf { it > HUD_BOTTOM_PADDING }?.let { it - HUD_BOTTOM_PADDING + INFO_PANEL_GAP } ?: INFO_PANEL_BOTTOM_INSET

/** Subtitle side padding; while a right-hand panel is open the cue is drawn left of it, never under it. */
internal fun cueEndPadding(panel: PlayerPanel?): Dp =
    if (panel == null || panel == PlayerPanel.STREAMS) CUE_SIDE_PADDING else PanelWidth + CUE_PANEL_GAP

internal val CUE_SIDE_PADDING = 96.dp
private val CUE_PANEL_GAP = 24.dp
