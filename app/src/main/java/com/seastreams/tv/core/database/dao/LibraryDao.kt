package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: LibraryItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<LibraryItemEntity>): List<Long>

    @Query(
        "DELETE FROM library_items WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType",
    )
    suspend fun remove(profileId: Long, tmdbId: Int, mediaType: MediaType)

    @Query("SELECT * FROM library_items WHERE profileId = :profileId")
    suspend fun getAll(profileId: Long): List<LibraryItemEntity>

    /** Used by "Clear Library"; callers snapshot via [getAll] first so Undo can re-insert. */
    @Query("DELETE FROM library_items WHERE profileId = :profileId")
    suspend fun clear(profileId: Long)
}
