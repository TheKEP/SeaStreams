package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

/**
 * The profile's last [WatchProgressDao.CONTINUE_WATCHING_LIMIT] in-progress movies/episodes,
 * newest first, with TMDB metadata for the expanded card.
 *
 * Kids profiles are re-checked against their *current* age ceiling (it may have been lowered since
 * the title was watched); a title whose rating can't be confirmed is dropped for Kids.
 */
class GetContinueWatchingUseCase @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
    private val hydrator: MediaHydrator,
    private val imageUrls: TmdbImageUrls,
    private val ageEngine: AgeRestrictionEngine,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(profile: ProfileEntity): Flow<List<ContinueWatchingItem>> =
        watchProgressDao.observeContinueWatching(profile.id)
            .mapLatest { rows -> resolve(rows, profile.toAgeRestriction()) }

    internal suspend fun resolve(rows: List<WatchProgressEntity>, restriction: AgeRestriction?): List<ContinueWatchingItem> =
        coroutineScope {
            rows.map { row -> async { resolveOne(row, restriction) } }.awaitAll().filterNotNull()
        }

    private suspend fun resolveOne(row: WatchProgressEntity, restriction: AgeRestriction?): ContinueWatchingItem? {
        val hydrated = orNullUnlessCancelled { hydrator.hydrate(MediaRef(row.tmdbId, row.mediaType)) }
        val media = when {
            hydrated != null -> {
                if (!ageEngine.isAllowed(hydrated.certification, restriction, hydrated.isAdultContent)) return null
                hydrated
            }
            restriction != null -> return null
            else -> row.toFallbackMedia()
        }
        return ContinueWatchingItem(
            media = media,
            seasonNumber = row.seasonNumber,
            episodeNumber = row.episodeNumber,
            progress = (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f),
        )
    }

    /** Adult profiles still see the title offline, using what was stored when playback started. */
    private fun WatchProgressEntity.toFallbackMedia() = MediaItem(
        tmdbId = tmdbId,
        mediaType = mediaType,
        title = title,
        overview = episodeTitle.orEmpty(),
        posterUrl = imageUrls.poster(posterPath),
        backdropUrl = imageUrls.backdrop(backdropPath),
        heroBackdropUrl = imageUrls.heroBackdrop(backdropPath),
        logoUrl = null,
        year = null,
        ratingPercent = null,
        genres = emptyList(),
        runtimeMinutes = null,
        certification = null,
        isAdultContent = false,
    )
}
