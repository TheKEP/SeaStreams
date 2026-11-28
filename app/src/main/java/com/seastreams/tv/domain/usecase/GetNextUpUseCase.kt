package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.NextUpDismissalDao
import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.NextUpDismissalEntity
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.NextUpMarker
import com.seastreams.tv.domain.model.TitleDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Next Up cards for the Continue Watching row (PRD §3, Milestone 13): for each show whose newest watched episode is
 * finished — watched shows and Library shows alike ([NextUpPolicy.candidates]) — the next aired episode, at 0%.
 *
 * Each candidate costs one `/tv/{id}` lookup (HTTP-cached 12h, plus [DETAILS_TTL_MILLIS] in memory so playback
 * progress ticks don't repeat it). `/external_ids` is never called here (CLAUDE.md rule 5): it runs only when a card
 * is played ([ResolveContinueWatchingTargetUseCase]). The profile's *current* age ceiling is checked here, before
 * anything reaches a ViewModel; a show whose details can't be loaded is left out (its seasons are needed).
 */
@Singleton
class GetNextUpUseCase @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
    private val dismissalDao: NextUpDismissalDao,
    private val titles: TitleDetailsRepository,
    private val ageEngine: AgeRestrictionEngine,
    private val settings: SettingsDataStore,
    private val clock: Clock,
) {
    private val detailsCache = ConcurrentHashMap<Int, CachedDetails>()

    /** Emits nothing but empty lists while the profile's *Show next up in continue watching* is off. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(profile: ProfileEntity): Flow<List<ContinueWatchingItem>> =
        settings.settings.map { it.player.showNextUpInContinueWatching }
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) {
                    flowOf(emptyList())
                } else {
                    combine(
                        watchProgressDao.observeLatestShowActivity(profile.id).map(NextUpPolicy::candidates).distinctUntilChanged(),
                        dismissalDao.observe(profile.id),
                    ) { seeds, dismissals -> seeds to dismissals.associateBy { it.tmdbId } }
                        .mapLatest { (seeds, dismissals) -> resolve(seeds, dismissals, profile.toAgeRestriction()) }
                }
            }

    internal suspend fun resolve(
        seeds: List<NextUpSeed>,
        dismissals: Map<Int, NextUpDismissalEntity>,
        restriction: AgeRestriction?,
    ): List<ContinueWatchingItem> = coroutineScope {
        seeds.map { seed -> async { resolveOne(seed, dismissals[seed.tmdbId], restriction) } }.awaitAll().filterNotNull()
    }

    private suspend fun resolveOne(
        seed: NextUpSeed,
        dismissal: NextUpDismissalEntity?,
        restriction: AgeRestriction?,
    ): ContinueWatchingItem? {
        val details = detailsFor(seed.tmdbId) ?: return null
        val media = details.media
        if (!ageEngine.isAllowed(media.certification, restriction, media.isAdultContent)) return null
        val next = NextUpPolicy.nextEpisode(seed, details) ?: return null
        if (NextUpPolicy.isDismissed(dismissal, next, details.lastAired, seed.updatedAtMillis)) return null
        return ContinueWatchingItem(
            media = media,
            seasonNumber = next.season,
            episodeNumber = next.episode,
            progress = 0f,
            nextUp = NextUpMarker(details.lastAired),
            activityAtMillis = NextUpPolicy.activityAt(seed, next, details, clock.zone),
        )
    }

    /** The show's details, unrestricted (the caller age-checks); `null` when they can't be loaded. Failures aren't cached. */
    private suspend fun detailsFor(tmdbId: Int): TitleDetails? {
        val now = clock.millis()
        detailsCache[tmdbId]?.takeIf { now - it.loadedAtMillis < DETAILS_TTL_MILLIS }?.let { return it.details }
        val details = orNullUnlessCancelled { titles.load(MediaRef(tmdbId, MediaType.TV), restriction = null) } ?: return null
        detailsCache[tmdbId] = CachedDetails(details, now)
        return details
    }

    private data class CachedDetails(val details: TitleDetails, val loadedAtMillis: Long)

    companion object {
        /** How long a show's details are reused in memory; the 12h HTTP cache backs them afterwards. */
        const val DETAILS_TTL_MILLIS = 10 * 60 * 1000L
    }
}
