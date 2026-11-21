package com.seastreams.tv.ui.screens.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.core.datastore.SubtitleEdgeType
import com.seastreams.tv.core.datastore.SubtitleStyle
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import kotlin.math.roundToInt

/** Subtitle text colours offered as swatches (no hex shown). */
val SUBTITLE_COLORS: List<Pair<String, Int>> = listOf(
    "White" to SubtitleStyle.WHITE_ARGB,
    "Yellow" to 0xFFFFEB3B.toInt(),
    "Cyan" to 0xFF4DD0E1.toInt(),
    "Green" to 0xFF69F0AE.toInt(),
    "Pink" to 0xFFFF80AB.toInt(),
    "Light grey" to 0xFFBDBDBD.toInt(),
)

/**
 * Global subtitle styling (PRD §11): the 16:9 [SubtitlePreviewStage] stays pinned at the top while the
 * controls scroll below it, and every change re-renders the sample cue at once. Colour swatches
 * preview on focus and save on `Enter`.
 */
@Composable
fun SubtitleStyleScreen(style: SubtitleStyle, onChange: ((SubtitleStyle) -> SubtitleStyle) -> Unit) {
    var previewColor by remember { mutableStateOf<Int?>(null) }
    val shown = previewColor?.let { style.copy(textColor = it) } ?: style
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusAfterFrame() }

    Column(Modifier.fillMaxSize().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Subtitle styling", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        SubtitlePreviewStage(shown, Modifier.width(StageWidth))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "size") {
                SettingsRow(
                    SeaIcons.TextSize, "Font size", "${(style.fontScale * PERCENT).roundToInt()}%",
                    RowTrailing.Stepper(
                        onDecrease = { onChange { it.copy(fontScale = stepFontScale(it.fontScale, -1)) } },
                        onIncrease = { onChange { it.copy(fontScale = stepFontScale(it.fontScale, 1)) } },
                    ),
                    onClick = { onChange { it.copy(fontScale = 1f) } },
                    modifier = Modifier.focusRequester(first),
                )
            }
            item(key = "bold") {
                SettingsRow(SeaIcons.TextSize, "Bold", onOff(style.bold), RowTrailing.Toggle(style.bold), { onChange { it.copy(bold = !it.bold) } })
            }
            item(key = "italic") {
                SettingsRow(SeaIcons.TextSize, "Italic", onOff(style.italic), RowTrailing.Toggle(style.italic), { onChange { it.copy(italic = !it.italic) } })
            }
            item(key = "color") {
                TextColorRow(
                    selected = style.textColor,
                    onPreview = { previewColor = it },
                    onSelect = { argb ->
                        previewColor = null
                        onChange { it.copy(textColor = argb) }
                    },
                )
            }
            item(key = "edge") {
                SettingsRow(
                    SeaIcons.Subtitles, "Outline / drop shadow", edgeLabel(style.edgeType),
                    RowTrailing.Stepper(
                        onDecrease = { onChange { it.copy(edgeType = stepEdge(it.edgeType, -1)) } },
                        onIncrease = { onChange { it.copy(edgeType = stepEdge(it.edgeType, 1)) } },
                    ),
                    onClick = { onChange { it.copy(edgeType = stepEdge(it.edgeType, 1)) } },
                )
            }
            item(key = "background") {
                SettingsRow(
                    SeaIcons.Brightness, "Background opacity", "${(style.backgroundOpacity * PERCENT).roundToInt()}%",
                    RowTrailing.Stepper(
                        onDecrease = { onChange { it.copy(backgroundOpacity = stepOpacity(it.backgroundOpacity, -1)) } },
                        onIncrease = { onChange { it.copy(backgroundOpacity = stepOpacity(it.backgroundOpacity, 1)) } },
                    ),
                    onClick = { onChange { it.copy(backgroundOpacity = 0f) } },
                    modifier = Modifier.onDpadEdgeWrap(Key.DirectionDown) { first.tryRequestFocus() },
                )
            }
        }
    }
}

@Composable
private fun TextColorRow(selected: Int, onPreview: (Int?) -> Unit, onSelect: (Int) -> Unit) {
    val selectedRequester = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Text color", style = MaterialTheme.typography.titleSmall, color = TextSecondary)
        Row(
            Modifier
                .focusRestorer(selectedRequester)
                .onFocusChanged { if (!it.hasFocus) onPreview(null) }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SUBTITLE_COLORS.forEach { (name, argb) ->
                ColorSwatch(
                    color = Color(argb),
                    name = name,
                    saved = argb == selected,
                    onFocused = { onPreview(argb) },
                    onClick = { onSelect(argb) },
                    modifier = if (argb == selected) Modifier.focusRequester(selectedRequester) else Modifier,
                )
            }
        }
    }
}

private fun onOff(value: Boolean) = if (value) "On" else "Off"

/** ±10% steps within 50%–200%. */
internal fun stepFontScale(scale: Float, direction: Int): Float {
    val steps = (scale * TENTHS).roundToInt() + direction
    return (steps / TENTHS).coerceIn(SubtitleStyle.MIN_FONT_SCALE, SubtitleStyle.MAX_FONT_SCALE)
}

/** ±10% steps within 0%–100%. */
internal fun stepOpacity(opacity: Float, direction: Int): Float =
    (((opacity * TENTHS).roundToInt() + direction) / TENTHS).coerceIn(0f, 1f)

internal fun stepEdge(edge: SubtitleEdgeType, direction: Int): SubtitleEdgeType {
    val all = SubtitleEdgeType.entries
    return all[Math.floorMod(edge.ordinal + direction, all.size)]
}

private val StageWidth = 440.dp
private const val PERCENT = 100
private const val TENTHS = 10f
