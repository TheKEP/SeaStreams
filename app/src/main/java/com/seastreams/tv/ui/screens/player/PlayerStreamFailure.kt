package com.seastreams.tv.ui.screens.player

import kotlinx.coroutines.flow.update

/**
 * *Automatic Stream Failover* is off and stream [index] failed (PRD §9): playback stays stopped on it
 * (ExoPlayer is idle after the error, so nothing decodes or advances) and the Streams overlay opens
 * so the user picks the next stream by hand. It opens from a player event, never a key press, so no
 * trailing `KeyUp` can click inside it.
 */
internal fun PlayerViewModel.onStreamStopped(index: Int) {
    val total = _state.value.streams.size
    _state.update { it.copy(streamFailed = true, status = streamFailedMessage(index, total)) }
    openPanel(PlayerPanel.STREAMS)
}

/** Status pill text for a stream that failed with failover off. */
internal fun streamFailedMessage(index: Int, total: Int): String {
    val which = if (total > 1) "Stream ${index + 1} of $total" else "This stream"
    return "$which couldn't play. Automatic failover is off — pick another stream."
}
