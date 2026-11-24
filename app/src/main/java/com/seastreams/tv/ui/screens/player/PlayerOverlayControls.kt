package com.seastreams.tv.ui.screens.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import com.seastreams.tv.player.PlayerTracks
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.screens.playback.RESTORE_FRAMES
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Player HUD (`player-settings.jpg`): title top-left, `Ends at` and a format summary top-right,
 * the seek bar, and the bottom action bar ([PlayerActionBar]). Trailers get the simplified HUD: title,
 * play/pause and the seek bar only.
 */
@Composable
fun PlayerOverlayControls(state: PlayerUiState, viewModel: PlayerViewModel, initialFocus: HudFocus, onBottomBlockMeasured: (Dp) -> Unit = {}) {
    val density = LocalDensity.current
    val progress by viewModel.progress.collectAsState()
    val manager by viewModel.manager.collectAsState()
    val playButton = remember { FocusRequester() }
    val seekBar = remember { FocusRequester() }
    val panelButtons = remember { PlayerPanel.entries.associateWith { FocusRequester() } }
    var lastPanel by remember { mutableStateOf<PlayerPanel?>(null) }
    LaunchedEffect(Unit) { (if (initialFocus == HudFocus.SEEK) seekBar else playButton).requestFocusAfterFrame() }
    // Closing an overlay hands focus back to the bar button that opened it (Play/Pause if that pill
    // isn't shown), retrying while the bar re-attaches so focus never stays on nothing.
    LaunchedEffect(state.panel) {
        val closed = lastPanel
        lastPanel = state.panel
        if (state.panel != null || closed == null) return@LaunchedEffect
        val target = panelReturnFocus(closed, state)?.let { panelButtons[it] } ?: playButton
        if (!retryEachFrame(RESTORE_FRAMES) { target.tryRequestFocus() }) playButton.tryRequestFocus()
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.75f), 1f to Color.Transparent)))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))),
        )
        Column(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 32.dp).width(620.dp)) {
            Text(state.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = if (state.isTrailer) "Trailer" else state.subtitle
            line?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1) }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 32.dp), horizontalAlignment = Alignment.End) {
            // At 1.5x the rest of the video takes 1/1.5 as long in wall-clock time.
            val remaining = ((progress.durationMs - progress.positionMs).coerceAtLeast(0) / state.playbackSpeed.multiplier).toLong()
            if (progress.durationMs > 0) {
                Text("Ends at ${LocalTime.now().plusSeconds(remaining / MILLIS_PER_SECOND).format(CLOCK)}", style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            manager?.let { formatSummary(it) }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f)) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // The Subtitles, Audio, Speed and Info cards end just above this block's seek row (PlayerPanels.cardBottomInset).
                .onSizeChanged { onBottomBlockMeasured(with(density) { it.height.toDp() }) }
                .padding(horizontal = 48.dp, vertical = HUD_BOTTOM_PADDING),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SeekRow(progress, viewModel, seekBar)
            PlayerActionBar(state, viewModel, playButton, panelButtons)
        }
    }
}

@Composable
private fun SeekRow(progress: PlayerProgress, viewModel: PlayerViewModel, requester: FocusRequester) {
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    // Held/rapid presses move a scrub marker; the seek itself happens once the presses stop.
    LaunchedEffect(scrubMs) {
        val target = scrubMs ?: return@LaunchedEffect
        delay(SCRUB_COMMIT_MS)
        viewModel.seekTo(target)
        scrubMs = null
    }
    val shown = scrubMs ?: progress.positionMs
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(formatPlaybackTime(shown), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        var focused by remember { mutableStateOf(false) }
        Canvas(
            Modifier
                .weight(1f)
                .height(20.dp)
                .focusRequester(requester)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val direction = when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val target = scrubMs
                            if (target != null) {
                                viewModel.seekTo(target)
                                scrubMs = null
                            } else {
                                viewModel.togglePlayPause()
                            }
                            return@onKeyEvent true
                        }
                        else -> return@onKeyEvent false
                    }
                    val step = scrubStepMs(event.nativeKeyEvent.repeatCount)
                    val base = scrubMs ?: progress.positionMs
                    val max = progress.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                    scrubMs = (base + direction * step).coerceIn(0, max)
                    true
                }
                .focusable(),
        ) {
            drawSeekBar(seekBarFractions(shown, progress.bufferedMs, progress.durationMs), focused)
        }
        val remaining = (progress.durationMs - shown).coerceAtLeast(0)
        Text("-${formatPlaybackTime(remaining)} / ${formatPlaybackTime(progress.durationMs)}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

/** Seek step grows the longer a direction is held: 10 s, then 30 s, then 60 s. */
internal fun scrubStepMs(repeatCount: Int): Long = when {
    repeatCount >= FAST_REPEATS -> 60_000L
    repeatCount >= MEDIUM_REPEATS -> 30_000L
    else -> SEEK_STEP_MS
}

/** `1080p · E-AC-3 5.1 · 8.2 Mbps`. */
private fun formatSummary(manager: SeaStreamsPlayerManager): String? {
    val video = manager.player.videoFormat
    val audio = manager.player.audioFormat
    val parts = listOfNotNull(
        video?.height?.takeIf { it > 0 }?.let { "${it}p" },
        audio?.let { listOfNotNull(PlayerTracks.codecName(it.sampleMimeType), PlayerTracks.channelLayout(it.channelCount)).joinToString(" ") }?.ifBlank { null },
        "%.1f Mbps".format(Locale.US, manager.meter.megabitsPerSecond()),
    )
    return parts.joinToString(" · ").ifBlank { null }
}

/** Indeterminate spinner (tv-material has no progress indicator). */
@Composable
fun BufferingRing(modifier: Modifier) {
    val rotation by rememberInfiniteTransition(label = "buffering").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(RING_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "bufferingRotation",
    )
    Canvas(modifier) {
        drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, useCenter = false, style = Stroke(5.dp.toPx()))
        drawArc(Color.White, rotation, 90f, useCenter = false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private const val SCRUB_COMMIT_MS = 650L
private const val MEDIUM_REPEATS = 5
private const val FAST_REPEATS = 15
private const val RING_PERIOD_MS = 900
