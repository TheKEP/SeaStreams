package com.seastreams.tv.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.StreamFailureMessages
import com.seastreams.tv.player.StreamFailureReason
import com.seastreams.tv.player.StreamPreflight
import com.seastreams.tv.player.usenet.PreflightResult
import com.seastreams.tv.ui.screens.playback.StartPick
import com.seastreams.tv.ui.screens.playback.firstPlayable
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
 * - failover on → the next stream is tried at once ([onSkip], with the brief reason), and [onExhausted] (the full
 *   message, naming the last reason) when none is left;
 * - failover off → [onStopped] with the brief reason (the Streams overlay opens, as for any failed stream).
 * One job at a time: a new [open] or [cancel] ends a run still checking, so a burst of refusals (a whole indexer of
 * passworded 7z sets refused in milliseconds) reports each skip once, in order, and nothing after it is cancelled.
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
        onSkip: (from: Int, to: Int, total: Int, reason: String) -> Unit,
        onStopped: (index: Int, reason: String) -> Unit,
        onExhausted: (message: String) -> Unit,
    ) {
        cancel()
        val first = streams.getOrNull(index) ?: return
        job = scope.launch {
            if (!failover) {
                val result = if (preflight.handles(first.url)) preflight.check(first) else PreflightResult.Playable
                return@launch if (result is PreflightResult.Skip) onStopped(index, StreamFailureReason.fromPreflight(result.reason)) else onPlay(index)
            }
            when (val pick = firstPlayable(streams, index, failover = true, preflight, onSkip)) {
                is StartPick.Play -> onPlay(pick.index)
                is StartPick.NonePlayable -> onExhausted(pick.message)
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
        onSkip = { from, to, total, reason ->
            _state.update { it.onNewStream(to).copy(status = StreamFailureMessages.skipped(from, to, total, reason)) }
        },
        onStopped = { failed, reason ->
            manager.player.stop() // a hot-swap from a playing stream stops there, like any failed stream
            onStreamStopped(failed, reason)
        },
        onExhausted = { message -> exitWithMessage(message) },
    )
}
