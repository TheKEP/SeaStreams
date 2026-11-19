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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.core.datastore.AudioSettings
import com.seastreams.tv.core.datastore.DialogueBoost
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.screens.playback.StreamListPanel
import com.seastreams.tv.ui.screens.settings.RowTrailing
import com.seastreams.tv.ui.screens.settings.SUBTITLE_COLORS
import com.seastreams.tv.ui.screens.settings.SettingsRow
import com.seastreams.tv.ui.screens.settings.SettingsSectionHeader
import com.seastreams.tv.ui.screens.settings.audioDelayLabel
import com.seastreams.tv.ui.screens.settings.dialogueLabel
import com.seastreams.tv.ui.screens.settings.edgeLabel
import com.seastreams.tv.ui.screens.settings.stepEdge
import com.seastreams.tv.ui.screens.settings.stepFontScale
import com.seastreams.tv.ui.screens.settings.stepOpacity
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

/** The open overlay, as a right-hand panel over the still-playing video. */
@Composable
fun PlayerPanels(panel: PlayerPanel, state: PlayerUiState, viewModel: PlayerViewModel, modifier: Modifier) {
    when (panel) {
        PlayerPanel.SUBTITLES -> PanelScaffold("Subtitles", modifier) { first -> subtitleItems(state, viewModel, first) }
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
            showRank = true,
        )
        PlayerPanel.EPISODES -> EpisodesPanel(state, viewModel, modifier)
        PlayerPanel.INFO -> InfoPanel(viewModel, modifier)
    }
}

/**
 * Right-hand panel: focus starts on its first entry and can't wander back to the HUD with the D-pad;
 * `Back` closes it (handled by the player) and returns focus to the bar button.
 */
@Composable
fun PanelScaffold(title: String, modifier: Modifier, content: LazyListScope.(first: FocusRequester) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(title) { first.requestFocusAfterFrame() }
    Column(
        modifier
            .fillMaxHeight()
            .width(PanelWidth)
            .background(SurfaceCard.copy(alpha = 0.94f))
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .trapPanelFocus(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content(first) }
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

private fun LazyListScope.subtitleItems(state: PlayerUiState, viewModel: PlayerViewModel, first: FocusRequester) {
    item(key = "off") {
        OptionRow("Off", null, state.selectedSubtitleId == null, { viewModel.selectSubtitle(null) }, Modifier.focusRequester(first))
    }
    state.subtitleOptions.forEach { option ->
        item(key = "sub-${option.id}") {
            OptionRow(option.label, option.source.label, option.id == state.selectedSubtitleId, { viewModel.selectSubtitle(option) })
        }
    }
    item(key = "h-sdh") { SettingsSectionHeader("Options") }
    item(key = "sdh") {
        SettingsRow(SeaIcons.Subtitles, "Clean SDH", "Hide [sound cues] and SPEAKER: labels", RowTrailing.Toggle(state.cleanSdh), { viewModel.setCleanSdh(!state.cleanSdh) })
    }
    item(key = "h-style") { SettingsSectionHeader("Style (this video only)") }
    val style = state.subtitleStyle
    item(key = "size") {
        SettingsRow(
            SeaIcons.TextSize, "Font size", "${(style.fontScale * PERCENT).toInt()}%",
            RowTrailing.Stepper(
                onDecrease = { viewModel.updateSubtitleStyle { it.copy(fontScale = stepFontScale(it.fontScale, -1)) } },
                onIncrease = { viewModel.updateSubtitleStyle { it.copy(fontScale = stepFontScale(it.fontScale, 1)) } },
            ),
            { viewModel.updateSubtitleStyle { it.copy(fontScale = stepFontScale(it.fontScale, 1)) } },
        )
    }
    item(key = "bold") {
        SettingsRow(null, "Bold", null, RowTrailing.Toggle(style.bold), { viewModel.updateSubtitleStyle { it.copy(bold = !it.bold) } })
    }
    item(key = "italic") {
        SettingsRow(null, "Italic", null, RowTrailing.Toggle(style.italic), { viewModel.updateSubtitleStyle { it.copy(italic = !it.italic) } })
    }
    item(key = "color") {
        val index = SUBTITLE_COLORS.indexOfFirst { it.second == style.textColor }.coerceAtLeast(0)
        val step: (Int) -> Unit = { direction ->
            val next = SUBTITLE_COLORS[Math.floorMod(index + direction, SUBTITLE_COLORS.size)].second
            viewModel.updateSubtitleStyle { it.copy(textColor = next) }
        }
        SettingsRow(SeaIcons.Palette, "Text color", SUBTITLE_COLORS[index].first, RowTrailing.Stepper({ step(-1) }, { step(1) }), { step(1) })
    }
    item(key = "edge") {
        SettingsRow(
            null, "Edge", edgeLabel(style.edgeType),
            RowTrailing.Stepper(
                onDecrease = { viewModel.updateSubtitleStyle { it.copy(edgeType = stepEdge(it.edgeType, -1)) } },
                onIncrease = { viewModel.updateSubtitleStyle { it.copy(edgeType = stepEdge(it.edgeType, 1)) } },
            ),
            { viewModel.updateSubtitleStyle { it.copy(edgeType = stepEdge(it.edgeType, 1)) } },
        )
    }
    item(key = "background") {
        SettingsRow(
            null, "Background", "${(style.backgroundOpacity * PERCENT).toInt()}%",
            RowTrailing.Stepper(
                onDecrease = { viewModel.updateSubtitleStyle { it.copy(backgroundOpacity = stepOpacity(it.backgroundOpacity, -1)) } },
                onIncrease = { viewModel.updateSubtitleStyle { it.copy(backgroundOpacity = stepOpacity(it.backgroundOpacity, 1)) } },
            ),
            { viewModel.updateSubtitleStyle { it.copy(backgroundOpacity = stepOpacity(it.backgroundOpacity, 1)) } },
        )
    }
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
    if (!state.dspAvailable) {
        item(key = "dsp-off") {
            Text(
                "Turn off Audio passthrough in Settings to use Dialogue Boost, Night Mode and Audio Delay.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        return
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

internal val PanelWidth = 500.dp
private const val PERCENT = 100
