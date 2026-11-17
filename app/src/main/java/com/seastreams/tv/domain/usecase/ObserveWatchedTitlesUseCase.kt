package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Titles a profile has watched, for the catalog/grid checkmark badge (PRD §1). A movie counts once its
 * single record reaches the `>= 90%` rule (or was `Mark watched`). Shows are left out: whether every
 * episode is finished can't be known from catalog data without per-show TMDB calls; their episodes
 * carry the badge on the Episodes screen instead.
 */
class ObserveWatchedTitlesUseCase @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
) {
    fun observe(profileId: Long): Flow<Set<MediaRef>> =
        watchProgressDao.observeCompletedIds(profileId, MediaType.MOVIE)
            .map { ids -> ids.mapTo(HashSet()) { MediaRef(it, MediaType.MOVIE) } }
            .distinctUntilChanged()
}
