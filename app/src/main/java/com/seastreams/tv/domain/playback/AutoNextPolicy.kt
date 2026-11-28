package com.seastreams.tv.domain.playback

import com.seastreams.tv.domain.model.PlaybackRequest

/**
 * Auto-Play Next Episode (PRD §9): only TV episodes advance, only when the setting is on, and only
 * when playback **ended on its own** — built-in `STATE_ENDED`, or an external player's natural end
 * ([PlaybackOutcome.endedNaturally]). `Back` in any player always just leaves, whatever the position:
 * at `>= 90%` it is saved as watched, but it never advances. Trailers and movies never advance. The built-in
 * player's credits countdown ([afterCreditsCountdown], PRD §9 *Skip & Next Episode Prompts*) is the only trigger
 * before the end, and it follows the same setting.
 */
object AutoNextPolicy {

    fun shouldPlayNext(isEpisode: Boolean, endedNaturally: Boolean, autoPlayNextEpisode: Boolean): Boolean =
        isEpisode && endedNaturally && autoPlayNextEpisode

    /** Built-in player: ExoPlayer reached `STATE_ENDED` for [request]. */
    fun afterBuiltInEnd(request: PlaybackRequest, autoPlayNextEpisode: Boolean): Boolean {
        val media = request as? PlaybackRequest.Media ?: return false
        return shouldPlayNext(media.target.episode != null, endedNaturally = true, autoPlayNextEpisode)
    }

    /** Built-in player: the Next episode prompt's countdown ran out during [request]'s credits. */
    fun afterCreditsCountdown(request: PlaybackRequest?, autoPlayNextEpisode: Boolean): Boolean {
        val media = request as? PlaybackRequest.Media ?: return false
        return shouldPlayNext(media.target.episode != null, endedNaturally = true, autoPlayNextEpisode)
    }

    /** External player: the [PlaybackOutcome] parsed from its result extras. */
    fun afterExternalReturn(target: PlaybackTarget, outcome: PlaybackOutcome, autoPlayNextEpisode: Boolean): Boolean =
        shouldPlayNext(target.episode != null, outcome.endedNaturally, autoPlayNextEpisode)
}
