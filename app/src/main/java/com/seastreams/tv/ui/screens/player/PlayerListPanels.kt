package com.seastreams.tv.ui.screens.player

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import com.seastreams.tv.core.datastore.PlaybackSpeed
import com.seastreams.tv.ui.screens.settings.SettingsSectionHeader
import com.seastreams.tv.ui.theme.focusItem

/**
 * Speed overlay (session only, PRD §9): one row per [PlaybackSpeed]. It opens scrolled so the active speed is fully
 * on screen, with focus on it (a row below the viewport has no attached requester until it is scrolled in, so a
 * plain focus request would land nowhere). Choosing a speed applies it at once and keeps the overlay open, without
 * moving focus; `Back` returns focus to the Speed pill.
 */
@Composable
fun SpeedPanel(state: PlayerUiState, viewModel: PlayerViewModel, bottomInset: Dp, modifier: Modifier) {
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val active = remember { FocusRequester() }
    // Fixed when the overlay opens: picking another speed inside it must not move focus.
    val openedOn = remember { state.playbackSpeed }
    PanelScaffold(
        "Playback speed", bottomInset, modifier, listState,
        onOpen = {
            if (!listState.focusItem(speedFocusIndex(openedOn), active)) listState.focusItem(speedFocusIndex(PlaybackSpeed.entries.first()), first)
        },
    ) {
        item(key = "h-speed") { SettingsSectionHeader("This video only") }
        PlaybackSpeed.entries.forEach { speed ->
            item(key = "speed-${speed.name}") {
                val requester = when (speed) {
                    openedOn -> Modifier.focusRequester(active)
                    PlaybackSpeed.entries.first() -> Modifier.focusRequester(first)
                    else -> Modifier
                }
                OptionRow(
                    label = speed.label,
                    detail = null,
                    selected = speed == state.playbackSpeed,
                    onClick = { viewModel.setPlaybackSpeed(speed) },
                    modifier = requester,
                )
            }
        }
    }
}

/** The Speed overlay's lazy index for [speed]: its rows follow the one "This video only" header. */
internal fun speedFocusIndex(speed: PlaybackSpeed): Int = SPEED_HEADER_ROWS + speed.ordinal

private const val SPEED_HEADER_ROWS = 1
