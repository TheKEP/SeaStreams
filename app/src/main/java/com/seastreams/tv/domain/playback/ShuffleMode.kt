package com.seastreams.tv.domain.playback

import com.seastreams.tv.domain.model.EpisodeRef

/** How `Next Episode` and Auto-Play Next Episode pick the episode after the current one (PRD §6, §9). */
sealed interface ShuffleMode {
    /** Sequential: the next episode in its season, then episode 1 of the following season. */
    data object None : ShuffleMode

    /** Details `Shuffle`: a random aired episode across every regular season of the show. */
    data object Show : ShuffleMode

    /** Episodes `Shuffle Season`: a random aired episode of season [number] only. */
    data class Season(val number: Int) : ShuffleMode
}

/**
 * The shuffle state of one playback session. [played] holds the episodes already played in this
 * shuffle, so later picks avoid repeats until every episode in the pool has played once.
 */
data class ShuffleSession(val mode: ShuffleMode = ShuffleMode.None, val played: List<EpisodeRef> = emptyList()) {
    companion object {
        /** Normal sequential next-episode behaviour. */
        val Sequential = ShuffleSession()

        /** A new shuffle of [mode] that starts on [first]. */
        fun start(mode: ShuffleMode, first: EpisodeRef): ShuffleSession =
            if (mode == ShuffleMode.None) Sequential else ShuffleSession(mode, listOf(first))
    }
}

/** The episode to play next and the shuffle session that continues with it. */
data class NextPick(val episode: EpisodeRef, val session: ShuffleSession)

/**
 * A manual episode pick (the in-player `Episodes` overlay): plays [ref] with its title/runtime
 * looked up again, and returns to sequential next-episode behaviour.
 */
fun PlaybackTarget.forEpisode(ref: EpisodeRef): PlaybackTarget =
    copy(episode = ref, episodeTitle = null, episodeRuntimeMinutes = null, shuffle = ShuffleSession.Sequential)
