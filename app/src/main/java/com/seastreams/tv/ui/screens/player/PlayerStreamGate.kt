package com.seastreams.tv.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.usenet.PreflightResult
import com.seastreams.tv.player.usenet.UsenetPreflight
import com.seastreams.tv.ui.screens.playback.NONE_WOULD_PLAY
import com.seastreams.tv.ui.screens.playback.skipStatus
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The open player's stream gate (PRD §9, §12 Playback): a Usenet release reached by a fallback advance, a Streams
 * overlay pick or an episode switch is opened by [UsenetPreflight] *before* ExoPlayer is given it. Refused releases
 * never reach the player, so there is no playback error, re-init or audio-focus change for them:
 * - failover on → the next stream is tried at once ([onSkip]), and [onExhausted] when none is left;
 * - failover off → [onStopped] (the Streams overlay opens, as for any failed stream).
 * Other streams go straight to [onPlay]; the fallback timer judges them as usual.
 */
class PlayerStreamGate @Inject constructor(private val preflight: UsenetPreflight) {
    private var job: Job? = null

    fun open(
        scope: CoroutineScope,
        streams: List<StreamSource>,
        index: Int,
        failover: Boolean,
        onPlay: (Int) -> Unit,
        onSkip: (from: Int, to: Int, total: Int) -> Unit,
        onStopped: (Int) -> Unit,
        onExhausted: () -> Unit,
    ) {
        cancel()
        job = scope.launch {
            var current = index
            while (current < streams.size) {
                val stream = streams[current]
                val result = if (UsenetStreamRegistry.isUsenet(stream.url)) preflight.check(stream) else PreflightResult.Playable
                when {
                    result == PreflightResult.Playable -> return@launch onPlay(current)
                    !failover -> return@launch onStopped(current)
                    current + 1 >= streams.size -> return@launch onExhausted()
                    else -> {
                        onSkip(current, current + 1, streams.size)
                        current++
                    }
                }
            }
        }
    }

    /** The session ended or another stream was asked for: a release still opening is never handed over. */
    fun cancel() {
        job?.cancel()
        job = null
    }
}

/** Gives stream [index] to ExoPlayer and starts its fallback timer. */
internal fun PlayerViewModel.handOff(manager: SeaStreamsPlayerManager, index: Int, positionMs: Long) {
    val streams = _state.value.streams
    manager.playStream(streams.getOrNull(index) ?: return, positionMs)
    fallback.onStreamStarted(index, streams.size)
}

/** [PlayerViewModel.playIndex] for a Usenet release (opened first, so a refused one never reaches ExoPlayer): through the gate, then [handOff] to ExoPlayer. */
internal fun PlayerViewModel.openThroughGate(manager: SeaStreamsPlayerManager, index: Int, positionMs: Long) {
    // The previous stream's timer or a late error must not advance while the release opens.
    fallback.cancel()
    val streams = _state.value.streams
    streamGate.open(
        scope = viewModelScope,
        streams = streams,
        index = index,
        failover = settings.player.autoStreamFailover,
        onPlay = { playable -> handOff(manager, playable, positionMs) },
        onSkip = { from, to, total -> _state.update { it.onNewStream(to).copy(status = skipStatus(from, to, total)) } },
        onStopped = { failed ->
            manager.player.stop() // a hot-swap from a playing stream stops there, like any failed stream
            onStreamStopped(failed)
        },
        onExhausted = {
            viewModelScope.launch {
                _messages.send(NONE_WOULD_PLAY)
                exit()
            }
        },
    )
}
