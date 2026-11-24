package com.seastreams.tv.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.seastreams.tv.player.FallbackReason
import com.seastreams.tv.player.StreamFailureMessages
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * *Automatic Stream Failover* is off and stream [index] failed (PRD §9): playback stays stopped on it
 * (ExoPlayer is idle after the error, so nothing decodes or advances) and the Streams overlay opens
 * so the user picks the next stream by hand. The status pill names the brief [reason] when one is known. It opens
 * from a player event, never a key press, so no trailing `KeyUp` can click inside it.
 */
internal fun PlayerViewModel.onStreamStopped(index: Int, reason: String? = null) {
    val total = _state.value.streams.size
    _state.update { it.copy(streamFailed = true, status = streamFailedMessage(index, total, reason)) }
    openPanel(PlayerPanel.STREAMS)
}

/** Status pill text for a stream that failed with failover off. */
internal fun streamFailedMessage(index: Int, total: Int, reason: String? = null): String = StreamFailureMessages.stopped(index, total, reason)

/**
 * Failover on: stream [from] timed out or failed ([detail]: its brief reason). The status says why and which stream
 * comes next; the single `status` value is replaced, never stacked, however fast the failures come.
 */
internal fun PlayerViewModel.onFallbackAdvance(from: Int, to: Int, reason: FallbackReason, detail: String?) {
    val manager = currentManager() ?: return
    val total = _state.value.streams.size
    _state.update { it.copy(status = StreamFailureMessages.advancing(from, to, total, reason, detail)) }
    playIndex(manager, to, maxOf(manager.player.currentPosition, 0L))
}

/** No stream is left: toast [message] (it names the last failure reason) and leave the player. */
internal fun PlayerViewModel.exitWithMessage(message: String) {
    viewModelScope.launch {
        _messages.send(message)
        exit()
    }
}
