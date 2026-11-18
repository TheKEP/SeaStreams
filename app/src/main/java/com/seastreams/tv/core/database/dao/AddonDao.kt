package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.seastreams.tv.core.database.entity.AddonEntity
import kotlinx.coroutines.flow.Flow

/** Installed addons. Every read and write is scoped to one profile's list. */
@Dao
interface AddonDao {

    @Query("SELECT * FROM addons WHERE profileId = :profileId ORDER BY sortOrder ASC")
    fun observeAll(profileId: Long): Flow<List<AddonEntity>>

    @Query("SELECT * FROM addons WHERE profileId = :profileId AND enabled = 1 ORDER BY sortOrder ASC")
    fun observeEnabled(profileId: Long): Flow<List<AddonEntity>>

    @Query("SELECT * FROM addons WHERE profileId = :profileId ORDER BY sortOrder ASC")
    suspend fun getAll(profileId: Long): List<AddonEntity>

    @Query("SELECT * FROM addons WHERE profileId = :profileId AND manifestUrl = :manifestUrl")
    suspend fun getByUrl(profileId: Long, manifestUrl: String): AddonEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM addons WHERE profileId = :profileId")
    suspend fun nextSortOrder(profileId: Long): Int

    /** Ids of profiles that do not have [manifestUrl] installed (used to repair built-in addons). */
    @Query("SELECT id FROM profiles WHERE id NOT IN (SELECT profileId FROM addons WHERE manifestUrl = :manifestUrl)")
    suspend fun profileIdsMissing(manifestUrl: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(addon: AddonEntity)

    /** Inserts only addons the profile does not have yet (used for default seeding). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(addons: List<AddonEntity>): List<Long>

    @Query("UPDATE addons SET enabled = :enabled WHERE profileId = :profileId AND manifestUrl = :manifestUrl")
    suspend fun setEnabled(profileId: Long, manifestUrl: String, enabled: Boolean)

    @Query("UPDATE addons SET customName = :customName WHERE profileId = :profileId AND manifestUrl = :manifestUrl")
    suspend fun rename(profileId: Long, manifestUrl: String, customName: String?)

    @Query("UPDATE addons SET sortOrder = :sortOrder WHERE profileId = :profileId AND manifestUrl = :manifestUrl")
    suspend fun updateSortOrder(profileId: Long, manifestUrl: String, sortOrder: Int)

    /** Rewrites the profile's sort orders so addons appear in exactly the order of [manifestUrlsInOrder]. */
    @Transaction
    suspend fun reorder(profileId: Long, manifestUrlsInOrder: List<String>) {
        manifestUrlsInOrder.forEachIndexed { index, url -> updateSortOrder(profileId, url, index) }
    }

    @Query("DELETE FROM addons WHERE profileId = :profileId AND manifestUrl = :manifestUrl")
    suspend fun delete(profileId: Long, manifestUrl: String)

    /** Removes every addon of [profileId] (profile deletion; the foreign key also cascades). */
    @Query("DELETE FROM addons WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)
}
