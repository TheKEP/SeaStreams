package com.seastreams.tv.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.StreamPreflight
import com.seastreams.tv.player.usenet.PreflightResult
import com.seastreams.tv.ui.screens.playback.NONE_WOULD_PLAY
import com.seastreams.tv.ui.screens.playback.StartPick
import com.seastreams.tv.ui.screens.playback.firstPlayable
import com.seastreams.tv.ui.screens.playback.skipStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The open player's stream gate (PRD §9, §12, §14 Playback): a Usenet release or Debrid library file reached by a
 * fallback advance, a Streams overlay pick or an episode switch is checked by [StreamPreflight] *before* ExoPlayer is
 * given it (release opened / fresh link resolved). Refused streams never reach the player, so there is no playback
 * error, re-init or audio-focus change for them:
 * - failover on → the next stream is tried at once ([onSkip]), and [onExhausted] when none is left;
 * - failover off → [onStopped] (the Streams overlay opens, as for any failed stream).
 * Other streams go straight to [onPlay]; the fallback timer judges them as usual.
 */
class PlayerStreamGate @Inject constructor(private val preflight: StreamPreflight) {

    /** Whether [url] must go through the gate (Usenet / Debrid) rather than straight to ExoPlayer. */
    fun handles(url: String): Boolean = preflight.handles(url)

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
        val first = streams.getOrNull(index) ?: return
        job = scope.launch {
            if (!failover) {
                val refused = preflight.handles(first.url) && preflight.check(first) is PreflightResult.Skip
                return@launch if (refused) onStopped(index) else onPlay(index)
            }
            when (val pick = firstPlayable(streams, index, failover = true, preflight, onSkip)) {
                is StartPick.Play -> onPlay(pick.index)
                is StartPick.NonePlayable -> onExhausted()
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

/**
 * [PlayerViewModel.playIndex] for a Usenet release or Debrid file (checked first, so a refused one never reaches
 * ExoPlayer): through the gate, then [handOff] to ExoPlayer.
 */
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
