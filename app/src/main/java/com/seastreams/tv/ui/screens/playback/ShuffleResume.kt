package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ShuffleMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The shuffled episode that was playing when playback last closed (built-in player exit or external player return),
 * in memory only: a shuffle start under 10 s saves no watch progress, yet Details' primary button must still continue
 * that shuffle rather than fall back to the episode after the last finished one (PRD §6). Never written to Room.
 */
class ShuffleResume {

    private val _target = MutableStateFlow<PlaybackTarget?>(null)
    val target: StateFlow<PlaybackTarget?> = _target.asStateFlow()

    /**
     * Playback of [last] closed. A shuffled episode becomes the one to continue; a sequential episode of the same
     * show (an Episodes overlay pick, an episode row, Continue Watching) ends that show's shuffle.
     */
    fun remember(last: PlaybackTarget) = _target.update { current ->
        when {
            last.episode != null && last.shuffle.mode != ShuffleMode.None -> last
            current != null && current.isFor(last.profileId, last.details.ref) -> null
            else -> current
        }
    }

    /** The show's Details page closed: its shuffle is over. */
    fun forget(profileId: Long, ref: MediaRef) = _target.update { current -> current?.takeUnless { it.isFor(profileId, ref) } }

    private fun PlaybackTarget.isFor(profileId: Long, ref: MediaRef): Boolean = this.profileId == profileId && details.ref == ref
}
