package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.playback.AutoNextPolicy
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.knownOrZero

/**
 * Which request's stream ExoPlayer actually holds, so its position is never saved under another title
 * (PRD §9). Between an episode switch (`startMedia`) and the hand-off of the new stream — stream lookup,
 * then a Usenet/Debrid preflight that can take seconds — the player still reports the previous episode's
 * position and duration. Saving those under the new episode would mark it watched the moment it starts.
 */
class PlayerProgressBinding {

    private var bound: PlaybackRequest? = null

    /** [request]'s stream was handed to ExoPlayer: its position is now [request]'s. */
    fun bind(request: PlaybackRequest?) {
        bound = request
    }

    /** A new movie/episode is starting (or the session ended): nothing is savable until its stream is handed over. */
    fun unbind() {
        bound = null
    }

    /** [current] when the player's position belongs to it, else `null` (write nothing). */
    fun savable(current: PlaybackRequest?): PlaybackRequest? = current?.takeIf { it === bound }
}

/** What a built-in `STATE_ENDED` does. */
enum class EndedAction {
    /** Save as watched, then Auto-Play Next Episode (or leave when it doesn't apply). */
    FINISH,

    /** Save as watched only: an episode switch is already under way and must not be replaced by a second one. */
    SAVE_ONLY,

    /** A stale event (a previous stream, or a source not yet handed over): do nothing. */
    IGNORE,
}

/**
 * The `STATE_ENDED` rules: an event whose [eventGeneration] isn't the manager's current [managerGeneration]
 * came from a stream already replaced (or being replaced, see `SeaStreamsPlayerManager.beginSource`), and
 * [bound] is false while the playing request's stream hasn't been handed over yet; both are ignored.
 */
fun endedAction(bound: Boolean, eventGeneration: Int, managerGeneration: Int, switchInFlight: Boolean): EndedAction = when {
    eventGeneration != managerGeneration || !bound -> EndedAction.IGNORE
    switchInFlight -> EndedAction.SAVE_ONLY
    else -> EndedAction.FINISH
}

/**
 * `STATE_ENDED`: the only built-in trigger of Auto-Play Next Episode (PRD §9), through the same
 * [PlayerSwitching.playNextEpisode] as the HUD button, so the shuffle session carries on identically.
 */
internal suspend fun PlayerViewModel.onEnded(manager: SeaStreamsPlayerManager, generation: Int) {
    val request = progressBinding.savable(_state.value.request)
    val action = endedAction(request != null, generation, manager.generation, switching.inFlight)
    if (request == null || action == EndedAction.IGNORE) return
    progressTracker.saveFinished(request, manager.player.duration.knownOrZero())
    if (action == EndedAction.SAVE_ONLY) return
    if (AutoNextPolicy.afterBuiltInEnd(request, settings.player.autoPlayNextEpisode)) switching.playNextEpisode(this, auto = true) else exit()
}
