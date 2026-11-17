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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
import kotlinx.coroutines.launch

/** One entry of the Details action stack. [onLongClick] is only set on `Play` (PRD §6). */
data class DetailsAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

/** Builds the stack for [state]: `Play`, `Trailer`, show-only `More episodes` + `Shuffle`, then the library toggle. */
fun detailsActions(
    state: DetailsUiState,
    onPlay: (forcePicker: Boolean) -> Unit,
    onTrailer: () -> Unit,
    onMoreEpisodes: () -> Unit,
    onShuffle: () -> Unit,
    onToggleLibrary: () -> Unit,
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
    }
}

/**
 * Vertical button stack (`movie-page.jpg`): plain icon + label rows that turn into a light pill while
 * focused. Every button records itself as the focus origin when pressed, so closing what it opened
 * (stream list, Episodes) lands back on it. A fresh `Down` on the last button wraps to the first.
 */
@Composable
fun DetailsActionStack(actions: List<DetailsAction>, firstButton: FocusRequester, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier.width(StackWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEachIndexed { index, action ->
            key(action.key) {
                val origin = rememberFocusOrigin()
                DetailsActionButton(
                    action = action,
                    origin = origin,
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstButton) else Modifier)
                        .then(
                            if (index == actions.lastIndex) {
                                Modifier.onDpadEdgeWrap(Key.DirectionDown) { scope.launch { firstButton.requestFocus() } }
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
private fun DetailsActionButton(action: DetailsAction, origin: FocusOrigin, modifier: Modifier) {
    val longClick = action.onLongClick
    Surface(
        onClick = {
            origin.mark()
            action.onClick()
        },
        onLongClick = longClick?.let {
            {
                origin.mark()
                it()
            }
        },
        modifier = modifier.focusRequester(origin.requester).fillMaxWidth().height(ButtonHeight),
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
