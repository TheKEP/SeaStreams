package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {

    /**
     * Continue Watching: per title, the most recently touched movie/episode that is started but
     * not finished (position below [COMPLETION_RATIO] of its duration), newest first.
     */
    @Query(
        """
        SELECT * FROM watch_progress w
        WHERE w.profileId = :profileId
          AND w.positionMs > 0
          AND w.durationMs > 0
          AND w.positionMs < w.durationMs * $COMPLETION_RATIO
          AND w.updatedAtMillis = (
              SELECT MAX(w2.updatedAtMillis) FROM watch_progress w2
              WHERE w2.profileId = w.profileId AND w2.tmdbId = w.tmdbId AND w2.mediaType = w.mediaType
          )
        ORDER BY w.updatedAtMillis DESC
        LIMIT :limit
        """,
    )
    fun observeContinueWatching(
        profileId: Long,
        limit: Int = CONTINUE_WATCHING_LIMIT,
    ): Flow<List<WatchProgressEntity>>

    @Query(
        """
        SELECT * FROM watch_progress
        WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType
        ORDER BY seasonNumber, episodeNumber
        """,
    )
    fun observeForTitle(profileId: Long, tmdbId: Int, mediaType: MediaType): Flow<List<WatchProgressEntity>>

    @Query(
        """
        SELECT * FROM watch_progress
        WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType
          AND seasonNumber = :seasonNumber AND episodeNumber = :episodeNumber
        """,
    )
    suspend fun get(
        profileId: Long,
        tmdbId: Int,
        mediaType: MediaType,
        seasonNumber: Int,
        episodeNumber: Int,
    ): WatchProgressEntity?

    @Upsert
    suspend fun upsert(progress: WatchProgressEntity)

    @Query(
        "DELETE FROM watch_progress WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType",
    )
    suspend fun clearForTitle(profileId: Long, tmdbId: Int, mediaType: MediaType)

    companion object {
        const val COMPLETION_RATIO = 0.95
        const val CONTINUE_WATCHING_LIMIT = 10
    }
}
