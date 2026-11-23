package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.domain.model.StreamSource
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
 * resolved), and refused ones are passed over, [onSkip] saying so (`Stream 1 didn't start. Trying stream 2 of 5…`).
 * Other streams are taken as they are: the player's own fallback timer judges them. With failover off nothing is
 * skipped here; the player's stream gate stops on a refused stream and opens the Streams overlay without ever
 * preparing ExoPlayer with it.
 */
internal suspend fun firstPlayable(
    streams: List<StreamSource>,
    index: Int,
    failover: Boolean,
    preflight: StreamPreflight,
    onSkip: (from: Int, to: Int, total: Int) -> Unit,
): StartPick {
    if (!failover) return StartPick.Play(index)
    var current = index
    while (current < streams.size) {
        val stream = streams[current]
        if (!preflight.handles(stream.url)) return StartPick.Play(current)
        when (val result = preflight.check(stream)) {
            PreflightResult.Playable -> return StartPick.Play(current)
            is PreflightResult.Skip -> {
                if (current + 1 >= streams.size) {
                    return StartPick.NonePlayable(if (streams.size == 1) result.reason else NONE_WOULD_PLAY)
                }
                onSkip(current, current + 1, streams.size)
                current++
            }
        }
    }
    return StartPick.NonePlayable(NONE_WOULD_PLAY)
}

/** The loading-screen status while a refused stream is passed over (same wording as the player's fallback). */
internal fun skipStatus(from: Int, to: Int, total: Int): String = "Stream ${from + 1} didn't start. Trying stream ${to + 1} of $total…"

internal const val NONE_WOULD_PLAY = "None of the streams would play"
