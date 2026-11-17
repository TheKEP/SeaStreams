package com.seastreams.tv.domain.playback

import com.seastreams.tv.domain.model.PlaybackRequest

/** How a built-in playback stopped. */
enum class PlayerStop {
    /** ExoPlayer reached `STATE_ENDED`. */
    ENDED,

    /** The user pressed `Back` with the HUD hidden. */
    USER_EXIT,
}

/**
 * Auto-Play Next Episode (PRD §9): only TV episodes advance, only when the setting is on, and only
 * when the episode counts as finished (`>= 90%`, `STATE_ENDED`, or an external player's
 * `end_by == "playback_completion"`). Trailers never advance; leaving early never does either.
 */
object AutoNextPolicy {

    fun shouldPlayNext(isEpisode: Boolean, completed: Boolean, autoPlayNextEpisode: Boolean): Boolean =
        isEpisode && completed && autoPlayNextEpisode

    /** Built-in player: [positionMs]/[durationMs] at the moment playback stopped. */
    fun afterBuiltInStop(
        request: PlaybackRequest,
        stop: PlayerStop,
        positionMs: Long,
        durationMs: Long,
        autoPlayNextEpisode: Boolean,
    ): Boolean {
        val media = request as? PlaybackRequest.Media ?: return false
        val completed = stop == PlayerStop.ENDED || isCompleted(positionMs, durationMs)
        return shouldPlayNext(media.target.episode != null, completed, autoPlayNextEpisode)
    }

    /** External player: the [PlaybackOutcome] parsed from its result extras. */
    fun afterExternalReturn(target: PlaybackTarget, outcome: PlaybackOutcome, autoPlayNextEpisode: Boolean): Boolean =
        shouldPlayNext(target.episode != null, outcome.completed, autoPlayNextEpisode)
}
