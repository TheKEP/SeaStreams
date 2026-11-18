package com.seastreams.tv.ui.screens.player

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
import android.view.KeyEvent.KEYCODE_MEDIA_NEXT
import android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_REWIND
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.ui.components.LocalTransientMessages
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.components.SubtitleCueText
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.delay

/** Where focus lands when the HUD opens. */
enum class HudFocus { PLAY, SEEK }

/**
 * Full-screen built-in player (PRD §9). With the HUD hidden: `Enter` pauses and opens the HUD,
 * `Up`/`Down` open it while playback continues, `Left`/`Right` seek 10 s, and `Back` leaves playback.
 * With the HUD (or an overlay) open, `Back` only closes the top overlay / the HUD. The HUD hides after
 * [HUD_TIMEOUT_MS] without a key press while playing.
 */
@Composable
fun PlayerScreen(request: PlaybackRequest, viewModel: PlayerViewModel, onExit: () -> Unit) {
    LaunchedEffect(request.sessionId) { viewModel.open(request) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val state by viewModel.state.collectAsState()
    val manager by viewModel.manager.collectAsState()
    val cue by viewModel.cue.collectAsState()
    val messages = LocalTransientMessages.current
    LaunchedEffect(viewModel) { viewModel.exits.collect { onExit() } }
    LaunchedEffect(viewModel) { viewModel.messages.collect { messages?.show(it) } }
    KeepScreenOn()
    PauseWhenBackgrounded(viewModel)
    BackHandler { viewModel.back() }

    val root = remember { FocusRequester() }
    var hudFocus by remember { mutableStateOf(HudFocus.PLAY) }
    var keyPresses by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.hudVisible) { if (!state.hudVisible) root.requestFocusAfterFrame() }
    LaunchedEffect(state.hudVisible, state.panel, state.playing, keyPresses) {
        if (state.hudVisible && state.panel == null && state.playing) {
            delay(HUD_TIMEOUT_MS)
            viewModel.hideHud()
        }
    }
    val openHud: (HudFocus) -> Unit = { focus ->
        hudFocus = focus
        viewModel.showHud()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                keyPresses++
                handleMediaKey(event.key.nativeKeyCode, viewModel, state)
            }
            .focusRequester(root)
            .onKeyEvent { event ->
                // Only reached while the HUD is hidden: the root itself holds focus then.
                if (event.type != KeyEventType.KeyDown || state.hudVisible) return@onKeyEvent false
                when (event.key.nativeKeyCode) {
                    KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER -> {
                        if (state.playing) viewModel.pauseAndShowHud() else openHud(HudFocus.PLAY)
                        hudFocus = HudFocus.PLAY
                        true
                    }
                    KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN -> {
                        openHud(HudFocus.PLAY)
                        true
                    }
                    KEYCODE_DPAD_LEFT -> {
                        viewModel.seekBy(-SEEK_STEP_MS)
                        openHud(HudFocus.SEEK)
                        true
                    }
                    KEYCODE_DPAD_RIGHT -> {
                        viewModel.seekBy(SEEK_STEP_MS)
                        openHud(HudFocus.SEEK)
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val current = manager
        if (current != null) {
            val player = current.player
            val aspect by current.videoAspect.collectAsState()
            key(player) { VideoSurface(player, aspect) }
            if (state.hdrWhiteDimming && player.isHdr()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = HDR_DIM_ALPHA)))
            }
        }
        cue?.let { text ->
            val bottom = if (state.hudVisible) SUBTITLE_RAISED else SUBTITLE_BOTTOM
            SubtitleCueText(
                text = text,
                style = state.subtitleStyle,
                containerHeight = maxHeight,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom, start = 96.dp, end = 96.dp),
            )
        }
        CenterIndicator(state, Modifier.align(Alignment.Center))
        state.status?.let { StatusPill(it, Modifier.align(Alignment.TopCenter).padding(top = 28.dp)) }
        if (state.hudVisible) {
            PlayerOverlayControls(state = state, viewModel = viewModel, initialFocus = hudFocus)
        }
        state.panel?.let { panel -> PlayerPanels(panel, state, viewModel, Modifier.align(Alignment.CenterEnd)) }
    }
}

/** Remote media keys work whether or not the HUD is showing. */
private fun handleMediaKey(code: Int, viewModel: PlayerViewModel, state: PlayerUiState): Boolean = when (code) {
    KEYCODE_MEDIA_PLAY_PAUSE -> {
        viewModel.togglePlayPause()
        true
    }
    KEYCODE_MEDIA_PLAY -> {
        if (!state.playing) viewModel.togglePlayPause()
        true
    }
    KEYCODE_MEDIA_PAUSE -> {
        if (state.playing) viewModel.togglePlayPause()
        true
    }
    KEYCODE_MEDIA_FAST_FORWARD -> {
        viewModel.seekBy(FAST_FORWARD_MS)
        true
    }
    KEYCODE_MEDIA_REWIND -> {
        viewModel.seekBy(-SEEK_STEP_MS)
        true
    }
    KEYCODE_MEDIA_NEXT -> {
        if (state.isEpisode) viewModel.nextEpisode()
        state.isEpisode
    }
    else -> false
}

@Composable
private fun VideoSurface(player: ExoPlayer, aspect: Float) {
    var view by remember { mutableStateOf<SurfaceView?>(null) }
    DisposableEffect(player, view) {
        val surface = view
        if (surface != null) player.setVideoSurfaceView(surface)
        onDispose { if (surface != null) player.clearVideoSurfaceView(surface) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { context -> SurfaceView(context).also { view = it } }, modifier = Modifier.aspectRatio(aspect))
    }
}

/** Loading ring while buffering, a pause badge while paused (`player-settings.jpg`). */
@Composable
private fun CenterIndicator(state: PlayerUiState, modifier: Modifier) {
    when {
        state.buffering -> BufferingRing(modifier.size(64.dp))
        !state.playing -> Box(
            modifier.size(72.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SeaIcons.Pause, contentDescription = "Paused", tint = Color.White, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun PauseWhenBackgrounded(viewModel: PlayerViewModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHostPaused() }
}

private fun ExoPlayer.isHdr(): Boolean {
    val transfer = videoFormat?.colorInfo?.colorTransfer
    return transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
}

const val SEEK_STEP_MS = 10_000L
private const val FAST_FORWARD_MS = 30_000L
private const val HUD_TIMEOUT_MS = 5_000L
private const val HDR_DIM_ALPHA = 0.18f
private val SUBTITLE_BOTTOM = 48.dp
private val SUBTITLE_RAISED = 170.dp
