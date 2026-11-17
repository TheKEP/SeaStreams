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
     * not finished (position below [COMPLETION_RATIO] of its duration), newest first. A title whose
     * newest record was dismissed (long-press `Remove`) stays hidden until it is played again.
     */
    @Query(
        """
        SELECT * FROM watch_progress w
        WHERE w.profileId = :profileId
          AND w.positionMs > 0
          AND w.durationMs > 0
          AND w.positionMs < w.durationMs * $COMPLETION_RATIO
          AND w.dismissedFromContinueWatching = 0
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

    /** Every saved episode of one season of a show, in episode order. */
    @Query(
        """
        SELECT * FROM watch_progress
        WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType AND seasonNumber = :seasonNumber
        ORDER BY episodeNumber
        """,
    )
    suspend fun getSeason(profileId: Long, tmdbId: Int, mediaType: MediaType, seasonNumber: Int): List<WatchProgressEntity>

    /** TMDB ids of [mediaType] titles with a finished (`>= COMPLETION_RATIO`) record, for watched badges. */
    @Query(
        """
        SELECT DISTINCT tmdbId FROM watch_progress
        WHERE profileId = :profileId AND mediaType = :mediaType
          AND durationMs > 0 AND positionMs >= durationMs * $COMPLETION_RATIO
        """,
    )
    fun observeCompletedIds(profileId: Long, mediaType: MediaType): Flow<List<Int>>

    @Upsert
    suspend fun upsert(progress: WatchProgressEntity)

    @Upsert
    suspend fun upsertAll(progress: List<WatchProgressEntity>)

    /** Forgets the listed episodes of one season (`Mark unwatched`). */
    @Query(
        """
        DELETE FROM watch_progress
        WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType
          AND seasonNumber = :seasonNumber AND episodeNumber IN (:episodeNumbers)
        """,
    )
    suspend fun deleteEpisodes(profileId: Long, tmdbId: Int, mediaType: MediaType, seasonNumber: Int, episodeNumbers: List<Int>)

    /**
     * Continue Watching `Remove`: hides one movie (season/episode `0`) or one episode from the row.
     * Position, duration and watched state are untouched; the next progress write clears the flag.
     */
    @Query(
        """
        UPDATE watch_progress SET dismissedFromContinueWatching = 1
        WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType
          AND seasonNumber = :seasonNumber AND episodeNumber = :episodeNumber
        """,
    )
    suspend fun dismissFromContinueWatching(
        profileId: Long,
        tmdbId: Int,
        mediaType: MediaType,
        seasonNumber: Int,
        episodeNumber: Int,
    )

    /**
     * Continue Watching `Clear progress`: forgets one movie (season/episode `0`) or one single episode,
     * leaving every other episode of the show as it was.
     */
    @Query(
        """
        DELETE FROM watch_progress
        WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType
          AND seasonNumber = :seasonNumber AND episodeNumber = :episodeNumber
        """,
    )
    suspend fun clearItemProgress(
        profileId: Long,
        tmdbId: Int,
        mediaType: MediaType,
        seasonNumber: Int,
        episodeNumber: Int,
    )

    @Query(
        "DELETE FROM watch_progress WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType",
    )
    suspend fun clearForTitle(profileId: Long, tmdbId: Int, mediaType: MediaType)

    companion object {
        /** PRD §9: `>= 90%` watched counts as finished. */
        const val COMPLETION_RATIO = 0.90
        const val CONTINUE_WATCHING_LIMIT = 10
    }
}
