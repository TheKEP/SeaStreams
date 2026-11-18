package com.seastreams.tv.domain.playback

import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.usecase.ResolveStreamsUseCase
import com.seastreams.tv.domain.usecase.StreamQuery
import com.seastreams.tv.domain.usecase.StreamResolution
import com.seastreams.tv.domain.usecase.topStream
import javax.inject.Inject
import kotlin.random.Random

/** A target with its streams resolved; [topIndex] is the auto-play stream's index, `-1` when there are none. */
data class ResolvedPlayback(val target: PlaybackTarget, val resolution: StreamResolution, val topIndex: Int) {
    val top: StreamSource? get() = resolution.streams.getOrNull(topIndex)
}

/** Result of looking for the episode after a finished one. */
sealed interface NextEpisode {
    data class Found(val playback: ResolvedPlayback) : NextEpisode

    /** The finished episode was the last aired one. */
    data object CaughtUp : NextEpisode
}

/**
 * Shared by the stream picker, the external-player return path and the built-in player: fills in an
 * episode's title/runtime, resolves its streams from the target profile's enabled addons, picks the
 * top stream (preferred auto-play addon first), and finds the next episode for Auto-Play Next Episode.
 */
class PlaybackStreamResolver @Inject constructor(
    private val resolveStreams: ResolveStreamsUseCase,
    private val titles: TitleDetailsRepository,
) {
    suspend fun resolve(target: PlaybackTarget, preferredAddonUrl: String?): ResolvedPlayback {
        val withInfo = withEpisodeInfo(target)
        val resolution = resolveStreams(StreamQuery(withInfo.profileId, withInfo.details.ref, withInfo.imdbId, withInfo.episode))
        val top = topStream(resolution.streams, preferredAddonUrl)
        return ResolvedPlayback(withInfo, resolution, top?.let(resolution.streams::indexOf) ?: -1)
    }

    /**
     * The episode after [finished] (resolved and ready to auto-play), or [NextEpisode.CaughtUp]. Follows
     * [finished]'s shuffle session (sequential, show-wide or single-season) and carries it forward.
     */
    suspend fun next(finished: PlaybackTarget, preferredAddonUrl: String?, random: Random = Random.Default): NextEpisode {
        val episode = finished.episode ?: return NextEpisode.CaughtUp
        val details = finished.details
        val pick = EpisodeNavigator.next(episode, details.seasons, details.lastAired, finished.shuffle, random)
            ?: return NextEpisode.CaughtUp
        val target = PlaybackTarget(finished.profileId, details, finished.imdbId, pick.episode, shuffle = pick.session)
        return NextEpisode.Found(resolve(target, preferredAddonUrl))
    }

    /** One season's episodes for the in-player Episodes browser; empty when TMDB can't be reached. */
    suspend fun episodes(tmdbId: Int, season: Int): List<Episode> =
        orNullUnlessCancelled { titles.episodes(tmdbId, season) }.orEmpty()

    /** Fills in the episode's title and runtime (for watch progress and the resume estimate). */
    suspend fun withEpisodeInfo(target: PlaybackTarget): PlaybackTarget {
        val ref: EpisodeRef = target.episode ?: return target
        if (target.episodeTitle != null) return target
        val episode = episodes(target.details.media.tmdbId, ref.season).firstOrNull { it.ref == ref } ?: return target
        return target.copy(episodeTitle = episode.title, episodeRuntimeMinutes = episode.runtimeMinutes)
    }
}
