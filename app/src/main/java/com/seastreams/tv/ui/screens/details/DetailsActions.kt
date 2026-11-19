package com.seastreams.tv.ui.screens.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.components.FocusOrigin
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.components.rememberFocusOrigin
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.rememberSelectLongPress
import com.seastreams.tv.ui.theme.selectLongPress
import kotlinx.coroutines.launch

const val CLEAR_PROGRESS_LABEL = "Clear progress"

/**
 * One entry of the Details action stack. [onLongClick] is only set on `Play` (PRD §6). An action with
 * [focusFirstOnClick] removes its own button, so the stack moves focus to the first button (`Play`)
 * before running [onClick], while the clicked node still exists.
 */
data class DetailsAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val focusFirstOnClick: Boolean = false,
)

/**
 * Builds the stack for [state]: `Play`, `Trailer`, show-only `More episodes` + `Shuffle`, the library
 * toggle, then — movies in the `Resume` state only — `Clear progress` as the bottom-most button.
 */
fun detailsActions(
    state: DetailsUiState,
    onPlay: (forcePicker: Boolean) -> Unit,
    onTrailer: () -> Unit,
    onMoreEpisodes: () -> Unit,
    onShuffle: () -> Unit,
    onToggleLibrary: () -> Unit,
    onClearProgress: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
): List<DetailsAction> = when (state.content) {
    DetailsContent.Restricted -> listOf(DetailsAction("back", "Go back", Icons.AutoMirrored.Filled.ArrowBack, onBack))
    DetailsContent.Failed -> listOf(
        DetailsAction("retry", "Retry", Icons.Filled.Refresh, onRetry),
        DetailsAction("back", "Go back", Icons.AutoMirrored.Filled.ArrowBack, onBack),
    )
    else -> buildList {
        add(DetailsAction("play", state.playLabel, Icons.Filled.PlayArrow, { onPlay(false) }, onLongClick = { onPlay(true) }))
        add(DetailsAction("trailer", "Trailer", SeaIcons.Trailer, onTrailer))
        if (state.isShow) {
            add(DetailsAction("episodes", "More episodes", SeaIcons.Episodes, onMoreEpisodes))
            add(DetailsAction("shuffle", "Shuffle", SeaIcons.Shuffle, onShuffle))
        }
        add(
            DetailsAction(
                key = "library",
                label = state.libraryLabel,
                icon = if (state.inLibrary) SeaIcons.LibraryAdded else SeaIcons.LibraryAdd,
                onClick = onToggleLibrary,
            ),
        )
        if (state.canClearProgress) {
            add(DetailsAction("clear", CLEAR_PROGRESS_LABEL, Icons.Filled.Close, onClearProgress, focusFirstOnClick = true))
        }
    }
}

/**
 * Vertical button stack (`movie-page.jpg`): plain icon + label rows that turn into a light pill while
 * focused. Every button records itself as the focus origin when pressed, so closing what it opened
 * (stream list, Episodes) lands back on it. Edge-stop-then-wrap both ways (PRD §6): a fresh `Down`
 * on the last button wraps to the first, a fresh `Up` on the first wraps to the last; held keys stop.
 * The last button is `Clear progress` while it is shown, otherwise the library toggle. Buttons are
 * keyed and never animated: `Resume` → `Play` only swaps the label, and dropping the bottom button
 * moves nothing above it.
 */
@Composable
fun DetailsActionStack(actions: List<DetailsAction>, firstButton: FocusRequester, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val lastButton = remember { FocusRequester() }
    val focusFirst: () -> Unit = remember(firstButton) { { firstButton.requestFocus() } }
    Column(modifier.width(StackWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEachIndexed { index, action ->
            key(action.key) {
                val origin = rememberFocusOrigin()
                DetailsActionButton(
                    action = action,
                    origin = origin,
                    focusFirst = focusFirst,
                    modifier = Modifier
                        .then(
                            if (index == 0) {
                                Modifier
                                    .focusRequester(firstButton)
                                    .onDpadEdgeWrap(Key.DirectionUp) { scope.launch { lastButton.requestFocus() } }
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (index == actions.lastIndex) {
                                Modifier
                                    .focusRequester(lastButton)
                                    .onDpadEdgeWrap(Key.DirectionDown) { scope.launch { firstButton.requestFocus() } }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun DetailsActionButton(action: DetailsAction, origin: FocusOrigin, focusFirst: () -> Unit, modifier: Modifier) {
    // Not the Surface's own onLongClick: its flag outlives the stream list taking focus and would
    // swallow the first click back on `Play` (PRD §6).
    val longPress = rememberSelectLongPress()
    val longClick = action.onLongClick?.let {
        {
            origin.mark()
            it()
        }
    }
    Surface(
        onClick = {
            if (!longPress.consumeClick()) {
                // Fires on the select KeyUp, so `Play` gaining focus here never sees a phantom click.
                if (action.focusFirstOnClick) focusFirst() else origin.mark()
                action.onClick()
            }
        },
        modifier = modifier
            .focusRequester(origin.requester)
            .selectLongPress(longPress, longClick)
            .fillMaxWidth()
            .height(ButtonHeight),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = focusPillColors(),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp).height(ButtonHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(action.icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(action.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val StackWidth = 360.dp
private val ButtonHeight = 40.dp
