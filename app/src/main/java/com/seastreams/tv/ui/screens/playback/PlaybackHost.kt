package com.seastreams.tv.ui.screens.playback

import android.content.ActivityNotFoundException
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.player.external.ExternalPlayerIntents
import com.seastreams.tv.ui.components.FocusOriginTracker
import com.seastreams.tv.ui.components.TransientMessages
import com.seastreams.tv.ui.theme.SurfaceRaised
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus

/**
 * Hosts playback for a profile's screens: launches external players through
 * `ActivityResultContracts.StartActivityForResult` (so their result extras come back to
 * [PlaybackViewModel.onExternalResult]), shows the stream list, and returns focus to the button that
 * opened it when it closes.
 */
@Composable
fun PlaybackHost(viewModel: PlaybackViewModel, messages: TransientMessages, origins: FocusOriginTracker) {
    val state by viewModel.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onExternalResult(ExternalPlayerIntents.resultExtras(result.data))
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PlaybackEvent.Message -> messages.show(event.text)
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
    var pickerOrigin by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(picker != null) {
        if (picker != null) {
            pickerOrigin = origins.take()
        } else {
            pickerOrigin?.let { origin -> retryEachFrame(RESTORE_FRAMES) { origin.tryRequestFocus() } }
            pickerOrigin = null
        }
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

/** Frames to keep retrying a focus restore while the uncovered layer re-attaches. */
const val RESTORE_FRAMES = 10
