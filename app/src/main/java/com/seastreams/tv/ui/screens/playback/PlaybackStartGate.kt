package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.player.StreamFailureMessages
import com.seastreams.tv.player.StreamFailureReason
import com.seastreams.tv.player.StreamPreflight
import com.seastreams.tv.player.usenet.PreflightResult

/** Which stream auto-play / a pick should hand to the player. */
sealed interface StartPick {
    data class Play(val index: Int) : StartPick

    /** Every stream from the requested one on was refused before reaching a player. */
    data class NonePlayable(val message: String) : StartPick
}

/**
 * Silent pre-player skip (PRD §9, §12, §14): with *Automatic stream failover* on, Usenet releases and Debrid library
 * files from [index] on are checked by [StreamPreflight] before any player exists (release opened / playback link
 * resolved), and refused ones are passed over, [onSkip] saying so with the brief reason
 * (`Stream 1 didn't start (7z archive not supported). Trying stream 2 of 5…`). When every one is refused the
 * [StartPick.NonePlayable] message names the last reason (one stream alone: its sanitized full reason).
 * Other streams are taken as they are: the player's own fallback timer judges them. With failover off nothing is
 * skipped here; the player's stream gate stops on a refused stream and opens the Streams overlay without ever
 * preparing ExoPlayer with it.
 */
internal suspend fun firstPlayable(
    streams: List<StreamSource>,
    index: Int,
    failover: Boolean,
    preflight: StreamPreflight,
    onSkip: (from: Int, to: Int, total: Int, reason: String) -> Unit,
): StartPick {
    if (!failover) return StartPick.Play(index)
    var current = index
    while (current < streams.size) {
        val stream = streams[current]
        if (!preflight.handles(stream.url)) return StartPick.Play(current)
        when (val result = preflight.check(stream)) {
            PreflightResult.Playable -> return StartPick.Play(current)
            is PreflightResult.Skip -> {
                val reason = StreamFailureReason.fromPreflight(result.reason)
                if (current + 1 >= streams.size) {
                    val single = StreamFailureReason.sanitize(result.reason).ifEmpty { reason }.replaceFirstChar(Char::uppercaseChar)
                    return StartPick.NonePlayable(if (streams.size == 1) single else StreamFailureMessages.noneWouldPlay(reason))
                }
                onSkip(current, current + 1, streams.size, reason)
                current++
            }
        }
    }
    return StartPick.NonePlayable(StreamFailureMessages.NONE_WOULD_PLAY)
}
