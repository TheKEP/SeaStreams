package com.seastreams.tv.ui.screens.playback

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.player.external.ExternalPlayerIntents
import com.seastreams.tv.ui.components.FocusOriginTracker
import com.seastreams.tv.ui.components.TransientMessages
import com.seastreams.tv.ui.components.restoreFocus
import com.seastreams.tv.ui.navigation.activityViewModel
import com.seastreams.tv.ui.screens.player.PlayerScreen
import com.seastreams.tv.ui.theme.SurfaceRaised
import com.seastreams.tv.ui.theme.awaitNextFrame
import com.seastreams.tv.ui.theme.tryRequestFocus

/**
 * Hosts playback for a profile's screens: launches external players through
 * `ActivityResultContracts.StartActivityForResult` (so their result extras come back to
 * [PlaybackViewModel.onExternalResult]), shows the stream list and the built-in player, and returns
 * focus to the button that opened them (e.g. `Play` or `Trailer`) once both are closed.
 */
@Composable
fun PlaybackHost(viewModel: PlaybackViewModel, messages: TransientMessages, origins: FocusOriginTracker) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onExternalResult(ExternalPlayerIntents.resultExtras(result.data))
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PlaybackEvent.Message -> messages.show(event.text)
                is PlaybackEvent.OpenYouTube -> try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_WATCH + event.youtubeKey)))
                } catch (e: ActivityNotFoundException) {
                    messages.show("No app on this TV can play trailers")
                }
                is PlaybackEvent.Launch -> {
                    val intent = ExternalPlayerIntents.build(event.spec)
                    try {
                        if (intent == null) viewModel.onLaunchFailed() else launcher.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        viewModel.onLaunchFailed()
                    }
                }
            }
        }
    }

    val picker = state.picker
    val player = state.player
    // The picker can hand over straight to the player; focus returns once neither is open.
    val layerOpen = picker != null || player != null
    var origin by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(layerOpen) {
        if (layerOpen) {
            origin = origins.take() ?: origin
        } else {
            // The exact opener first; Details' primary button if there was no mark or it is gone.
            val targets = listOfNotNull(origin, origins.fallback()).distinct()
            restoreFocus(RESTORE_FRAMES, ::awaitNextFrame, targets.map { target -> { target.tryRequestFocus() } })
            origin = null
        }
    }
    if (player != null) {
        PlayerScreen(request = player, viewModel = activityViewModel(), onExit = viewModel::closePlayer)
    }
    if (picker != null) {
        StreamPickerModal(state = picker, onChoose = viewModel::choose, onDismiss = viewModel::dismissPicker)
    }
    state.busyLabel?.let { BusyBadge(it) }
}

@Composable
private fun BusyBadge(label: String) {
    Box(Modifier.fillMaxSize().padding(top = 28.dp, end = 48.dp), contentAlignment = Alignment.TopEnd) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(SurfaceRaised, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

private const val YOUTUBE_WATCH = "https://www.youtube.com/watch?v="

/** Frames to keep retrying a focus restore while the uncovered layer re-attaches. */
const val RESTORE_FRAMES = 10
