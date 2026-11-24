package com.seastreams.tv.ui.screens.player

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.screens.settings.RowTrailing
import com.seastreams.tv.ui.screens.settings.SUBTITLE_COLORS
import com.seastreams.tv.ui.screens.settings.SettingsRow
import com.seastreams.tv.ui.screens.settings.SettingsSectionHeader
import com.seastreams.tv.ui.screens.settings.edgeLabel
import com.seastreams.tv.ui.screens.settings.stepEdge
import com.seastreams.tv.ui.screens.settings.stepFontScale
import com.seastreams.tv.ui.screens.settings.stepOpacity
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.focusItem
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.verticalListEdgeWrap

/**
 * The Subtitles overlay's rows in lazy order: `Off`, one per track, then the Options and Style sections
 * (their headers aren't focusable and sit in the middle, so `Off` and `Background` are the wrap edges).
 * [activeIndex] is the row of the active track, or `Off` (0) when subtitles are off or the selected
 * track is no longer listed.
 */
class SubtitlePanelLayout(val keys: List<String>, val activeIndex: Int) {
    val firstIndex: Int get() = 0
    val lastIndex: Int get() = keys.lastIndex

    fun indexOf(key: String): Int = keys.indexOf(key)
}

internal fun subtitleRowKey(id: String) = "sub-$id"

fun subtitlePanelLayout(state: PlayerUiState): SubtitlePanelLayout {
    val keys = buildList {
        add(KEY_OFF)
        state.subtitleOptions.forEach { add(subtitleRowKey(it.id)) }
        add(KEY_OPTIONS_HEADER)
        add(KEY_SDH)
        add(KEY_STYLE_HEADER)
        addAll(STYLE_KEYS)
    }
    val selected = state.subtitleOptions.indexOfFirst { it.id == state.selectedSubtitleId }
    return SubtitlePanelLayout(keys, if (selected < 0) 0 else selected + 1)
}

/**
 * The in-player Subtitles overlay (PRD §9): opens scrolled to the active track (or `Off`) with focus on
 * it, and wraps both ways under the §1 edge-stop-then-wrap rule — a fresh `Up` on `Off` goes to
 * `Background`, a fresh `Down` on `Background` to `Off`, held keys stop on the edge. Track rows ignore
 * the release of the key that opened the menu, so opening it never picks a track.
 */
@Composable
fun SubtitlesPanel(state: PlayerUiState, viewModel: PlayerViewModel, bottomInset: Dp, modifier: Modifier) {
    val layout = subtitlePanelLayout(state)
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    val active = remember { FocusRequester() }
    // Fixed when the menu opens: picking another track inside it must not move focus.
    val openedOn = remember { layout.activeIndex }
    val wrap = rememberListEdgeWrap(listState, first, last, layout.lastIndex)
    val trackRow: (Int) -> Modifier = { index ->
        (if (index == openedOn) Modifier.focusRequester(active) else Modifier).ignoreInitialKeyUp()
    }
    PanelScaffold(
        "Subtitles", bottomInset, modifier, listState,
        onOpen = {
            // One row of context above the active track, then make sure the track itself is fully on screen.
            listState.scrollToItem((openedOn - 1).coerceAtLeast(0))
            if (!listState.focusItem(openedOn, active)) listState.focusItem(layout.firstIndex, first)
        },
    ) {
        item(key = KEY_OFF) {
            val edge = Modifier.focusRequester(first).verticalListEdgeWrap(layout.firstIndex, layout.lastIndex, wrap.toFirst, wrap.toLast)
            OptionRow("Off", null, state.selectedSubtitleId == null, { viewModel.selectSubtitle(null) }, edge.then(trackRow(0)))
        }
        state.subtitleOptions.forEachIndexed { position, option ->
            item(key = subtitleRowKey(option.id)) {
                OptionRow(option.label, option.source.label, option.id == state.selectedSubtitleId, { viewModel.selectSubtitle(option) }, trackRow(position + 1))
            }
        }
        item(key = KEY_OPTIONS_HEADER) { SettingsSectionHeader("Options") }
        item(key = KEY_SDH) {
            SettingsRow(SeaIcons.Subtitles, "Clean SDH", "Hide [sound cues] and SPEAKER: labels", RowTrailing.Toggle(state.cleanSdh), { viewModel.setCleanSdh(!state.cleanSdh) })
        }
        item(key = KEY_STYLE_HEADER) { SettingsSectionHeader("Style (this video only)") }
        styleItems(state, viewModel, Modifier.focusRequester(last).verticalListEdgeWrap(layout.lastIndex, layout.lastIndex, wrap.toFirst, wrap.toLast))
    }
}

/** Session-only subtitle style rows; [lastRow] goes on `Background`, the list's last row. */
private fun LazyListScope.styleItems(state: PlayerUiState, viewModel: PlayerViewModel, lastRow: Modifier) {
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
            lastRow,
        )
    }
}

internal const val KEY_OFF = "off"
private const val KEY_OPTIONS_HEADER = "h-sdh"
private const val KEY_SDH = "sdh"
private const val KEY_STYLE_HEADER = "h-style"
internal val STYLE_KEYS = listOf("size", "bold", "italic", "color", "edge", "background")
private const val PERCENT = 100
