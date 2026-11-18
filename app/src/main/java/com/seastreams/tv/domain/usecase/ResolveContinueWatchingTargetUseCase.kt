package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.details.RestrictedTitleException
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.playback.PlaybackTarget
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** What a Continue Watching card resolves to for direct playback. */
sealed interface ContinueWatchingTarget {
    data class Ready(val target: PlaybackTarget) : ContinueWatchingTarget

    /** The profile's age ceiling doesn't allow the title. */
    data object Restricted : ContinueWatchingTarget

    /** The profile is gone or the title couldn't be loaded. */
    data object Unavailable : ContinueWatchingTarget
}

/**
 * Turns a Continue Watching card into a [PlaybackTarget] without opening Details (PRD §3, *Auto-play
 * from Continue Watching*): loads the title age-checked for the profile ([TitleDetailsRepository.load]),
 * then its IMDb id — one lazy `/external_ids` lookup for the single title the user clicked (CLAUDE.md
 * rule 5). Shows play the card's exact episode, not Details' next-up one.
 */
class ResolveContinueWatchingTargetUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val titles: TitleDetailsRepository,
) {
    suspend operator fun invoke(profileId: Long, item: ContinueWatchingItem): ContinueWatchingTarget {
        val profile = profiles.getProfile(profileId) ?: return ContinueWatchingTarget.Unavailable
        val ref = item.media.ref
        val details = try {
            titles.load(ref, profile.toAgeRestriction())
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestrictedTitleException) {
            return ContinueWatchingTarget.Restricted
        } catch (e: Exception) {
            return ContinueWatchingTarget.Unavailable
        }
        val imdbId = orNullUnlessCancelled { titles.imdbIdForDetails(ref) }
        if (ref.mediaType != MediaType.TV) {
            return ContinueWatchingTarget.Ready(PlaybackTarget(profileId, details, imdbId, episode = null))
        }
        val episode = EpisodeRef(item.seasonNumber, item.episodeNumber)
        // Title and runtime only label the player and size progress; playback works without them.
        val info = orNullUnlessCancelled { titles.episodes(ref.tmdbId, episode.season) }?.firstOrNull { it.ref == episode }
        return ContinueWatchingTarget.Ready(
            PlaybackTarget(profileId, details, imdbId, episode, info?.title, info?.runtimeMinutes),
        )
    }
}
