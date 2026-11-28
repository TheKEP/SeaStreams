package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

/** A library item joined with the most recent playback activity across its episodes. */
data class LibraryItemWithActivity(
    @Embedded val item: LibraryItemEntity,
    val lastWatchedAtMillis: Long?,
)

@Dao
interface LibraryDao {

    @Query(
        """
        SELECT l.*, (
            SELECT MAX(w.updatedAtMillis) FROM watch_progress w
            WHERE w.profileId = l.profileId AND w.tmdbId = l.tmdbId AND w.mediaType = l.mediaType
        ) AS lastWatchedAtMillis
        FROM library_items l
        WHERE l.profileId = :profileId
        """,
    )
    fun observeWithActivity(profileId: Long): Flow<List<LibraryItemWithActivity>>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM library_items
            WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType
        )
        """,
    )
    fun observeContains(profileId: Long, tmdbId: Int, mediaType: MediaType): Flow<Boolean>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM library_items
            WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType
        )
        """,
    )
    suspend fun contains(profileId: Long, tmdbId: Int, mediaType: MediaType): Boolean

    /** Idempotent: returns `-1` and changes nothing when the title is already saved for the profile. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: LibraryItemEntity): Long

    /**
     * Batch insert for the MDBList import: a title already saved for the profile is skipped (`-1`),
     * never overwritten, so its original `addedAtMillis` and metadata stay intact.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<LibraryItemEntity>): List<Long>

    /** TMDB ids of the profile's saved titles of [mediaType]; the MDBList import skips these up front. */
    @Query("SELECT tmdbId FROM library_items WHERE profileId = :profileId AND mediaType = :mediaType")
    suspend fun tmdbIds(profileId: Long, mediaType: MediaType): List<Int>

    @Query(
        "DELETE FROM library_items WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType",
    )
    suspend fun remove(profileId: Long, tmdbId: Int, mediaType: MediaType)

    /** Saved movies whose keywords haven't been looked up yet (rows from before Room v11, or an old backup). */
    @Query("SELECT tmdbId FROM library_items WHERE profileId = :profileId AND mediaType = 'MOVIE' AND keywordIds IS NULL")
    suspend fun moviesMissingKeywords(profileId: Long): List<Int>

    /** Sets a title's keyword ids, only while still unknown, so a newer save is never overwritten. */
    @Query(
        "UPDATE library_items SET keywordIds = :keywordIds " +
            "WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType AND keywordIds IS NULL",
    )
    suspend fun fillMissingKeywordIds(profileId: Long, tmdbId: Int, mediaType: MediaType, keywordIds: String)

    /** [fillMissingKeywordIds] for a batch of movies in one transaction, so the Library re-derives once. */
    @Transaction
    suspend fun fillMissingMovieKeywordIds(profileId: Long, keywordIds: Map<Int, String>) {
        keywordIds.forEach { (tmdbId, ids) -> fillMissingKeywordIds(profileId, tmdbId, MediaType.MOVIE, ids) }
    }

    @Query("SELECT * FROM library_items WHERE profileId = :profileId")
    suspend fun getAll(profileId: Long): List<LibraryItemEntity>

    /** Used by "Clear Library", once its Undo window has passed. */
    @Query("DELETE FROM library_items WHERE profileId = :profileId")
    suspend fun clear(profileId: Long)
}
