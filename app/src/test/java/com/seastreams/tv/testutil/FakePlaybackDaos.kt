package com.seastreams.tv.testutil

import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.LibraryItemWithActivity
import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.model.TitleDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory [LibraryDao] with Room's composite primary key + `OnConflictStrategy.IGNORE` semantics. */
class FakeLibraryDao : LibraryDao {
    val rows = MutableStateFlow<List<LibraryItemEntity>>(emptyList())

    /** Counts insert calls, including ignored ones. */
    var insertCalls = 0
        private set

    private fun LibraryItemEntity.sameKey(profileId: Long, tmdbId: Int, mediaType: MediaType) =
        this.profileId == profileId && this.tmdbId == tmdbId && this.mediaType == mediaType

    override fun observeWithActivity(profileId: Long): Flow<List<LibraryItemWithActivity>> =
        rows.map { list -> list.filter { it.profileId == profileId }.map { LibraryItemWithActivity(it, null) } }

    override fun observeContains(profileId: Long, tmdbId: Int, mediaType: MediaType): Flow<Boolean> =
        rows.map { list -> list.any { it.sameKey(profileId, tmdbId, mediaType) } }

    override suspend fun contains(profileId: Long, tmdbId: Int, mediaType: MediaType): Boolean =
        rows.value.any { it.sameKey(profileId, tmdbId, mediaType) }

    override suspend fun insert(item: LibraryItemEntity): Long {
        insertCalls++
        if (contains(item.profileId, item.tmdbId, item.mediaType)) return -1L
        rows.update { it + item }
        return rows.value.size.toLong()
    }

    override suspend fun insertAll(items: List<LibraryItemEntity>): List<Long> = items.map { insert(it) }

    override suspend fun remove(profileId: Long, tmdbId: Int, mediaType: MediaType) =
        rows.update { list -> list.filterNot { it.sameKey(profileId, tmdbId, mediaType) } }

    override suspend fun getAll(profileId: Long): List<LibraryItemEntity> = rows.value.filter { it.profileId == profileId }

    override suspend fun clear(profileId: Long) = rows.update { list -> list.filterNot { it.profileId == profileId } }
}

/** In-memory [WatchProgressDao] keyed like the Room table. */
class FakeWatchProgressDao : WatchProgressDao {
    val rows = MutableStateFlow<List<WatchProgressEntity>>(emptyList())

    private fun WatchProgressEntity.key() = listOf(profileId, tmdbId, mediaType, seasonNumber, episodeNumber)

    /** Mirrors the Room query: each title's newest record, if in progress and not dismissed. */
    override fun observeContinueWatching(profileId: Long, limit: Int): Flow<List<WatchProgressEntity>> =
        rows.map { list ->
            list.filter { it.profileId == profileId }
                .groupBy { it.tmdbId to it.mediaType }
                .values.map { title -> title.maxBy { it.updatedAtMillis } }
                .filter {
                    it.positionMs > 0 && it.durationMs > 0 && !it.dismissedFromContinueWatching &&
                        it.positionMs < it.durationMs * WatchProgressDao.COMPLETION_RATIO
                }
                .sortedByDescending { it.updatedAtMillis }
                .take(limit)
        }

    override fun observeForTitle(profileId: Long, tmdbId: Int, mediaType: MediaType): Flow<List<WatchProgressEntity>> =
        rows.map { list -> list.filter { it.profileId == profileId && it.tmdbId == tmdbId && it.mediaType == mediaType } }

    override suspend fun get(profileId: Long, tmdbId: Int, mediaType: MediaType, seasonNumber: Int, episodeNumber: Int): WatchProgressEntity? =
        rows.value.firstOrNull { it.key() == listOf(profileId, tmdbId, mediaType, seasonNumber, episodeNumber) }

    override fun observeCompletedIds(profileId: Long, mediaType: MediaType): Flow<List<Int>> =
        rows.map { list ->
            list.filter {
                it.profileId == profileId && it.mediaType == mediaType &&
                    it.durationMs > 0 && it.positionMs >= it.durationMs * WatchProgressDao.COMPLETION_RATIO
            }.map { it.tmdbId }.distinct()
        }

    override suspend fun upsert(progress: WatchProgressEntity) =
        rows.update { list -> list.filterNot { it.key() == progress.key() } + progress }

    override suspend fun getSeason(profileId: Long, tmdbId: Int, mediaType: MediaType, seasonNumber: Int): List<WatchProgressEntity> =
        rows.value.filter { it.key().take(4) == listOf(profileId, tmdbId, mediaType, seasonNumber) }.sortedBy { it.episodeNumber }

    override suspend fun upsertAll(progress: List<WatchProgressEntity>) = progress.forEach { upsert(it) }

    override suspend fun deleteEpisodes(profileId: Long, tmdbId: Int, mediaType: MediaType, seasonNumber: Int, episodeNumbers: List<Int>) =
        rows.update { list ->
            list.filterNot { it.key().take(4) == listOf(profileId, tmdbId, mediaType, seasonNumber) && it.episodeNumber in episodeNumbers }
        }

    override suspend fun dismissFromContinueWatching(
        profileId: Long,
        tmdbId: Int,
        mediaType: MediaType,
        seasonNumber: Int,
        episodeNumber: Int,
    ) = rows.update { list ->
        val key = listOf(profileId, tmdbId, mediaType, seasonNumber, episodeNumber)
        list.map { if (it.key() == key) it.copy(dismissedFromContinueWatching = true) else it }
    }

    override suspend fun clearItemProgress(
        profileId: Long,
        tmdbId: Int,
        mediaType: MediaType,
        seasonNumber: Int,
        episodeNumber: Int,
    ) = rows.update { list -> list.filterNot { it.key() == listOf(profileId, tmdbId, mediaType, seasonNumber, episodeNumber) } }

    override suspend fun clearForTitle(profileId: Long, tmdbId: Int, mediaType: MediaType) =
        rows.update { list -> list.filterNot { it.profileId == profileId && it.tmdbId == tmdbId && it.mediaType == mediaType } }
}

fun testMedia(tmdbId: Int, type: MediaType = MediaType.MOVIE, runtimeMinutes: Int? = 100) = MediaItem(
    tmdbId = tmdbId,
    mediaType = type,
    title = "Title $tmdbId",
    overview = "Overview",
    posterUrl = null,
    backdropUrl = null,
    heroBackdropUrl = null,
    logoUrl = null,
    year = 2026,
    ratingPercent = 80,
    genres = listOf("Drama"),
    runtimeMinutes = runtimeMinutes,
    certification = "PG",
    isAdultContent = false,
)

fun testDetails(
    tmdbId: Int,
    type: MediaType = MediaType.MOVIE,
    seasons: List<SeasonInfo> = emptyList(),
    runtimeMinutes: Int? = 100,
) = TitleDetails(
    media = testMedia(tmdbId, type, runtimeMinutes),
    cast = listOf("Actor A", "Actor B"),
    genreIds = listOf(18, 35),
    posterPath = "/poster.jpg",
    backdropPath = "/backdrop.jpg",
    seasons = seasons,
    lastAired = null,
)
