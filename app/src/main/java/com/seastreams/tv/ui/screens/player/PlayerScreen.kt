package com.seastreams.tv.ui.screens.player

import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
import android.view.KeyEvent.KEYCODE_MEDIA_NEXT
import android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_REWIND
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.ui.components.LocalTransientMessages
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.components.SubtitleCueText
import com.seastreams.tv.ui.components.TransientMessages
import com.seastreams.tv.ui.components.hdrUiDim
import com.seastreams.tv.ui.theme.SelectKeys
import com.seastreams.tv.ui.screens.playback.RESTORE_FRAMES
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import kotlinx.coroutines.delay

/** Where focus lands when the HUD opens. */
enum class HudFocus { PLAY, SEEK }

/**
 * Full-screen built-in player (PRD §9). With the HUD hidden: `Enter` pauses and opens the HUD,
 * `Up`/`Down` open it while playback continues, and `Left`/`Right` seek 10 s. Each `Back` press does
 * one thing: close an open sub-overlay (focus returns to its bar button), else hide the HUD (playback
 * continues), else leave playback. The HUD hides after [HUD_TIMEOUT_MS] without a key press while playing.
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
    ResetHdrWindowOnExit()
    DimToastsForHdr(messages, state.dimUi)
    PauseWhenBackgrounded(viewModel)
    // Fallback only: with Compose focus anywhere in the player, the root preview pass below handles Back.
    BackHandler { viewModel.back() }

    val root = remember { FocusRequester() }
    var hudFocus by remember { mutableStateOf(HudFocus.PLAY) }
    var keyPresses by remember { mutableIntStateOf(0) }
    val selectGate = remember { PlayerSelectKeyGate() }
    val backGate = remember { PlayerBackKeyGate() }
    // The hidden-HUD state must keep a focused root, or the next Back / Enter would have nowhere to land.
    LaunchedEffect(state.hudVisible) { if (!state.hudVisible) retryEachFrame(RESTORE_FRAMES) { root.tryRequestFocus() } }
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
                // Consume every Back event so Compose never moves focus "out" to this root (a dead,
                // unfocused-looking state) and the release never leaks to the screen underneath.
                if (event.key in PlayerBackKeys) {
                    if (backGate.onBackKey(event.type == KeyEventType.KeyDown, event.nativeKeyEvent.repeatCount)) viewModel.back()
                    return@onPreviewKeyEvent true
                }
                // Runs before whichever HUD control now has focus: swallow the HUD-opening press.
                if (event.key in SelectKeys &&
                    selectGate.consumeHeldPress(event.type == KeyEventType.KeyDown, event.nativeKeyEvent.repeatCount)
                ) {
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                keyPresses++
                handleMediaKey(event.key.nativeKeyCode, viewModel, state)
            }
            .focusRequester(root)
            .onKeyEvent { event ->
                // Only reached while the HUD is hidden: the root itself holds focus then.
                if (event.type != KeyEventType.KeyDown || state.hudVisible) return@onKeyEvent false
                // Initial loading screen: no HUD, pause or seek on a stream with no frame yet; Back still leaves.
                if (state.initialLoading) return@onKeyEvent event.key in SelectKeys || event.key.nativeKeyCode in DpadKeyCodes
                if (event.key in SelectKeys) {
                    hudFocus = HudFocus.PLAY
                    when (selectGate.onHiddenHudSelectDown(event.nativeKeyEvent.repeatCount, state.playing)) {
                        HiddenHudSelect.PAUSE_AND_SHOW -> viewModel.pauseAndShowHud()
                        HiddenHudSelect.SHOW -> openHud(HudFocus.PLAY)
                        null -> Unit
                    }
                    return@onKeyEvent true
                }
                when (event.key.nativeKeyCode) {
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
        // Exiting: the SurfaceView (and its last HDR frame) goes now, not when the screen is removed (PRD §10).
        if (current != null && !state.videoDetached) {
            val player = current.player
            val aspect by current.videoAspect.collectAsState()
            key(player) { VideoSurface(player, aspect) }
        }
        val containerHeight = maxHeight
        // Everything drawn over the video; HDR White Dimming dims this layer only, never the picture.
        Box(Modifier.fillMaxSize().hdrUiDim(state.dimUi)) {
            val visual = loadingVisual(request is PlaybackRequest.Trailer, state.started, state.buffering)
            // The session's current request: episode switches and auto-next replace the opening one.
            ((state.request ?: request) as? PlaybackRequest.Media)?.let { media -> PlayerLoadingOverlay(media.artwork, visual) }
            cue?.let { text ->
                val bottom = if (state.hudVisible) SUBTITLE_RAISED else SUBTITLE_BOTTOM
                SubtitleCueText(
                    text = text,
                    style = state.subtitleStyle,
                    containerHeight = containerHeight,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom, start = CUE_SIDE_PADDING, end = cueEndPadding(state.panel)),
                )
            }
            CenterIndicator(state, isTrailer = request is PlaybackRequest.Trailer, visual, Modifier.align(Alignment.Center))
            state.status?.let { StatusPill(it, Modifier.align(Alignment.TopCenter).padding(top = 28.dp)) }
            if (state.hudVisible) {
                PlayerOverlayControls(state = state, viewModel = viewModel, initialFocus = hudFocus)
            }
            state.panel?.let { panel -> PlayerPanels(panel, state, viewModel, Modifier.align(Alignment.CenterEnd)) }
        }
    }
}

/** Toasts are drawn by the shell above the player; they follow the player's HDR White Dimming too. */
@Composable
private fun DimToastsForHdr(messages: TransientMessages?, dim: Boolean) {
    LaunchedEffect(messages, dim) { messages?.hdrDim = dim }
    DisposableEffect(messages) { onDispose { messages?.hdrDim = false } }
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

/**
 * A trailer's loading ring while buffering, and a pause badge while paused (`player-settings.jpg`).
 * Movies/episodes show [PlayerLoadingOverlay] instead of the ring, and no badge while it is up.
 */
@Composable
private fun CenterIndicator(state: PlayerUiState, isTrailer: Boolean, visual: LoadingVisual, modifier: Modifier) {
    when {
        isTrailer && state.buffering -> BufferingRing(modifier.size(64.dp))
        visual != LoadingVisual.NONE -> Unit
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

private val DpadKeyCodes = setOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT)

const val SEEK_STEP_MS = 10_000L
private const val FAST_FORWARD_MS = 30_000L
private const val HUD_TIMEOUT_MS = 5_000L
private val SUBTITLE_BOTTOM = 48.dp
private val SUBTITLE_RAISED = 170.dp
